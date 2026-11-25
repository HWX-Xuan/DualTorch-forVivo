package com.dualtorch.app.core;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把设备上和"闪光灯 / 多摄 / 厂商私有参数"有关的信息全部打印出来，
 * 用于判断这台机器到底给第三方开放了哪条通路。
 */
public final class CameraProbe {

    private static final String[] INTEREST = {
            "flash", "torch", "led", "light", "dual", "zoom", "beauty", "vivo", "qti", "mtk"
    };

    private CameraProbe() {
    }

    public static String dump(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 设备 ===\n");
        sb.append("厂商   : ").append(Build.MANUFACTURER).append('\n');
        sb.append("型号   : ").append(Build.MODEL).append('\n');
        sb.append("设备   : ").append(Build.DEVICE).append('\n');
        sb.append("系统   : Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("指纹   : ").append(Build.FINGERPRINT).append('\n');
        sb.append("Root   : ").append(RootEngine.hasRoot() ? "有" : "无").append("\n\n");

        SystemTorchEngine engine;
        try {
            engine = new SystemTorchEngine(ctx);
        } catch (Throwable t) {
            sb.append("读取相机列表失败: ").append(t).append('\n');
            return sb.toString();
        }

        sb.append("=== 摄像头清单 ===\n");
        for (Map.Entry<String, CameraCharacteristics> e : engine.allCameras().entrySet()) {
            sb.append(describe(e.getKey(), e.getValue(), engine)).append('\n');
        }

        sb.append("\n=== 通道推断 ===\n");
        String[] ch = resolveChannels(engine);
        sb.append("通道 A : camera ").append(ch[0]).append('\n');
        sb.append("通道 B : ").append(ch[1] == null ? "无独立闪光灯相机" : "camera " + ch[1]).append('\n');
        if (ch[1] == null) {
            sb.append("说明   : 系统只给一颗相机上报了闪光灯，第二颗灯只能靠相机会话\n")
                    .append("         或厂商参数触发（见下方 Vendor 键）。\n");
        }

        sb.append("\n=== 厂商私有参数键（含 flash/torch/led/zoom 等） ===\n");
        boolean any = false;
        for (Map.Entry<String, CameraCharacteristics> e : engine.allCameras().entrySet()) {
            List<String> keys = vendorKeys(e.getValue());
            if (keys.isEmpty()) {
                continue;
            }
            any = true;
            sb.append("[camera ").append(e.getKey()).append("]\n");
            for (String k : keys) {
                sb.append("  ").append(k).append('\n');
            }
        }
        if (!any) {
            sb.append("未发现（厂商把私有 tag 对第三方屏蔽了）\n");
        }

        sb.append("\n=== LED sysfs 节点 ===\n");
        List<RootEngine.Node> nodes = RootEngine.scan();
        if (nodes.isEmpty()) {
            sb.append(RootEngine.hasRoot() ? "无可用节点\n" : "无 root，无法读取\n");
        } else {
            for (RootEngine.Node n : nodes) {
                sb.append("  ").append(n.path).append("  max=").append(n.max).append('\n');
            }
        }
        return sb.toString();
    }

    private static String describe(String id, CameraCharacteristics c, SystemTorchEngine engine) {
        StringBuilder sb = new StringBuilder();
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        sb.append("camera ").append(id)
                .append("  ").append(facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "后置" : "前置/其他")
                .append("  flash=").append(Boolean.TRUE.equals(flash) ? "YES" : "no");
        if (Boolean.TRUE.equals(flash)) {
            sb.append("  torchMax=").append(engine.maxStrength(id))
                    .append("  defLevel=").append(engine.defaultStrength(id));
            try {
                if (Build.VERSION.SDK_INT >= 35) {
                    Integer sm = c.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL);
                    Integer tm = c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL);
                    sb.append("\n       singleMax=").append(sm).append("  torchMax35=").append(tm);
                }
            } catch (Throwable ignored) {
            }
        }
        sb.append('\n');

        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focals != null && phys != null && focals.length > 0) {
            double diag = Math.sqrt(phys.getWidth() * phys.getWidth() + phys.getHeight() * phys.getHeight());
            double eq = focals[0] * (43.2666 / diag);
            sb.append(String.format(Locale.US, "       等效焦距 ≈ %.0fmm   物理 %.2fmm\n", eq, focals[0]));
        }
        android.util.Range<Float> zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zoom != null) {
            sb.append(String.format(Locale.US, "       zoomRange %.1f - %.1f\n", zoom.getLower(), zoom.getUpper()));
        }
        java.util.Set<String> physIds = c.getPhysicalCameraIds();
        if (physIds != null && !physIds.isEmpty()) {
            sb.append("       物理子相机 ").append(physIds).append('\n');
        }
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
            if (sizes != null && sizes.length > 0) {
                sb.append("       最大预览 ").append(sizes[0].getWidth()).append('x').append(sizes[0].getHeight()).append('\n');
            }
        }
        return sb.toString();
    }

    public static List<String> vendorKeys(CameraCharacteristics c) {
        List<String> out = new ArrayList<>();
        try {
            List<CaptureRequest.Key<?>> keys = c.getAvailableCaptureRequestKeys();
            if (keys == null) {
                return out;
            }
            for (CaptureRequest.Key<?> k : keys) {
                String n = k.getName();
                String lower = n.toLowerCase(Locale.US);
                boolean standard = n.startsWith("android.");
                boolean hit = false;
                for (String s : INTEREST) {
                    if (lower.contains(s)) {
                        hit = true;
                        break;
                    }
                }
                if (!standard && (hit || lower.startsWith("com.") || lower.startsWith("org."))) {
                    out.add(n);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 通道 A = 焦距最短的闪光灯相机；通道 B = 焦距最长的闪光灯相机。 */
    public static String[] resolveChannels(SystemTorchEngine engine) {
        List<String> flash = engine.flashCameras();
        String a = flash.isEmpty() ? "0" : flash.get(0);
        String b = null;
        if (flash.size() >= 2) {
            b = flash.get(flash.size() - 1);
        }
        return new String[]{a, b};
    }

    /** 找出最长的后置相机（长焦），用于相机会话模式的备选。 */
    public static String longestBackCamera(SystemTorchEngine engine) {
        String best = null;
        float bestF = -1;
        for (Map.Entry<String, CameraCharacteristics> e : engine.allCameras().entrySet()) {
            Integer facing = e.getValue().get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                continue;
            }
            float[] f = e.getValue().get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (f == null || f.length == 0) {
                continue;
            }
            if (f[0] > bestF) {
                bestF = f[0];
                best = e.getKey();
            }
        }
        return best;
    }
}
