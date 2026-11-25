package com.dualtorch.app.core;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import java.util.Collections;
import java.util.Set;

/**
 * 两条「应用层最后还能试」的通路。
 *
 * 1) 物理子相机会话
 *    X300 Pro 上 camera 5（22.5mm 长焦）是 camera 0 的物理子相机，
 *    它不接受 setTorchMode（invalid cameraId），但 Logical Multi-Camera
 *    允许把 output 绑定到 physicalCameraId 上再开会话。如果 HAL 是按
 *    「这个流属于哪颗物理相机」去选灯，那这里就能单独点亮长焦灯。
 *
 * 2) 单次闪光
 *    FLASH_MODE_SINGLE 走的是拍照链路，vivo 的闪光算法会按焦段点灯：
 *    3× 以上应该是两颗一起闪。用它来确认「长焦灯确实存在，只是不能常亮」。
 */
public class PhysicalTorchEngine {

    public interface Cb {
        void log(String s);
    }

    private final Context ctx;
    private final HandlerThread thread;
    private final Handler handler;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private boolean closed = true;

    public PhysicalTorchEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        thread = new HandlerThread("dual-torch-phys");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    // ---------------- 物理子相机点灯 ----------------

    public void openPhysical(final String logicalId, final String physicalId,
                             final float zoom, final Cb cb) {
        handler.post(() -> {
            closeInternal();
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics ch = cm.getCameraCharacteristics(logicalId);
                Set<String> phys = ch.getPhysicalCameraIds();
                if (phys == null || !phys.contains(physicalId)) {
                    log(cb, "camera " + physicalId + " 不是 camera " + logicalId
                            + " 的物理子相机（实际：" + phys + "），仍继续尝试");
                }
                int[] size = pickSize(ch);
                reader = ImageReader.newInstance(size[0], size[1], ImageFormat.YUV_420_888, 2);
                reader.setOnImageAvailableListener(r -> {
                    try {
                        Image img = r.acquireLatestImage();
                        if (img != null) {
                            img.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }, handler);
                final Surface surface = reader.getSurface();

                OutputConfiguration oc = new OutputConfiguration(surface);
                oc.setPhysicalCameraId(physicalId);
                log(cb, "打开 camera " + logicalId + "，output 绑定物理相机 " + physicalId
                        + "，尺寸 " + size[0] + "x" + size[1]);

                cm.openCamera(logicalId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        device = camera;
                        try {
                            device.createCaptureSessionByOutputConfigurations(
                                    Collections.singletonList(oc),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(CameraCaptureSession s) {
                                            session = s;
                                            try {
                                                builder = device.createCaptureRequest(
                                                        CameraDevice.TEMPLATE_PREVIEW);
                                                builder.addTarget(surface);
                                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                                builder.set(CaptureRequest.FLASH_MODE,
                                                        CaptureRequest.FLASH_MODE_TORCH);
                                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                                        CaptureRequest.CONTROL_AF_MODE_OFF);
                                                if (zoom > 1.01f) {
                                                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                                                }
                                                session.setRepeatingRequest(builder.build(),
                                                        null, handler);
                                                closed = false;
                                                log(cb, "会话已建立，已下发 FLASH_MODE_TORCH —— 看灯");
                                            } catch (Throwable t) {
                                                log(cb, "下发请求失败：" + t);
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(CameraCaptureSession s) {
                                            log(cb, "createCaptureSessionByOutputConfigurations 失败"
                                                    + "（HAL 不接受物理流的配置）");
                                        }
                                    }, handler);
                        } catch (Throwable t) {
                            log(cb, "建会话异常：" + t);
                        }
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        log(cb, "相机被抢占（onDisconnected）");
                        closeInternal();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        log(cb, "相机错误码 " + error);
                        closeInternal();
                    }
                }, handler);
            } catch (CameraAccessException e) {
                log(cb, "openCamera 异常：reason=" + e.getReason());
            } catch (Throwable t) {
                log(cb, "异常：" + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        });
    }

    public void close(final Cb cb) {
        handler.post(() -> {
            closeInternal();
            log(cb, "已关闭");
        });
    }

    // ---------------- 单次闪光 ----------------

    public void flashOnce(final String cameraId, final float zoom, final Cb cb) {
        handler.post(() -> {
            closeInternal();
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics ch = cm.getCameraCharacteristics(cameraId);
                int[] size = pickSize(ch);
                reader = ImageReader.newInstance(size[0], size[1], ImageFormat.YUV_420_888, 2);
                reader.setOnImageAvailableListener(r -> {
                    try {
                        Image img = r.acquireLatestImage();
                        if (img != null) {
                            img.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }, handler);
                final Surface surface = reader.getSurface();
                log(cb, "打开 camera " + cameraId + "，准备在 " + zoom + "× 触发一次闪光");

                cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        device = camera;
                        try {
                            device.createCaptureSession(Collections.singletonList(surface),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(CameraCaptureSession s) {
                                            session = s;
                                            closed = false;
                                            handler.postDelayed(() -> fire(cb, surface, zoom), 900);
                                        }

                                        @Override
                                        public void onConfigureFailed(CameraCaptureSession s) {
                                            log(cb, "会话配置失败");
                                        }
                                    }, handler);
                        } catch (Throwable t) {
                            log(cb, "建会话异常：" + t);
                        }
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        log(cb, "相机被抢占");
                        closeInternal();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        log(cb, "相机错误码 " + error);
                        closeInternal();
                    }
                }, handler);
            } catch (Throwable t) {
                log(cb, "异常：" + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        });
    }

    private void fire(final Cb cb, Surface surface, float zoom) {
        if (session == null || device == null) {
            log(cb, "会话已失效，闪光未触发");
            return;
        }
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(surface);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            if (zoom > 1.01f) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            }
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req,
                                               TotalCaptureResult result) {
                    Object seq = result.get(CaptureResult.FLASH_STATE);
                    log(cb, "闪光已触发并完成（FLASH_STATE=" + seq + "）");
                    handler.postDelayed(() -> {
                        closeInternal();
                        log(cb, "会话已关闭");
                    }, 600);
                }
            }, handler);
            log(cb, "已下发 FLASH_MODE_SINGLE 请求");
        } catch (Throwable t) {
            log(cb, "触发闪光失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    // ---------------- 工具 ----------------

    private static int[] pickSize(CameraCharacteristics ch) {
        try {
            StreamConfigurationMap map = ch.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes != null && sizes.length > 0) {
                    java.util.Arrays.sort(sizes, (x, y) -> Integer.compare(
                            x.getWidth() * x.getHeight(), y.getWidth() * y.getHeight()));
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

    private static void log(Cb cb, String s) {
        LogBus.i(s);
        if (cb != null) {
            cb.log(s);
        }
    }

    private void closeInternal() {
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
        closed = true;
    }

    public void release() {
        handler.post(() -> {
            closeInternal();
            thread.quitSafely();
        });
    }
}
