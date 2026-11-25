package com.dualtorch.app.core;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 相机 / 闪光灯能力枚举。
 *
 * 这里刻意把「出现在 getCameraIdList 里的相机」「逻辑相机的物理子相机」
 * 「根本没列出来但可能接受 setTorchMode 的编号」全部当作候选，
 * 因为 vivo 是否把长焦相机注册成独立闪光灯单元，只有逐个试才知道。
 */
public final class FlashProbe {

    public static class Cam {
        public final String id;
        public boolean listed;
        public boolean physical;
        public boolean flash;
        public int facing = -1;
        public String focal = "—";
        public int infoMax = 1;
        public int torchMax = 1;
        public int singleMax = 1;
        public String error;

        Cam(String id) {
            this.id = id;
        }

        public String focalText() {
            return focal;
        }

        public String label() {
            String pos;
            switch (facing) {
                case CameraCharacteristics.LENS_FACING_BACK:
                    pos = "后置";
                    break;
                case CameraCharacteristics.LENS_FACING_FRONT:
                    pos = "前置";
                    break;
                default:
                    pos = "未知";
                    break;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("camera ").append(id).append(" · ").append(pos);
            if (!"—".equals(focal)) {
                sb.append(" · ").append(focal);
            }
            if (physical) {
                sb.append(" · 物理子相机");
            } else if (!listed) {
                sb.append(" · 未在列表");
            }
            return sb.toString();
        }

        public String caps() {
            if (error != null) {
                return "读取失败：" + error;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(flash ? "有闪光灯" : "无闪光灯");
            if (flash) {
                sb.append(" · 强度档 info=").append(infoMax)
                        .append(" torch=").append(torchMax)
                        .append(" single=").append(singleMax);
            }
            return sb.toString();
        }
    }

    private FlashProbe() {
    }

    public static CameraManager manager(Context ctx) {
        return (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    public static List<Cam> scan(Context ctx) {
        CameraManager cm = manager(ctx);
        List<Cam> listed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (String id : cm.getCameraIdList()) {
                Cam c = describe(cm, id);
                c.listed = true;
                listed.add(c);
                seen.add(id);
            }
        } catch (Throwable ignored) {
        }

        List<Cam> physical = new ArrayList<>();
        for (Cam c : listed) {
            try {
                CameraCharacteristics ch = cm.getCameraCharacteristics(c.id);
                for (String p : ch.getPhysicalCameraIds()) {
                    if (seen.add(p)) {
                        Cam pc = describe(cm, p);
                        pc.physical = true;
                        physical.add(pc);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        List<Cam> hidden = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = String.valueOf(i);
            if (seen.add(id)) {
                hidden.add(describe(cm, id));
            }
        }

        List<Cam> out = new ArrayList<>();
        // 有闪光灯的后置优先，其次其它已列出相机，再次物理子相机，最后盲试编号
        for (Cam c : listed) {
            if (c.flash && c.facing == CameraCharacteristics.LENS_FACING_BACK) {
                out.add(c);
            }
        }
        for (Cam c : listed) {
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        out.addAll(physical);
        out.addAll(hidden);
        return out;
    }

    private static Cam describe(CameraManager cm, String id) {
        Cam c = new Cam(id);
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            c.flash = Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            c.facing = facing == null ? -1 : facing;
            float[] f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (f != null && f.length > 0) {
                c.focal = String.format(Locale.US, "%.1fmm", f[0]);
            }
            if (c.flash) {
                c.infoMax = intOf(ch, CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL, 33);
                c.torchMax = intOf(ch, CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL, 35);
                c.singleMax = intOf(ch, CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL, 35);
            }
        } catch (Throwable t) {
            c.error = t.getClass().getSimpleName();
        }
        return c;
    }

    private static int intOf(CameraCharacteristics ch, CameraCharacteristics.Key<Integer> key, int api) {
        if (Build.VERSION.SDK_INT < api) {
            return 1;
        }
        try {
            Integer v = ch.get(key);
            return v == null ? 1 : Math.max(1, v);
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 返回 null 表示成功，否则是失败原因。 */
    public static String setTorch(CameraManager cm, String id, boolean on) {
        try {
            cm.setTorchMode(id, on);
            return null;
        } catch (CameraAccessException e) {
            return "CameraAccess(" + e.getReason() + ")";
        } catch (Throwable t) {
            String m = t.getMessage();
            return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        }
    }

    /** 打开手电并指定档位；返回 null 表示成功。 */
    public static String setTorchLevel(CameraManager cm, String id, int level) {
        try {
            cm.turnOnTorchWithStrengthLevel(id, level);
            return null;
        } catch (CameraAccessException e) {
            return "CameraAccess(" + e.getReason() + ")";
        } catch (Throwable t) {
            String m = t.getMessage();
            return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        }
    }

    /** 回读当前档位；-1 表示不支持或未开灯。 */
    public static int readLevel(CameraManager cm, String id) {
        if (Build.VERSION.SDK_INT < 33) {
            return -1;
        }
        try {
            return cm.getTorchStrengthLevel(id);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static List<String> flashIds(Context ctx) {
        List<String> out = new ArrayList<>();
        for (Cam c : scan(ctx)) {
            if (c.flash && c.listed) {
                out.add(c.id);
            }
        }
        return out;
    }

    /**
     * 实测这颗相机真正接受的最高档位。
     *
     * 厂商上报的 FLASH_TORCH_STRENGTH_MAX_LEVEL 经常和 HAL 实际校验用的值不一致
     * （X300 Pro 上报 5，传 5 直接抛 "Torch strength level 5 is not within the valid range"）。
     * 所以这里从声明值往下逐档试，返回第一个被接受的档位：
     *   1..N  → 真实可用上限
     *   0     → 连 1 档都被拒绝（接口形同虚设）
     */
    public static int probeMaxLevel(CameraManager cm, String id, int declared) {
        int start = Math.max(1, Math.min(declared, 20));
        for (int lv = start; lv >= 1; lv--) {
            String e = setTorchLevel(cm, id, lv);
            if (e == null) {
                return lv;
            }
            if (!isRangeError(e)) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean isRangeError(String e) {
        return e != null && (e.contains("not within the valid range")
                || e.contains("out of range") || e.contains("invalid strength"));
    }

    /** 把与闪光灯/灯珠相关的所有 key 打出来，包括厂商私有 tag。 */
    public static String dumpFlashKeys(CameraManager cm, String id) {
        StringBuilder sb = new StringBuilder();
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            sb.append("--- camera ").append(id).append(" 闪光相关 key ---\n");
            int n = 0;
            for (CameraCharacteristics.Key<?> k : ch.getKeys()) {
                String name = k.getName();
                if (!interesting(name)) {
                    continue;
                }
                Object v;
                try {
                    v = ch.get(k);
                } catch (Throwable t) {
                    v = "<读不出>";
                }
                sb.append("  ").append(name).append(" = ").append(fmt(v)).append('\n');
                n++;
            }
            if (n == 0) {
                sb.append("  （无）\n");
            }
            int m = 0;
            List<CaptureRequest.Key<?>> req = ch.getAvailableCaptureRequestKeys();
            StringBuilder reqSb = new StringBuilder();
            if (req != null) {
                for (CaptureRequest.Key<?> k : req) {
                    String name = k.getName();
                    if (!interesting(name)) {
                        continue;
                    }
                    reqSb.append("  ").append(name).append('\n');
                    m++;
                }
            }
            if (m > 0) {
                sb.append("  可注入的请求 key：\n").append(reqSb);
            }
            Set<String> phys = ch.getPhysicalCameraIds();
            if (phys != null && !phys.isEmpty()) {
                sb.append("  物理子相机 = ").append(phys).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  读取失败：").append(t).append('\n');
        }
        return sb.toString();
    }

    private static boolean interesting(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        return n.contains("flash") || n.contains("torch") || n.contains("led")
                || n.contains("strobe") || n.contains("light") || n.contains("vivo");
    }

    private static String fmt(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof int[]) {
            return Arrays.toString((int[]) v);
        }
        if (v instanceof float[]) {
            return Arrays.toString((float[]) v);
        }
        if (v instanceof byte[]) {
            return Arrays.toString((byte[]) v);
        }
        if (v instanceof Object[]) {
            return Arrays.deepToString((Object[]) v);
        }
        return String.valueOf(v);
    }

    public static String platformTag() {
        return Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.DEVICE
                + " / API " + Build.VERSION.SDK_INT;
    }

    public static List<String> idsOf(List<Cam> cams) {
        List<String> out = new ArrayList<>();
        for (Cam c : cams) {
            out.add(c.id);
        }
        return out;
    }

    public static String join(List<String> v) {
        return Arrays.toString(v.toArray());
    }
}
