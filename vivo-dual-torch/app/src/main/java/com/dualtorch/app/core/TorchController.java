package com.dualtorch.app.core;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 调度中心：把"点亮通道 A / 通道 B"的意图翻译成具体通路上的操作。
 *
 * 关于 A/B 的语义（真机验证后修正）：
 * 在**主摄会话**里，HAL 是按焦段决定点几颗灯的，无法单独点长焦那颗；
 * 2.0× 正好是 vivo 的双灯阈值，所以"只要 B"也会变成"两颗都亮"。
 * 想只点长焦灯，必须换成**长焦相机自己的会话**（zoom=1.0 即该镜头原生焦段）。
 */
public class TorchController {

    public enum Mode {
        AUTO("自动"), SYSTEM("系统手电"), CAMERA("相机会话"), VENDOR("Vendor"), ROOT("Root");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public interface Listener {
        void onState(boolean a, boolean b, String note);
    }

    private static final int[] SOS = {180, 180, 180, 180, 180, 520, 520, 180, 520, 180, 520, 1200};

    /** 整个进程只有一份：界面、服务、磁贴改的是同一套状态，不会互相打架。 */
    private static TorchController instance;

    public static synchronized TorchController get(Context ctx) {
        if (instance == null) {
            instance = new TorchController(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized TorchController peek() {
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SystemTorchEngine sys;
    private CameraSessionEngine cam;
    private final List<RootEngine.Node> nodes = new ArrayList<>();
    private volatile boolean rootChecked;

    private Mode mode = Mode.AUTO;
    /** 0 = 关，1..max = 硬件第几档。 */
    private int levelIndex = 1;

    /**
     * 通路切换的最小间隔。
     *
     * 只用来防「同一次点击被处理两遍」这种抖动，不再用来等 HAL。
     * 真正需要等的是相机会话释放，交给 waitSessionClosed 去轮询。
     */
    private static final long MODE_GAP_MS = 150;
    private Mode lastAppliedMode;
    private long lastAppliedAt;
    /** 每次 apply 递增，用来作废已经排队的旧任务，避免「切回去又被旧任务点亮」。 */
    private int applyGen;
    private float zoom = 1f;
    private int style = CameraSessionEngine.STYLE_TORCH;
    private final Map<String, String> vendor = new LinkedHashMap<>();

    private boolean a;
    private boolean b;
    /** 会话正在打开（camera.open 还没回调），此时 setTorchMode 会撞 CAMERA_IN_USE。 */
    private volatile boolean opening;
    private String idA = "0";
    private String idB;
    private String teleId;
    private String sessionCamera;

    /** 系统手电最近一次回读到的档位；-1 表示没读到。 */
    private int torchReadback = -1;
    /** 测试用：强制指定硬件档位，0 表示按百分比映射。 */
    private int forcedLevel = 0;

    private int strobeHz = 0;
    private boolean sos = false;
    private boolean strobeState;
    private int sosIndex;
    private Runnable strobeTask;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private TorchController(Context ctx) {
        this.ctx = ctx;
        try {
            sys = new SystemTorchEngine(this.ctx);
            String[] ch = CameraProbe.resolveChannels(sys);
            idA = ch[0];
            idB = ch[1];
            teleId = CameraProbe.longestBackCamera(sys);
            String saved = Prefs.torchId(this.ctx);
            if (saved != null) {
                idA = saved;
                LogBus.i("使用自检选定的手电相机 camera " + saved);
            }
        } catch (Throwable t) {
            LogBus.e("初始化相机服务失败: " + t);
            sys = null;
        }
        syncHwMax();
        levelIndex = Prefs.hwMax(this.ctx);
        cam = new CameraSessionEngine(this.ctx);
        // root 检测会阻塞（Magisk 还会弹授权框），必须放到后台，否则可能 ANR
        new Thread(() -> {
            try {
                if (RootEngine.hasRoot()) {
                    List<RootEngine.Node> found = RootEngine.scan();
                    synchronized (nodes) {
                        nodes.clear();
                        nodes.addAll(found);
                    }
                    LogBus.i("检测到 root，发现 " + found.size() + " 个 LED 节点");
                }
            } catch (Throwable ignored) {
            }
            rootChecked = true;
        }, "root-probe").start();
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** @deprecated 用 addListener / removeListener */
    @Deprecated
    public void setListener(Listener l) {
        addListener(l);
    }

    public SystemTorchEngine sys() {
        return sys;
    }

    public String idA() {
        return idA;
    }

    public String idB() {
        return idB;
    }

    public String teleId() {
        return teleId;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode m) {
        mode = m;
        LogBus.i("策略切换 → " + m.label);
        apply();
    }

    /**
     * 调亮度：档位编号直接就是硬件档位，不做百分比换算。
     *
     * 硬件上报几档，界面就给几档；只有系统手电这条通路会真的改电流，
     * 相机会话里 HAL 忽略强度请求，所以双灯档不动。
     */
    public void setLevelIndex(int idx) {
        int max = maxStrength();
        levelIndex = Math.max(Prefs.LEVEL_OFF, Math.min(max, idx));
        if (levelIndex == Prefs.LEVEL_OFF) {
            allOff();
            return;
        }
        if (effectiveMode() == Mode.SYSTEM) {
            forcedLevel = levelIndex;
            apply();
        }
    }

    public int levelIndex() {
        return levelIndex;
    }

    /** 硬件档位上限，同时刷新缓存，保证界面和硬件一致。 */
    public int maxStrength() {
        int max;
        try {
            max = sys == null ? 1 : sys.maxStrength(idA);
        } catch (Throwable t) {
            max = 1;
        }
        max = Math.max(1, max);
        Prefs.setHwMax(ctx, max);
        return max;
    }

    private void syncHwMax() {
        maxStrength();
    }

    /** 会话 / root 通路还在用的百分比，只是按档位线性映射。 */
    private int percent() {
        int max = maxStrength();
        if (levelIndex <= Prefs.LEVEL_OFF) {
            return 0;
        }
        return Math.max(1, Math.min(100, Math.round(levelIndex * 100f / max)));
    }

    /** 系统手电最近一次回读到的档位。 */
    public int torchReadback() {
        return torchReadback;
    }

    public int strengthPercent() {
        return percent();
    }

    public void setZoom(float z) {
        zoom = z;
        if (cam.isRunning()) {
            cam.update(pickZoom(), 100);
        } else {
            apply();
        }
    }

    public void setStyle(int s) {
        style = s;
        apply();
    }

    public void setVendor(Map<String, String> v) {
        vendor.clear();
        vendor.putAll(v);
        apply();
    }

    /** 手动指定会话相机；null 表示自动。 */
    public void setSessionCamera(String id) {
        sessionCamera = id;
        LogBus.i("会话相机 → " + (id == null ? "自动" : "camera " + id));
        apply();
    }

    public void setA(boolean on) {
        a = on;
        apply();
    }

    public void setB(boolean on) {
        b = on;
        apply();
    }

    public void setBoth(boolean on) {
        a = on;
        b = on;
        apply();
    }

    /**
     * 快捷入口（磁贴 / 快捷方式 / 冷启动）用：一次性设定灯路 + 亮度，只触发一次 apply。
     *
     * 上灯 → 系统手电（实测只有这条路能只点一颗）；
     * 双灯 → 相机会话，zoom 拉到 2× 让 HAL 开两颗。
     */
    public void applyPreset(int ledSet, int level) {
        levelIndex = Math.max(Prefs.LEVEL_OFF, Math.min(maxStrength(), level));
        forcedLevel = 0;
        if (ledSet == Prefs.LED_BOTH) {
            mode = Mode.CAMERA;
            a = true;
            b = true;
            zoom = 2f;
        } else {
            mode = Mode.SYSTEM;
            a = true;
            b = false;
            zoom = 1f;
        }
        LogBus.i("应用预设 → " + Prefs.ledName(ledSet) + " · "
                + Prefs.levelName(levelIndex, maxStrength()));
        apply();
    }

    /**
     * 主界面的两档灯路。这是本 App 的核心动作：
     *
     * 广角 → 系统手电（turnOnTorchWithStrengthLevel），只点上面那颗广角 LED，
     *        这条通路认硬件档位，亮度能真的调；
     * 双灯 → 相机会话 zoom 2.0×，HAL 同时点两颗，强度固定满功率（会话里改强度无效）。
     *
     * 映射来自真机实测（vivo V2547A）：广角=单灯可调光，双灯=满功率。
     */
    public void applyFocal(int focal, int level) {
        int max = maxStrength();
        levelIndex = Math.max(Prefs.LEVEL_OFF, Math.min(max, level));
        if (levelIndex == Prefs.LEVEL_OFF) {
            allOff();
            return;
        }
        if (focal > 0) {
            mode = Mode.CAMERA;
            a = true;
            b = true;
            zoom = 2f;
            forcedLevel = 0;
        } else {
            mode = Mode.SYSTEM;
            a = true;
            b = false;
            zoom = 1f;
            forcedLevel = levelIndex;
        }
        LogBus.i("灯路 → " + Prefs.focalName(focal)
                + (focal > 0 ? "（满功率）" : "（" + Prefs.levelName(levelIndex, max) + "）"));
        apply();
    }

    /** 测试用：只走系统手电，固定 camera A，满档。 */
    public void testSystem() {
        levelIndex = maxStrength();
        forcedLevel = 0;
        mode = Mode.SYSTEM;
        a = true;
        b = false;
        zoom = 1f;
        apply();
    }

    /** 测试用：系统手电 + 指定硬件档位，1 = 最暗。用来验证档位有没有真的落到硬件。 */
    public void testSystemLevel(int level) {
        forcedLevel = Math.max(1, level);
        mode = Mode.SYSTEM;
        a = true;
        b = false;
        zoom = 1f;
        apply();
    }

    /** 测试用：主摄相机会话，直接指定 zoom，绕开双灯阈值逻辑。 */
    public void testSession(float z, int percent) {
        levelIndex = maxStrength();
        mode = Mode.CAMERA;
        a = true;
        b = true;
        zoom = z;
        if (cam.isRunning() && idA.equals(cam.runningId())) {
            cam.update(z, percent);
            cam.setFlashOn(true);
            LogBus.i("测试会话 → " + fmt(z) + " · " + percent + "%（复用已开的相机）");
            notifyState("会话 " + fmt(z) + " · " + percent + "%");
            return;
        }
        if (sys != null) {
            sys.forceOff();
        }
        CameraSessionEngine.Config cfg = new CameraSessionEngine.Config();
        cfg.zoom = z;
        cfg.style = CameraSessionEngine.STYLE_TORCH;
        cfg.strengthPercent = percent;
        startSession(idA, cfg, false);
        LogBus.i("测试会话 → camera " + idA + " · " + fmt(z) + " · " + percent + "%");
    }

    /** 当前实际回读到的硬件档位；-1 表示不支持。 */
    public int readStrengthNow() {
        try {
            return sys == null ? -1 : sys.readStrength(idA);
        } catch (Throwable t) {
            return -1;
        }
    }

    public boolean hwStrength() {
        return hwStrengthAvailable();
    }

    public boolean isA() {
        return a;
    }

    public boolean isB() {
        return b;
    }

    /** 是否已经有灯在点（用于避免界面和服务重复下发同一次开灯）。 */
    public boolean isOn() {
        return a || b;
    }

    public void setStrobe(int hz) {
        strobeHz = hz;
        sos = false;
        restartStrobe();
    }

    public void setSos() {
        sos = true;
        strobeHz = 0;
        restartStrobe();
    }

    /**
     * 会话相机选择：
     * 只要 B（长焦灯）→ 优先用长焦相机自己的会话，zoom 回 1.0；
     * 只要 A 或双灯 → 主摄会话。
     */
    private String pickSessionCamera() {
        if (sessionCamera != null) {
            return sessionCamera;
        }
        if (b && !a && teleId != null) {
            return teleId;
        }
        return idA;
    }

    private float pickZoom() {
        if (b && !a && sessionCamera == null) {
            return 1f;                       // 长焦相机的原生焦段，避开主摄的 2× 双灯阈值
        }
        if (a && b) {
            return Math.max(zoom, 2f);       // 双灯：主摄 ≥2×，HAL 才会开两颗
        }
        return zoom;
    }

    private Mode effectiveMode() {
        if (mode != Mode.AUTO) {
            return mode;
        }
        // 真机实测：只有 setTorchMode 这条通路能只点一颗灯（camera 0 只亮上面那颗），
        // 相机会话一定会被 HAL 的焦段算法接管，所以默认走系统手电。
        return sys != null ? Mode.SYSTEM : Mode.CAMERA;
    }

    public String effectiveModeLabel() {
        return effectiveMode().label;
    }

    public Mode resolvedMode() {
        return effectiveMode();
    }

    public boolean isSessionRunning() {
        return cam.isRunning();
    }

    /** 会话正在打开（openCamera 已发出、还没回调）。 */
    public boolean isSessionOpening() {
        return opening;
    }

    public void apply() {
        if (sys == null) {
            notifyState("相机服务不可用");
            return;
        }
        Mode m = effectiveMode();
        long since = SystemClock.elapsedRealtime() - lastAppliedAt;
        // 系统手电和相机会话互相抢占：刚切过就再切，HAL 往往直接不理第二次。
        // 与其失败，不如排队到间隔之后再执行。
        if (lastAppliedMode != null && m != lastAppliedMode && since < MODE_GAP_MS) {
            final int gen = ++applyGen;
            long wait = MODE_GAP_MS - since;
            LogBus.i("通路刚切过，延后 " + wait + "ms 再执行");
            notifyState("切换中 · 稍候");
            main.postDelayed(() -> {
                if (gen == applyGen) {
                    doApply();
                }
            }, wait);
            return;
        }
        applyGen++;
        doApply();
    }

    /** 真正下发的入口；apply() 负责节流与作废旧任务。 */
    private void doApply() {
        lastAppliedMode = effectiveMode();
        lastAppliedAt = SystemClock.elapsedRealtime();
        String note;
        switch (effectiveMode()) {
            case SYSTEM:
                note = applySystem();
                break;
            case ROOT:
                note = applyRoot();
                break;
            case VENDOR:
                note = applyCamera(true);
                break;
            case CAMERA:
            default:
                note = applyCamera(false);
                break;
        }
        if (note != null) {
            notifyState(note);
        }
    }

    /** 系统手电与相机会话互斥：先关会话，等相机真正释放后再点亮。 */
    private String applySystem() {
        // 会话在跑、或者正在打开，都必须先彻底放掉相机，否则系统手电会拿到 CAMERA_IN_USE
        if (cam.isRunning() || opening) {
            cam.stop();
            waitSessionClosed(1);
            return null;
        }
        return applySystemNow();
    }

    /**
     * cam.stop() 是异步的，teardown 没跑完就 setTorchMode 会拿到 CAMERA_IN_USE。
     * 先立刻查一次（多数情况 stop 已经同步释放），没释放再每 200ms 轮询，
     * 最多 6 次（约 1.2 秒）。
     */
    private void waitSessionClosed(final int attempt) {
        final int gen = applyGen;
        long delay = attempt == 0 ? 0 : 200;
        main.postDelayed(() -> {
            if (gen != applyGen) {
                return;
            }
            if (cam.isRunning() || opening) {
                if (attempt < 6) {
                    waitSessionClosed(attempt + 1);
                } else {
                    LogBus.e("相机会话迟迟没释放，仍尝试点亮系统手电");
                    opening = false;
                    notifyState(applySystemNow());
                }
                return;
            }
            notifyState(applySystemNow());
        }, delay);
    }

    /** 该机是否上报了硬件亮度档位。 */
    private boolean hwStrengthAvailable() {
        try {
            return sys != null && sys.maxStrength(idA) > 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private String applySystemNow() {
        String note;
        torchReadback = -1;
        try {
            if (a && forcedLevel > 0) {
                sys.setTorchLevel(idA, forcedLevel);
                torchReadback = sys.readStrength(idA);
            } else if (a) {
                sys.setTorch(idA, true, percent());
                torchReadback = sys.readStrength(idA);
                LogBus.ok("系统手电 ON → camera " + idA + "（"
                        + Prefs.levelName(levelIndex, maxStrength()) + "）"
                        + (torchReadback > 0 ? "，回读档 " + torchReadback : ""));
            } else {
                sys.setTorch(idA, false, 0);
            }
        } catch (Throwable t) {
            LogBus.e("camera " + idA + " 手电失败: " + t.getMessage());
        }
        if (b) {
            if (idB == null || idB.equals(idA)) {
                note = "本机只有一颗相机上报闪光灯，双灯请用「双灯」档";
                LogBus.e(note);
            } else {
                try {
                    sys.setTorch(idB, true, percent());
                    LogBus.ok("系统手电 ON → camera " + idB);
                    note = "双通道已通过系统手电点亮";
                } catch (Throwable t) {
                    note = "camera " + idB + " 手电失败: " + t.getMessage();
                    LogBus.e(note);
                }
            }
        } else {
            note = a ? "广角单灯已点亮" : "全部关闭";
        }
        return note;
    }

    /**
     * 相机会话通路。
     *
     * 实测：这条路上 FLASH_STRENGTH_LEVEL 会被 HAL 静默忽略（回读恒定），
     * 而用 setFlashOn 反复开关来凑亮度会让灯闪得毫无规律，所以这里固定满功率，
     * 亮度一律交给系统手电那条通路去调。
     */
    private String applyCamera(boolean useVendor) {
        if (!a && !b) {
            opening = false;
            cam.stop();
            return "全部关闭";
        }
        // 只有准备新开会话时才需要放掉系统手电；会话已经在跑时再 forceOff 会把它自己点灭
        if (sys != null && !cam.isRunning()) {
            sys.forceOff();
        }
        String target = pickSessionCamera();
        float z = pickZoom();

        CameraSessionEngine.Config cfg = new CameraSessionEngine.Config();
        cfg.zoom = z;
        cfg.style = style;
        cfg.strengthPercent = 100;
        if (useVendor) {
            cfg.vendor.putAll(vendor);
        }

        String note;
        if (cam.isRunning() && target.equals(cam.runningId())) {
            cam.update(z, 100);
            cam.setFlashOn(true);
            note = "会话保持 · camera " + target + " · " + fmt(z);
        } else {
            startSession(target, cfg, true);
            note = "正在建立会话 · camera " + target + " · " + fmt(z) + "（约 1–2 秒）";
        }

        // 语义提示：主摄会话里无法单独点长焦灯
        if (b && !a && target.equals(idA)) {
            note = "主摄会话无法单独点长焦灯（≥2× 会双灯），建议会话相机设为长焦";
        } else if (b && !a && teleId == null) {
            note = "未找到独立长焦相机，只能退回主摄会话";
        }
        return note;
    }

    /** 打开会话；长焦相机不允许第三方直接打开时自动回退主摄。 */
    private void startSession(final String target, final CameraSessionEngine.Config cfg,
                              final boolean allowFallback) {
        final int gen = applyGen;
        opening = true;
        cam.start(target, cfg, new CameraSessionEngine.Listener() {
            @Override
            public void onOpened(String cameraId, float z) {
                opening = false;
                if (gen != applyGen) {
                    return;      // 用户已经切走了，别把旧会话的状态顶上来
                }
                notifyState("会话就绪 · camera " + cameraId + " · " + fmt(z));
            }

            @Override
            public void onClosed() {
                opening = false;
            }

            @Override
            public void onFail(String reason) {
                opening = false;
                if (gen != applyGen) {
                    return;
                }
                if (allowFallback && !target.equals(idA)) {
                    LogBus.e("camera " + target + " 打不开（" + reason + "），回退主摄");
                    CameraSessionEngine.Config fallback = new CameraSessionEngine.Config();
                    fallback.zoom = Math.max(zoom, 2f);
                    fallback.style = cfg.style;
                    fallback.strengthPercent = cfg.strengthPercent;
                    fallback.vendor.putAll(cfg.vendor);
                    startSession(idA, fallback, false);
                    notifyState("长焦相机不可直接打开，已回退主摄（会双灯）");
                } else {
                    notifyState("相机会话失败：" + reason);
                }
            }
        });
    }

    private static String fmt(float z) {
        return String.format(Locale.US, "%.2f×", z);
    }

    private String applyRoot() {
        if (!rootChecked) {
            return "root 检测中，请稍后重试";
        }
        if (!RootEngine.hasRoot()) {
            return "未获得 root，无法直写 LED 节点";
        }
        int pct = Math.max(10, percent());
        if (RootEngine.hasMtkFlashlight()) {
            int lvl = Math.max(1, Math.round(30 * pct / 100f));
            String e1 = RootEngine.mtkTorch(0, a, lvl);
            String e2 = RootEngine.mtkTorch(1, b, lvl);
            if (e1 == null && e2 == null) {
                return "MTK 直写成功（灯路0=" + (a ? lvl : 0) + "，灯路1=" + (b ? lvl : 0) + "）";
            }
            return "MTK 直写失败：" + (e1 != null ? e1 : e2);
        }
        List<RootEngine.Node> list = RootEngine.scan();
        List<RootEngine.Node> torches = new ArrayList<>();
        for (RootEngine.Node n : list) {
            if (n.isTorch() || n.isFlash()) {
                torches.add(n);
            }
        }
        if (torches.isEmpty()) {
            return "没有找到可写的 LED 节点";
        }
        String lastErr = null;
        for (int i = 0; i < torches.size() && i < 2; i++) {
            boolean on = i == 0 ? a : b;
            int val = on ? Math.max(1, torches.get(i).max * pct / 100) : 0;
            String e = RootEngine.setBrightness(torches.get(i).path + "/brightness", val);
            if (e != null) {
                lastErr = e;
            } else {
                LogBus.ok((on ? "点亮 " : "熄灭 ") + torches.get(i).name + " = " + val);
            }
        }
        return lastErr == null
                ? "Root 直写 " + Math.min(2, torches.size()) + " 路节点"
                : "写入失败：" + lastErr;
    }

    private void rootAllOff() {
        if (!rootChecked || !RootEngine.hasRoot()) {
            return;
        }
        if (RootEngine.hasMtkFlashlight()) {
            RootEngine.mtkTorch(0, false, 0);
            RootEngine.mtkTorch(1, false, 0);
        }
        for (RootEngine.Node n : RootEngine.scan()) {
            RootEngine.setBrightness(n.path + "/brightness", 0);
        }
    }

    private void restartStrobe() {
        stopStrobe();
        if (strobeHz <= 0 && !sos) {
            if (cam.isRunning()) {
                cam.setFlashOn(true);
            } else {
                apply();
            }
            return;
        }
        LogBus.i(sos ? "SOS 模式启动" : "频闪 " + strobeHz + "Hz");
        strobeState = false;
        sosIndex = 0;
        strobeTask = new Runnable() {
            @Override
            public void run() {
                strobeState = !strobeState;
                toggleStrobe(strobeState);
                long delay;
                if (sos) {
                    delay = SOS[sosIndex % SOS.length];
                    sosIndex++;
                } else {
                    delay = Math.max(40, 500 / strobeHz);
                }
                main.postDelayed(this, delay);
            }
        };
        main.post(strobeTask);
    }

    private void toggleStrobe(boolean on) {
        Mode m = effectiveMode();
        if (m == Mode.SYSTEM) {
            try {
                sys.setTorch(idA, on, percent());
                if (b && idB != null && !idB.equals(idA)) {
                    sys.setTorch(idB, on, percent());
                }
            } catch (Throwable ignored) {
            }
        } else if (m == Mode.ROOT) {
            if (on) {
                applyRoot();
            } else {
                rootAllOff();
            }
        } else {
            cam.setFlashOn(on);
        }
    }

    private void stopStrobe() {
        if (strobeTask != null) {
            main.removeCallbacks(strobeTask);
            strobeTask = null;
        }
    }

    public void allOff() {
        a = false;
        b = false;
        strobeHz = 0;
        sos = false;
        stopStrobe();
        opening = false;
        if (sys != null) {
            sys.forceOff();
        }
        cam.stop();
        rootAllOff();
        LogBus.i("全部关闭");
        notifyState("待机 · 双灯关闭");
    }

    /** 只关灯，不销毁单例（界面退出后仍由前台服务持有）。 */
    public void release() {
        allOff();
    }

    private void notifyState(String note) {
        for (Listener l : listeners) {
            try {
                l.onState(a, b, note);
            } catch (Throwable ignored) {
            }
        }
    }
}
