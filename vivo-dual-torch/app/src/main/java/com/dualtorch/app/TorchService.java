package com.dualtorch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayDeque;

import com.dualtorch.app.core.LogBus;
import com.dualtorch.app.core.Prefs;
import com.dualtorch.app.core.TorchController;

/**
 * 手电的常驻持有者。
 *
 * 为什么必须是 Service：setTorchMode 的灯是挂在「进程」上的，进程被回收灯就灭。
 * 磁贴、快捷方式、冷启动都只是给这里发一条命令，真正的状态只在这里。
 */
public class TorchService extends Service implements TorchController.Listener {

    public static final String ACTION_CMD = "com.dualtorch.app.action.CMD";
    public static final String EXTRA_CMD = "cmd";

    public static final String CMD_ON = "on";
    /** 无条件开双灯（磁贴 / 通知 / 快捷方式用），不沿用界面上的广角档。 */
    public static final String CMD_DUAL = "dual";
    public static final String CMD_OFF = "off";
    public static final String CMD_TOGGLE = "toggle";
    public static final String CMD_CYCLE_LED = "cycle_led";
    public static final String CMD_CYCLE_BRIGHT = "cycle_bright";

    private static final String CHANNEL = "torch";
    private static final int NOTIF_ID = 4101;

    /**
     * 两条命令之间的最小间隔。
     *
     * 原本设了 900ms，是怕 HAL 丢掉连续请求。实测这个等待远比问题本身明显 ——
     * 用户连点两下，第二下要干等将近一秒。现在改成不等待，命令仍然串行执行，
     * 真正需要「等相机释放」的场景交给 TorchController 里的通路节流处理。
     */
    private static final long MIN_GAP_MS = 0;

    private TorchController controller;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private long lastExecAt;
    private boolean draining;

    /** 关灯后不立刻收摊，给「关 → 开」这种连点留一个窗口，否则后一条命令会丢。 */
    private final Runnable stopCheck = () -> {
        if (queue.isEmpty() && !Prefs.lit(this)) {
            stopSelf();
        }
    };

