package com.dualtorch.app.core;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 相机会话通道。
 *
 * 系统手电筒走的是 CameraService 的 setTorchMode 捷径，HAL 往往把它固定映射到
 * 主闪光通道；相机拍照走的是完整 capture 请求链路，HAL 的闪光算法会读取
 * CONTROL_AE_MODE / FLASH_MODE / CONTROL_ZOOM_RATIO / FLASH_STRENGTH_LEVEL，
 * 再决定点亮哪几路 LED、给多大电流。
 *
 * Android 15（API 35）起 CaptureRequest 多了 FLASH_STRENGTH_LEVEL，
 * 可以在预览流里直接指定硬件亮度档位，不需要用频闪去模拟。
 */
public class CameraSessionEngine {

    public static final int STYLE_TORCH = 0;   // AE=ON + FLASH_MODE_TORCH
    public static final int STYLE_ALWAYS = 1;  // AE=ON_ALWAYS_FLASH
    public static final int STYLE_BOTH = 2;    // AE=ON_ALWAYS_FLASH + FLASH_MODE_TORCH

    public interface Listener {
        void onOpened(String cameraId, float zoom);

        void onClosed();

        void onFail(String reason);
    }

    public static class Config {
        public float zoom = 1f;
        public int style = STYLE_TORCH;
        /** 0 = 用 HAL 默认档位；1–100 = 百分比，映射到硬件档位 */
        public int strengthPercent = 0;
        public Map<String, String> vendor = new LinkedHashMap<>();
    }

    private final Context ctx;
    private final HandlerThread thread;
    private final Handler handler;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private CameraCharacteristics chars;
    private volatile String runningId;
    private volatile float runningZoom = 1f;
    private volatile int runningPercent = 0;
    /** HAL 拒绝 FLASH_STRENGTH_LEVEL 时置位，调度层据此改用占空比调光。 */
    private volatile boolean strengthRejected;
    private int runningStyle = STYLE_TORCH;
    private Map<String, String> vendor = new LinkedHashMap<>();
    private Listener listener;

    public CameraSessionEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        thread = new HandlerThread("dual-torch-cam");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public boolean isRunning() {
        return runningId != null;
    }

    /** 本次会话是否已确认不支持 FLASH_STRENGTH_LEVEL。 */
    public boolean strengthRejected() {
        return strengthRejected;
    }

    public String runningId() {
        return runningId;
    }

