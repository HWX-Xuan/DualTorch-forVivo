package com.dualtorch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.dualtorch.app.core.LogBus;
import com.dualtorch.app.core.Prefs;
import com.dualtorch.app.core.TorchController;
import com.dualtorch.app.core.UpdateChecker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高级选项：策略、相机会话、频闪、Vendor、日志、系统入口、实验工具。
 * 主界面只留灯路和亮度，其余全部收在这里。
 */
public class AdvancedActivity extends Activity implements LogBus.Sink {

    private TorchController controller;

    private TextView tvModeHint, tvLog;
    private TextView[] modeChips, styleChips, strobeChips, camChips;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_main);

        controller = TorchController.get(this);
        tvModeHint = findViewById(R.id.tvModeHint);
        tvLog = findViewById(R.id.tvLog);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        setupModeChips();
        setupSessionChips();
        setupStyleChips();
        setupStrobeChips();
        setupEntryCard();
        setupUpdateCard();
        setupTools();
        updateModeHint();

        LogBus.addSink(this);
        for (String line : LogBus.tail(40)) {
            tvLog.append(line + "\n");
        }
    }

    // ---------------- 策略 ----------------

    private void setupModeChips() {
        modeChips = new TextView[]{
                findViewById(R.id.chipAuto), findViewById(R.id.chipSystem),
                findViewById(R.id.chipCamera), findViewById(R.id.chipVendor),
                findViewById(R.id.chipRoot)
        };
        for (int i = 0; i < modeChips.length; i++) {
            final int idx = i;
            modeChips[i].setOnClickListener(v -> {
                select(modeChips, idx);
                controller.setMode(TorchController.Mode.values()[idx]);
                updateModeHint();
            });
        }
        select(modeChips, controller.mode().ordinal());
    }

    private void setupSessionChips() {
        camChips = new TextView[]{
                findViewById(R.id.chipCamAuto), findViewById(R.id.chipCamA),
                findViewById(R.id.chipCamTele)
        };
        camChips[0].setOnClickListener(v -> {
            select(camChips, 0);
            controller.setSessionCamera(null);
        });
        camChips[1].setOnClickListener(v -> {
            select(camChips, 1);
            controller.setSessionCamera(controller.idA());
        });
        camChips[2].setOnClickListener(v -> {
            select(camChips, 2);
            controller.setSessionCamera(controller.teleId());
        });
        select(camChips, 0);
    }

    private void setupStyleChips() {
        styleChips = new TextView[]{
                findViewById(R.id.chipFlashTorch), findViewById(R.id.chipFlashAlways),
                findViewById(R.id.chipFlashBoth)
        };
        for (int i = 0; i < styleChips.length; i++) {
            final int idx = i;
            styleChips[i].setOnClickListener(v -> {
                select(styleChips, idx);
                controller.setStyle(idx);
            });
        }
        select(styleChips, 0);
    }

    private void setupStrobeChips() {
        strobeChips = new TextView[]{
                findViewById(R.id.chipStrobeOff), findViewById(R.id.chipStrobe2),
                findViewById(R.id.chipStrobe5), findViewById(R.id.chipStrobe10),
                findViewById(R.id.chipSos)
        };
        for (int i = 0; i < strobeChips.length; i++) {
            final int idx = i;
            strobeChips[i].setOnClickListener(v -> {
                select(strobeChips, idx);
                if (idx == 4) {
                    controller.setSos();
                } else {
                    int[] hz = {0, 2, 5, 10};
                    controller.setStrobe(hz[idx]);
                }
            });
        }
        select(strobeChips, 0);

        EditText etKey = findViewById(R.id.etVendorKey);
        EditText etValue = findViewById(R.id.etVendorValue);
        findViewById(R.id.btnApplyVendor).setOnClickListener(v -> {
            String k = etKey.getText().toString().trim();
            String val = etValue.getText().toString().trim();
            if (k.isEmpty()) {
                Toast.makeText(this, "请先填写厂商参数 key", Toast.LENGTH_SHORT).show();
                return;
            }
            if (val.isEmpty()) {
                val = "1";
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put(k, val);
            select(modeChips, 3);
            controller.setMode(TorchController.Mode.VENDOR);
            controller.setVendor(m);
            updateModeHint();
        });
    }

    private void updateModeHint() {
        String hint;
        switch (controller.mode()) {
            case SYSTEM:
                hint = "CameraManager.setTorchMode。最省事，一颗相机对应一个 Flash 单元，只能点广角那颗。";
                break;
            case CAMERA:
                hint = "建立预览流，把 FLASH_MODE_TORCH 塞进重复请求，由 HAL 按 zoom 决定点几颗灯。";
                break;
            case VENDOR:
                hint = "在相机会话里注入厂商私有 tag。先在「硬件探测」里找可疑的 flash key 再填。";
                break;
            case ROOT:
                hint = "直写 /sys/class/leds 下的 torch 节点，可独立控制两路。需要 root。";
                break;
            default:
                hint = "自动：能走系统手电就走系统手电，否则退回相机会话。";
                break;
        }
        tvModeHint.setText(hint);
    }

    // ---------------- 系统入口 ----------------

    private void setupEntryCard() {
        Switch sw = findViewById(R.id.swAutoOn);
        sw.setChecked(Prefs.autoOnLaunch(this));
        sw.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setAutoOnLaunch(this, checked);
            LogBus.i("冷启动自动开灯 → " + (checked ? "开" : "关"));
        });
        findViewById(R.id.btnAddTile).setOnClickListener(v -> addTile());
        findViewById(R.id.btnGuide).setOnClickListener(v -> showGuide());
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "系统低于 Android 13，请手动在控制中心编辑里添加",
                    Toast.LENGTH_LONG).show();
            showGuide();
            return;
        }
        try {
            StatusBarManager sbm = getSystemService(StatusBarManager.class);
            sbm.requestAddTileService(
                    new ComponentName(this, TorchTileService.class),
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    getMainExecutor(),
                    result -> runOnUiThread(() -> Toast.makeText(this,
                            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                    ? "已添加到控制中心" : "未添加（可手动在编辑里拖入）",
                            Toast.LENGTH_SHORT).show()));
        } catch (Throwable t) {
            LogBus.e("请求添加磁贴失败：" + t);
            showGuide();
        }
    }

    private void showGuide() {
        String text =
                "① 控制中心（可用）\n"
                        + "下拉控制中心 → 编辑（铅笔）→ 全部开关 → 找到「双芯闪光灯 / 手电灯路」，拖进常用区。\n"
                        + "两个磁贴分别管：开关（开 / 关手电）和灯路（广角单灯 ↔ 双灯）；调亮度请用通知栏或桌面快捷方式「切亮度」。\n\n"
                        + "② 快捷启动（可用，熄屏也能用）\n"
                        + "设置 → 快捷与辅助 → 快捷启动 → 打开 → 打开应用 → 双芯闪光灯。\n"
                        + "回到本页打开「打开应用即开灯」，长按音量减就直接亮灯。\n\n"
                        + "③ 轻敲背面（可用）\n"
                        + "设置 → 快捷与辅助 → 轻敲手机背面 → 轻敲三下 → 打开应用 → 双芯闪光灯。\n\n"
                        + "④ 锁屏右侧快捷入口（接不了）\n"
                        + "只列系统内置项，没有第三方入口。用「快捷启动」替代。\n\n"
                        + "⑤ 长按桌面图标（可用）\n"
                        + "开 / 关手电、切灯路、切亮度、关灯，也会被收录进 vivo「原子控件」。";
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFFD8DEE9);
        tv.setLineSpacing(6f, 1f);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("哪些系统入口能接第三方")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------- 关于与更新 ----------------

    private void setupUpdateCard() {
        TextView tvVersion = findViewById(R.id.tvAboutVersion);
        tvVersion.setText("双芯闪光灯 v" + UpdateChecker.appVersion(this));

        Switch sw = findViewById(R.id.swAutoUpdate);
        sw.setChecked(Prefs.autoUpdate(this));
        sw.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setAutoUpdate(this, checked);
            LogBus.i("自动检查更新 → " + (checked ? "开" : "关"));
        });

        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> manualCheck());
    }

    private void manualCheck() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        UpdateChecker.check(this, (rel, err) -> {
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                return;
            }
            if (rel == null) {
                Toast.makeText(this, "已是最新版本 v" + UpdateChecker.appVersion(this),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            UpdateDialog.show(this, rel);
        });
    }

    // ---------------- 工具 ----------------

    private void setupTools() {
        findViewById(R.id.btnLab).setOnClickListener(v ->
                startActivity(new Intent(this, LabActivity.class)));
        findViewById(R.id.btnProbe).setOnClickListener(v ->
                startActivity(new Intent(this, ProbeActivity.class)));
        findViewById(R.id.btnScan).setOnClickListener(v ->
                startActivity(new Intent(this, ScanActivity.class)));
        findViewById(R.id.btnRoot).setOnClickListener(v ->
                startActivity(new Intent(this, RootActivity.class)));
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dualtorch-log", LogBus.dump()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        });
    }

    private void select(TextView[] group, int index) {
        for (int i = 0; i < group.length; i++) {
            group[i].setSelected(i == index);
        }
    }

    @Override
    public void onLog(String line) {
        runOnUiThread(() -> {
            tvLog.append(line + "\n");
            View parent = tvLog;
            int scroll = tvLog.getLayout() == null ? 0
                    : tvLog.getLayout().getLineTop(tvLog.getLineCount()) - parent.getHeight();
            if (scroll > 0) {
                tvLog.scrollTo(0, scroll);
            }
        });
    }

    @Override
    protected void onDestroy() {
        LogBus.removeSink(this);
        super.onDestroy();
    }
}