    /**
     * 从磁贴 / 快捷方式 / 广播里下发命令。
     * 返回 false 说明系统拒绝了后台启动，调用方应该改用 startActivityAndCollapse 兜底。
     */
    public static boolean send(Context ctx, String cmd) {
        Intent i = new Intent(ctx, TorchService.class)
                .setAction(ACTION_CMD)
                .putExtra(EXTRA_CMD, cmd);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (Throwable t) {
            LogBus.e("命令下发被拒（" + cmd + "）：" + t.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        controller = TorchController.get(this);
        controller.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent == null ? null : intent.getStringExtra(EXTRA_CMD);
        if (cmd == null) {
            cmd = CMD_TOGGLE;
        }
        // 先挂上前台通知，再干活：Android 要求 startForegroundService 后 5 秒内必须 startForeground
        startForeground(NOTIF_ID, buildNotification());
        enqueue(cmd);
        return START_NOT_STICKY;
    }

    /** 串行执行队列；不人为等待，只保证同一时刻只有一条命令在跑。 */
    private void enqueue(String cmd) {
        queue.add(cmd);
        drain();
    }

    private void drain() {
        if (draining || queue.isEmpty()) {
            return;
        }
        long wait = MIN_GAP_MS - (SystemClock.elapsedRealtime() - lastExecAt);
        if (wait > 0) {
            draining = true;
            main.postDelayed(() -> {
                draining = false;
                drain();
            }, wait);
            return;
        }
        String cmd = queue.poll();
        lastExecAt = SystemClock.elapsedRealtime();
        try {
            handle(cmd);
        } catch (Throwable t) {
            LogBus.e("执行 " + cmd + " 失败：" + t);
        }
        if (!queue.isEmpty()) {
            // 交给下一个消息循环，避免在同一次调用里连打 HAL
            main.post(this::drain);
        }
    }

    private void handle(String cmd) {
        switch (cmd) {
            case CMD_ON:
                turnOn();
                break;
            case CMD_OFF:
                turnOff();
                break;
            case CMD_DUAL:
                turnOnDual();
                break;
            case CMD_TOGGLE:
                if (Prefs.lit(this)) {
                    turnOff();
                } else {
                    turnOnDual();
                }
                break;
            case CMD_CYCLE_LED:
                cycleLed();
                break;
            case CMD_CYCLE_BRIGHT:
                cycleLevel();
                break;
            default:
                refreshNotification();
                break;
        }
    }

    /** 界面已经点过灯了，这里只把前台通知挂上，别动灯路。 */
    private void turnOn() {
        Prefs.setLit(this, true);
        refreshNotification();
    }

    /**
     * 磁贴叫「双芯闪光灯」，点开就必须两颗全亮 —— 不能沿用界面上的广角单灯。
     * 这里不看 controller.isOn()，因为它可能正亮着一颗。
     */
    private void turnOnDual() {
        int max = controller.maxStrength();
        Prefs.setFocal(this, Prefs.FOCAL_DUAL);
        Prefs.setLevelIndex(this, max);
        Prefs.setLit(this, true);
        controller.applyFocal(Prefs.FOCAL_DUAL, max);
        refreshNotification();
        LogBus.ok("快捷入口 → 双灯满功率");
    }

    private void turnOff() {
        Prefs.setLit(this, false);
        controller.allOff();
        LogBus.i("快捷入口关灯");
        stopForeground(true);
        main.removeCallbacks(stopCheck);
        main.postDelayed(stopCheck, 600);
    }

    private void cycleLed() {
        int next = (Prefs.focal(this) + 1) % 2;
        Prefs.setFocal(this, next);
        LogBus.i("灯路切换 → " + Prefs.focalName(next));
        if (Prefs.lit(this)) {
            controller.applyFocal(next, Prefs.levelIndex(this));
        }
        refreshNotification();
    }

    private void cycleLevel() {
        if (Prefs.focal(this) != Prefs.FOCAL_WIDE) {
            // 双灯档 HAL 固定满功率，切亮度没意义，先退回广角再调
            Prefs.setFocal(this, Prefs.FOCAL_WIDE);
            LogBus.i("双灯不可调光，已切回广角");
        }
        int next = Prefs.nextLevel(this, Prefs.levelIndex(this));
        Prefs.setLevelIndex(this, next);
        LogBus.i("亮度切换 → " + Prefs.levelName(next, Prefs.hwMax(this)));
        if (Prefs.lit(this)) {
            controller.applyFocal(Prefs.focal(this), next);
        }
        refreshNotification();
    }

    @Override
    public void onState(boolean a, boolean b, String note) {
        refreshNotification();
    }

    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        boolean on = Prefs.lit(this);
        String text = Prefs.summary(this);

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("双芯手电 · " + (on ? "开" : "关"))
                .setContentText(text)
                .setContentIntent(piOpen)
                .setOngoing(on)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (on) {
            b.addAction(new Notification.Action.Builder(
                    null, "关灯", action(CMD_OFF, 11)).build());
            b.addAction(new Notification.Action.Builder(
                    null, "切灯路", action(CMD_CYCLE_LED, 12)).build());
            b.addAction(new Notification.Action.Builder(
                    null, "调亮度", action(CMD_CYCLE_BRIGHT, 13)).build());
        } else {
            b.addAction(new Notification.Action.Builder(
                    null, "开双灯", action(CMD_DUAL, 14)).build());
        }
        return b.build();
    }

    private PendingIntent action(String cmd, int code) {
        Intent i = new Intent(this, ShortcutActivity.class)
                .putExtra(EXTRA_CMD, cmd)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "手电状态", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("手电开启时显示，可直接关灯 / 切灯路 / 调亮度");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        queue.clear();
        controller.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