    public void start(String cameraId, Config cfg, Listener l) {
        listener = l;
        handler.post(() -> {
            teardown();
            if (cameraId == null) {
                fail("没有可用的 cameraId");
                return;
            }
            runningStyle = cfg.style;
            strengthRejected = false;
            runningPercent = cfg.strengthPercent;
            vendor = new LinkedHashMap<>(cfg.vendor);
            runningZoom = cfg.zoom <= 0 ? 1f : cfg.zoom;
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                chars = cm.getCameraCharacteristics(cameraId);
                int[] size = pickSize(chars);
                reader = ImageReader.newInstance(size[0], size[1], ImageFormat.YUV_420_888, 3);
                reader.setOnImageAvailableListener(r -> {
                    try {
                        Image img = r.acquireLatestImage();
                        if (img != null) {
                            img.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }, handler);
                LogBus.i("打开相机 " + cameraId + "，zoom=" + runningZoom
                        + "，style=" + styleName(runningStyle)
                        + "，强度=" + runningPercent + "%（档位上限 " + maxStrength(chars, runningStyle) + "）");
                cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        device = camera;
                        createSession();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        LogBus.e("相机 " + cameraId + " 被抢占（onDisconnected）");
                        teardown();
                        fail("相机被其他应用占用");
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        LogBus.e("相机 " + cameraId + " 错误码 " + error);
                        teardown();
                        fail("相机打开失败，错误码 " + error);
                    }
                }, handler);
            } catch (CameraAccessException e) {
                fail("openCamera 异常: " + e.getReason());
            } catch (SecurityException e) {
                fail("缺少相机权限");
            } catch (Throwable t) {
                fail(t.getClass().getSimpleName() + " " + t.getMessage());
            }
        });
    }

    /** 选一个 HAL 确实支持的 YUV 尺寸，避免 newInstance 直接抛异常。 */
    private static int[] pickSize(CameraCharacteristics ch) {
        try {
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes != null && sizes.length > 0) {
                    java.util.Arrays.sort(sizes, (x, y) ->
                            Integer.compare(x.getWidth() * x.getHeight(), y.getWidth() * y.getHeight()));
                    Size chosen = sizes[0];
                    for (Size s : sizes) {
                        if (s.getWidth() >= 640 && s.getHeight() >= 480) {
                            chosen = s;
                            break;
                        }
                    }
                    return new int[]{chosen.getWidth(), chosen.getHeight()};
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{640, 480};
    }

    private void createSession() {
        try {
            final Surface surface = reader.getSurface();
            device.createCaptureSession(java.util.Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                runningId = device.getId();
                                builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(surface);
                                applyTo(builder, true);
                                session.setRepeatingRequest(builder.build(), null, handler);
                                LogBus.ok("相机会话已建立，请求已下发（" + styleName(runningStyle) + "）");
                                if (listener != null) {
                                    listener.onOpened(runningId, runningZoom);
                                }
                            } catch (Throwable t) {
                                fail("下发请求失败: " + t.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            fail("createCaptureSession 配置失败");
                        }
                    }, handler);
        } catch (Throwable t) {
            fail("createCaptureSession 异常: " + t.getMessage());
        }
    }

    /** 运行时同时更新焦段与亮度，不重建会话。 */
    public void update(float zoom, int strengthPercent) {
        runningZoom = zoom;
        runningPercent = strengthPercent;
        handler.post(() -> {
            if (session == null || builder == null) {
                return;
            }
            try {
                applyTo(builder, true);
                session.setRepeatingRequest(builder.build(), null, handler);
                LogBus.i("会话参数更新 → " + String.format(java.util.Locale.US, "%.2f", zoom)
                        + "× / " + strengthPercent + "%");
            } catch (Throwable t) {
                LogBus.e("更新参数失败: " + t.getMessage());
            }
        });
    }

    /** 兼容旧调用 */
    public void setZoom(float zoom) {
        update(zoom, runningPercent);
    }

    /** 频闪：在同一会话内切换 FLASH_MODE，比反复开关相机会话快得多。 */
    public void setFlashOn(boolean on) {
        handler.post(() -> {
            if (session == null || builder == null) {
                return;
            }
            try {
                applyTo(builder, on);
                session.setRepeatingRequest(builder.build(), null, handler);
            } catch (Throwable ignored) {
            }
        });
    }

    private void applyTo(CaptureRequest.Builder b, boolean flashOn) {
        int aeMode;
        int flashMode;
        if (!flashOn) {
            aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
            flashMode = CaptureRequest.FLASH_MODE_OFF;
        } else if (runningStyle == STYLE_ALWAYS) {
            aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            flashMode = CaptureRequest.FLASH_MODE_OFF;
        } else if (runningStyle == STYLE_BOTH) {
            aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            flashMode = CaptureRequest.FLASH_MODE_TORCH;
        } else {
            aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
            flashMode = CaptureRequest.FLASH_MODE_TORCH;
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
        b.set(CaptureRequest.FLASH_MODE, flashMode);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

        applyStrength(b, flashOn);
        applyZoom(b);
        applyVendor(b);
    }

    /**
     * 硬件亮度档位。API 35 的 FLASH_STRENGTH_LEVEL 让预览流里的手电筒也能调电流，
     * 比 PWM 频闪干净得多（无可见闪烁、无卷帘条纹）。
     */
    private void applyStrength(CaptureRequest.Builder b, boolean flashOn) {
        if (!flashOn || Build.VERSION.SDK_INT < 35 || runningPercent <= 0) {
            return;
        }
        int max = maxStrength(chars, runningStyle);
        if (max <= 1) {
            return;
        }
        int level = SystemTorchEngine.level(runningPercent, max);
        try {
            b.set(CaptureRequest.FLASH_STRENGTH_LEVEL, level);
        } catch (Throwable t) {
            strengthRejected = true;
            LogBus.e("该机不支持会话内调光（FLASH_STRENGTH_LEVEL）");
        }
    }

    /** 手电筒模式用 TORCH 档位表，单次闪光模式用 SINGLE 档位表。 */
    public static int maxStrength(CameraCharacteristics ch, int style) {
        if (ch == null) {
            return 1;
        }
        boolean single = style == STYLE_ALWAYS;
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                Integer v = single
                        ? ch.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL)
                        : ch.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL);
                if (v != null && v > 1) {
                    // 手电档位表同样要削掉 HAL 不认的最高档；单次闪光表保持原样
                    return single ? v : SystemTorchEngine.torchMax(v);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Integer v = ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                if (v != null && v > 1) {
                    return single ? v : SystemTorchEngine.torchMax(v);
                }
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private void applyZoom(CaptureRequest.Builder b) {
        float z = Math.max(1f, runningZoom);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
                return;
            } catch (Throwable ignored) {
                // 设备不支持 zoom ratio，退回裁剪区域
            }
        }
        try {
            android.graphics.Rect active = chars == null ? null
                    : chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active == null) {
                return;
            }
            int w = (int) (active.width() / z);
            int h = (int) (active.height() / z);
            int cx = active.centerX();
            int cy = active.centerY();
            b.set(CaptureRequest.SCALER_CROP_REGION,
                    new android.graphics.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2));
        } catch (Throwable ignored) {
        }
    }

    /** 把用户指定的厂商私有 key 塞进请求里。 */
    private void applyVendor(CaptureRequest.Builder b) {
        if (vendor.isEmpty() || chars == null) {
            return;
        }
        try {
            java.util.List<CaptureRequest.Key<?>> keys = chars.getAvailableCaptureRequestKeys();
            for (Map.Entry<String, String> e : vendor.entrySet()) {
                CaptureRequest.Key<?> key = null;
                for (CaptureRequest.Key<?> k : keys) {
                    if (k.getName().equals(e.getKey())) {
                        key = k;
                        break;
                    }
                }
                if (key == null) {
                    LogBus.e("厂商参数不存在: " + e.getKey());
                    continue;
                }
                if (setTyped(b, key, e.getValue())) {
                    LogBus.ok("注入 " + e.getKey() + " = " + e.getValue());
                } else {
                    LogBus.e("注入失败（类型不匹配）: " + e.getKey() + " = " + e.getValue());
                }
            }
        } catch (Throwable t) {
            LogBus.e("注入厂商参数异常: " + t.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean setTyped(CaptureRequest.Builder b, CaptureRequest.Key<?> key, String raw) {
        Object[] candidates = new Object[]{
                parseInt(raw), parseLong(raw), parseFloat(raw), parseDouble(raw),
                parseByte(raw), new Rational(parseIntSafe(raw, 1), 1)
        };
        for (Object v : candidates) {
            if (v == null) {
                continue;
            }
            try {
                ((CaptureRequest.Builder) b).set((CaptureRequest.Key) key, v);
                return true;
            } catch (IllegalArgumentException ignored) {
                // 类型不符，换下一个
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Float parseFloat(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Byte parseByte(String s) {
        try {
            return Byte.parseByte(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseIntSafe(String s, int def) {
        Integer v = parseInt(s);
        return v == null ? def : v;
    }

    public static String styleName(int s) {
        switch (s) {
            case STYLE_ALWAYS:
                return "ALWAYS_FLASH";
            case STYLE_BOTH:
                return "TORCH+AE";
            default:
                return "TORCH";
        }
    }

    public void stop() {
        handler.post(this::teardown);
    }

    private void teardown() {
        boolean wasRunning = runningId != null;
        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable ignored) {
        }
        session = null;
        try {
            if (device != null) {
                device.close();
            }
        } catch (Throwable ignored) {
        }
        device = null;
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Throwable ignored) {
        }
        reader = null;
        builder = null;
        runningId = null;
        if (wasRunning) {
            LogBus.i("相机会话已关闭");
            if (listener != null) {
                listener.onClosed();
            }
        }
    }

    private void fail(String reason) {
        LogBus.e(reason);
        if (listener != null) {
            listener.onFail(reason);
        }
    }

    public void release() {
        handler.post(() -> {
            teardown();
            thread.quitSafely();
        });
    }
}
