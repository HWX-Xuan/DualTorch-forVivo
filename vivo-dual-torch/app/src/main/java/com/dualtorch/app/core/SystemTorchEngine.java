package com.dualtorch.app.core;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统手电筒通道：CameraManager.setTorchMode / turnOnTorchWithStrengthLevel。
 *
 * 这是不需要任何权限、不需要 root 的唯一官方通路。它的问题在于：
 * 一个 cameraId 只对应一个 Flash 单元，HAL 内部如何映射到闪光灯驱动 IC 的
 * 哪一路通道，由厂商决定，应用层无法指定。
 */
public class SystemTorchEngine {

    private final CameraManager cm;
    private final Map<String, CameraCharacteristics> all = new LinkedHashMap<>();
    private final List<String> flashCameras = new ArrayList<>();

    public SystemTorchEngine(Context ctx) throws CameraAccessException {
        cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            all.put(id, c);
            Boolean avail = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(avail)) {
                flashCameras.add(id);
            }
        }
        // 焦距短的排前面：一般对应广角主闪
        Collections.sort(flashCameras, (a, b) -> Float.compare(minFocal(all.get(a)), minFocal(all.get(b))));
    }

    private static float minFocal(CameraCharacteristics c) {
        try {
            float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (f != null && f.length > 0) {
                return f[0];
            }
        } catch (Throwable ignored) {
        }
        return 999f;
    }

    public CameraManager manager() {
        return cm;
    }

    public Map<String, CameraCharacteristics> allCameras() {
        return all;
    }

    public List<String> flashCameras() {
        return new ArrayList<>(flashCameras);
    }

    /** 是否有相机上报了闪光灯。 */
    public boolean hasFlash() {
        return !flashCameras.isEmpty();
    }

    /**
     * 手电档位的有效上限。
     *
     * 实测校准（V2547A）：HAL 声明的档位表最后一项是「闪光」档，torch 通路并不接受，
     * 请求它只会落回第二档的亮度 —— 用户看到的就是「拖到最右反而和二档一样亮」。
     * 直接删掉这一档，剩下的最高档（第四档）才是真正最亮的。
     *
     * 只有两档的机器不动，避免被削成不可调光。
     */
    public static int torchMax(int declared) {
        if (declared >= 3) {
            return declared - 1;
        }
        return Math.max(1, declared);
    }

    /** 手电筒模式的强度档位上限；1 表示不支持调光。 */
    public int maxStrength(String id) {
        CameraCharacteristics c = all.get(id);
        if (c == null) {
            return 1;
        }
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                Integer v = c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL);
                if (v != null && v > 1) {
                    return torchMax(v);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Integer v = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                if (v != null && v > 1) {
                    return torchMax(v);
                }
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    public int defaultStrength(String id) {
        CameraCharacteristics c = all.get(id);
        if (c == null) {
            return 1;
        }
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                Integer v = c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL);
                if (v != null) {
                    return Math.max(1, v);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Integer v = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL);
                if (v != null) {
                    return Math.max(1, v);
                }
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /** 百分比 → 硬件档位。0 表示不指定，交给 HAL 用默认值。 */
    public static int level(int percent, int max) {
        if (max <= 1 || percent <= 0) {
            return 0;
        }
        int p = Math.min(100, percent);
        int lvl = 1 + Math.round((max - 1) * (p / 100f));
        return Math.min(max, Math.max(1, lvl));
    }

    /** 回读当前档位；-1 表示系统不支持或灯没开。 */
    public int readStrength(String id) {
        if (Build.VERSION.SDK_INT < 33) {
            return -1;
        }
        try {
            return cm.getTorchStrengthLevel(id);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 点亮 / 熄灭。
     *
     * 关键教训（V2547A 实测）：灯已经亮着时，无论 turnOnTorchWithStrengthLevel
     * 还是 setTorchMode(true)，都可能被 HAL 当成空操作 —— 于是从暗档往上调会「调不动」。
     * 所以改档位一律先关再开，宁可闪一下，也不能让用户以为滑条坏了。
     */
    public void setTorch(String id, boolean on, int percent) throws CameraAccessException {
        if (!on) {
            cm.setTorchMode(id, false);
            return;
        }
        int max = maxStrength(id);
        if (max > 1 && percent > 0) {
            setTorchLevel(id, level(percent, max));
            return;
        }
        cm.setTorchMode(id, true);
    }

    /**
     * 按绝对档位点亮（1 = 最暗，max = 最亮）。0 表示熄灭。
     *
     * 直接改档 → 回读校验；回读没跟上才关掉重开。
     * 这样正常调档不会闪，只有在 HAL 忽略升档时才付一次闪烁的代价。
     */
    public void setTorchLevel(String id, int level) throws CameraAccessException {
        int max = maxStrength(id);
        if (level <= 0) {
            cm.setTorchMode(id, false);
            return;
        }
        if (max <= 1) {
            cm.setTorchMode(id, true);
            return;
        }
        int lv = Math.min(level, max);
        if (applyLevel(id, lv, max)) {
            return;
        }
        // 灯亮着时部分 HAL 会忽略升档：关掉重来
        try {
            cm.setTorchMode(id, false);
        } catch (Throwable ignored) {
        }
        if (applyLevel(id, lv, max)) {
            LogBus.i("camera " + id + " 关掉重开后才吃下 " + lv + " 档");
            return;
        }
        LogBus.e("camera " + id + " 档位 " + lv + " 始终没生效，退回满功率");
        cm.setTorchMode(id, true);
    }

    /** 设档位并回读；回读明显不符就返回 false。 */
    private boolean applyLevel(String id, int lv, int max) {
        try {
            cm.turnOnTorchWithStrengthLevel(id, lv);
        } catch (Throwable t) {
            return false;
        }
        int back = readStrength(id);
        // 注意：最高档也要校验。之前用 lv < max 跳过最高档，
        // 结果 HAL 把「最亮」悄悄落回二档，界面却显示已经到顶。
        if (back > 0 && back != lv) {
            LogBus.e("请求 " + lv + " 档，回读却是 " + back);
            return false;
        }
        LogBus.ok("camera " + id + " 档位 " + lv + "/" + max
                + (back > 0 ? " → 回读 " + back : ""));
        return true;
    }

    /** 该相机是否支持手电调光。 */
    public boolean hasStrength(String id) {
        return maxStrength(id) > 1;
    }

    /** 尝试把所有上报了闪光灯的相机一起点亮，观察哪几颗 LED 会亮。 */
    public int setAll(boolean on, int percent) {
        int n = 0;
        for (String id : flashCameras) {
            try {
                setTorch(id, on, percent);
                n++;
            } catch (Throwable t) {
                LogBus.e("camera " + id + " 手电失败: " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " / " + t.getMessage()));
            }
        }
        return n;
    }

    public void forceOff() {
        for (String id : all.keySet()) {
            try {
                cm.setTorchMode(id, false);
            } catch (Throwable ignored) {
            }
        }
    }
}
