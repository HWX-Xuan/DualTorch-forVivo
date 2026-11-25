package com.dualtorch.app.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 偏好与状态。
 *
 * 这里存的是「快捷入口」和「App 界面」共享的唯一状态：
 * 灯路、亮度、是否点亮、冷启动是否自动开灯。
 * 磁贴 / 快捷方式 / 桌面图标都读同一份，避免两边打架。
 */
public final class Prefs {

    /** 只亮上面那颗（camera 0 的 setTorchMode，实测可用） */
    public static final int LED_TOP = 0;
    /** 双灯（相机会话，HAL 按焦段点两颗） */
    public static final int LED_BOTH = 1;
    /** 只亮长焦那颗（物理子相机会话，实测不可用，保留枚举） */
    public static final int LED_BOTTOM = 2;

    /**
     * 主界面只有两档，因为实测这台机器的焦段不影响灯数：
     * 广角 = 系统手电，只点上面那颗；双灯 = 相机会话，两颗一起，2× 与 3.5× 光斑一样。
     */
    public static final int FOCAL_WIDE = 0;
    public static final int FOCAL_DUAL = 1;

    private static final String NAME = "dualtorch";

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------------- 自检选定的手电相机 ----------------

    public static String torchId(Context ctx) {
        return sp(ctx).getString("torch_id", null);
    }

    public static void setTorchId(Context ctx, String id) {
        sp(ctx).edit().putString("torch_id", id).apply();
    }

    public static void clearTorchId(Context ctx) {
        sp(ctx).edit().remove("torch_id").apply();
    }

    // ---------------- 焦段 ----------------

    public static int focal(Context ctx) {
        return sp(ctx).getInt("focal", FOCAL_WIDE) > 0 ? FOCAL_DUAL : FOCAL_WIDE;
    }

    public static void setFocal(Context ctx, int v) {
        sp(ctx).edit().putInt("focal", v > 0 ? FOCAL_DUAL : FOCAL_WIDE).apply();
    }

    public static String focalName(int f) {
        return f > 0 ? "双灯" : "广角";
    }

    public static String focalSub(int f) {
        return f > 0 ? "满功率 · 更亮" : "单灯 · 可调光";
    }

    public static String focalDesc(int f) {
        if (f > 0) {
            return "相机会话点亮两颗，HAL 固定满功率。亮度不可调，但明显更亮、更远。";
        }
        return "系统手电只点上面那颗广角灯，最省电最凉，亮度可以调。";
    }

    // ---------------- 灯路 ----------------

    public static int ledSet(Context ctx) {
        return sp(ctx).getInt("led_set", LED_TOP);
    }

    public static void setLedSet(Context ctx, int v) {
        sp(ctx).edit().putInt("led_set", v).apply();
    }

    public static String ledName(int set) {
        switch (set) {
            case LED_BOTH:
                return "双灯";
            case LED_BOTTOM:
                return "下灯";
            default:
                return "上灯";
        }
    }

    // ---------------- 亮度（档位 = 硬件档位，0 = 关） ----------------

    /** 0 表示关灯；1..hwMax 直接就是硬件的第几档。 */
    public static final int LEVEL_OFF = 0;

    private static final String KEY_HW_MAX = "hw_max";
    private static final String[] CN = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /**
     * 这台机器的硬件档位上限（缓存）。
     *
     * 控制器每次初始化都会用 CameraCharacteristics 的真实值刷新它，
     * 界面据此生成同样多的档位 —— 硬件有几档，滑条就有几档。
     */
    public static int hwMax(Context ctx) {
        return Math.max(1, sp(ctx).getInt(KEY_HW_MAX, 4));
    }

    public static void setHwMax(Context ctx, int max) {
        if (max >= 1) {
            sp(ctx).edit().putInt(KEY_HW_MAX, max).apply();
        }
    }

    /** 0 = 关闭，1..hwMax = 硬件档位。默认停在最亮。 */
    public static int levelIndex(Context ctx) {
        SharedPreferences p = sp(ctx);
        int max = hwMax(ctx);
        if (!p.contains("level_index")) {
            return max;
        }
        int v = p.getInt("level_index", max);
        if (!p.getBoolean("level_model_v2", false)) {
            // 旧版只有 0/1/2/3 四个位置，3 代表「最亮」，迁移到新的档位编号
            v = v <= 0 ? LEVEL_OFF : v >= 3 ? max : Math.min(v, max);
            p.edit().putInt("level_index", v).putBoolean("level_model_v2", true).apply();
        }
        return Math.max(LEVEL_OFF, Math.min(max, v));
    }

    public static void setLevelIndex(Context ctx, int v) {
        int max = hwMax(ctx);
        sp(ctx).edit().putInt("level_index", Math.max(LEVEL_OFF, Math.min(max, v)))
                .putBoolean("level_model_v2", true).apply();
    }

    /** 完整名称：关闭 / 一档 / 二档 / … / 最亮。 */
    public static String levelName(int idx, int max) {
        max = Math.max(1, max);
        if (idx <= LEVEL_OFF) {
            return "关闭";
        }
        if (idx >= max) {
            return "最亮";
        }
        return cn(idx) + "档";
    }

    /** 刻度用的短名称。 */
    public static String levelTick(int idx, int max) {
        max = Math.max(1, max);
        if (idx <= LEVEL_OFF) {
            return "关";
        }
        if (idx >= max) {
            return "最亮";
        }
        return cn(idx) + "档";
    }

    private static String cn(int i) {
        return i > 0 && i < CN.length ? CN[i] : String.valueOf(i);
    }

    /** 磁贴循环用：最亮 → 次亮 → … → 一档 → 最亮（跳过关闭）。 */
    public static int nextLevel(Context ctx, int cur) {
        int max = hwMax(ctx);
        if (cur >= max) {
            return Math.max(1, max - 1);
        }
        return cur <= 1 ? max : cur - 1;
    }

    // ---------------- 开关状态 ----------------

    public static boolean lit(Context ctx) {
        return sp(ctx).getBoolean("lit", false);
    }

    public static void setLit(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean("lit", v).apply();
    }

    // ---------------- 冷启动行为 ----------------

    public static boolean autoOnLaunch(Context ctx) {
        return sp(ctx).getBoolean("auto_on_launch", true);
    }

    public static void setAutoOnLaunch(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean("auto_on_launch", v).apply();
    }

    /** 状态一句话描述，磁贴与通知共用。 */
    public static String summary(Context ctx) {
        if (!lit(ctx)) {
            return "关闭";
        }
        int f = focal(ctx);
        int lv = levelIndex(ctx);
        if (f > 0) {
            return "双灯 · 满功率";
        }
        return lv == LEVEL_OFF ? "广角 · 关闭" : "广角 · " + levelName(lv, hwMax(ctx));
    }
}
