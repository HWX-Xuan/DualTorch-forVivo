package com.dualtorch.app;

import android.app.Activity;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import com.dualtorch.app.core.PhysicalTorchEngine;

import java.util.Locale;
import java.util.Set;

/**
 * 物理子相机实验室：物理子相机会话 + 单次闪光。
 * 这两条是自检之后仅剩的非 root 通路，从「高级 → 工具」进入。
 */
public class LabActivity extends Activity {

    private PhysicalTorchEngine engine;
    private TextView tvPhysList, tvPhysLog, tvFlashLog;
    private EditText etLogical, etPhys, etPhysZoom, etFlashZoom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced);

        tvPhysList = findViewById(R.id.tvPhysList);
        tvPhysLog = findViewById(R.id.tvPhysLog);
        tvFlashLog = findViewById(R.id.tvFlashLog);
        etLogical = findViewById(R.id.etLogical);
        etPhys = findViewById(R.id.etPhys);
        etPhysZoom = findViewById(R.id.etPhysZoom);
        etFlashZoom = findViewById(R.id.etFlashZoom);

        engine = new PhysicalTorchEngine(this);
        renderPhysicalList();

        findViewById(R.id.btnPhysOn).setOnClickListener(v -> {
            tvPhysLog.setText("");
            engine.openPhysical(text(etLogical, "0"), text(etPhys, "5"),
                    zoom(etPhysZoom, 1f), this::appendPhys);
        });
        findViewById(R.id.btnPhysOff).setOnClickListener(v ->
                engine.close(this::appendPhys));
        findViewById(R.id.btnFlash).setOnClickListener(v -> {
            tvFlashLog.setText("");
            engine.flashOnce("0", zoom(etFlashZoom, 3f), this::appendFlash);
        });
    }

    private void renderPhysicalList() {
        StringBuilder sb = new StringBuilder();
        String teleParent = null;
        String telePhys = null;
        float teleFocal = -1f;
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Set<String> phys = ch.getPhysicalCameraIds();
                float[] f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                String focal = (f != null && f.length > 0)
                        ? String.format(Locale.US, "%.1fmm", f[0]) : "—";
                sb.append("camera ").append(id).append(" · ").append(focal);
                if (phys == null || phys.isEmpty()) {
                    sb.append("（无物理子相机）\n");
                    continue;
                }
                sb.append("  →  ").append(phys).append('\n');
                for (String p : phys) {
                    try {
                        CameraCharacteristics pc = cm.getCameraCharacteristics(p);
                        float[] pf = pc.get(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        float fl = (pf != null && pf.length > 0) ? pf[0] : 0f;
                        sb.append("    · ").append(p).append("  ")
                                .append(String.format(Locale.US, "%.1fmm", fl)).append('\n');
                        if (fl > teleFocal) {
                            teleFocal = fl;
                            teleParent = id;
                            telePhys = p;
                        }
                    } catch (Throwable t) {
                        sb.append("    · ").append(p).append("  读取失败\n");
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("读取相机列表失败：").append(t);
        }
        tvPhysList.setText(sb.toString());
        if (telePhys != null) {
            etLogical.setText(teleParent);
            etPhys.setText(telePhys);
        }
    }

    private void appendPhys(String line) {
        runOnUiThread(() -> tvPhysLog.append(line + "\n"));
    }

    private void appendFlash(String line) {
        runOnUiThread(() -> tvFlashLog.append(line + "\n"));
    }

    private static String text(EditText et, String def) {
        String s = et.getText() == null ? "" : et.getText().toString().trim();
        return s.isEmpty() ? def : s;
    }

    private static float zoom(EditText et, float def) {
        try {
            float v = Float.parseFloat(text(et, String.valueOf(def)));
            return v <= 0 ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    @Override
    protected void onDestroy() {
        if (engine != null) {
            engine.release();
        }
        super.onDestroy();
    }
}
