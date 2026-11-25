package com.dualtorch.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.dualtorch.app.core.LogBus;
import com.dualtorch.app.core.Prefs;
import com.dualtorch.app.core.TorchController;
import com.dualtorch.app.core.UpdateChecker;

/**
 * 主界面：只做两件事 —— 灯路切换 + 亮度。
 *
 * 亮度档位不写死：硬件上报几档，滑条就生成几档（关 + 1..N）。
 * 这台机器的 HAL 只有真实档位，给 95 格滑条只会骗人。
 */
public class MainActivity extends Activity implements TorchController.Listener, LogBus.Sink {

    private static final int REQ_CAMERA = 1001;
    private static final int REQ_NOTIFY = 1002;

    private TorchController controller;

    private LinearLayout btnMaster, tickRow;
    private TextView tvMasterTitle, tvMasterSub, tvStatus, tvStrategy;
    private TextView tvStrength, tvStrengthHint, tvFocalHint;
    private TextView[] tiles;
    private SeekBar sbStrength;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private int focal;
    private int level;
    private int hwMax = 1;
    private boolean uiLock;
    private String lastNote;
    private Runnable pendingLevel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        controller = TorchController.get(this);
        controller.addListener(this);
        LogBus.addSink(this);
        LogBus.i("启动 · " + Build.MANUFACTURER + " " + Build.MODEL);

        hwMax = controller.maxStrength();
        focal = Prefs.focal(this);
        level = clamp(Prefs.levelIndex(this));
        sbStrength.setMax(hwMax);
        buildTicks();
        setupTiles();
        setupMaster();
        setupSlider();
        renderFocal();
        renderStrength(level);
        refresh();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }

        // 长按音量下键 / 敲击背面 → 系统只是把本应用拉起来，不会带任何标记。
        // 所以冷启动一律按「直接开双灯」处理，这才是快捷入口该有的样子。
        if (savedInstanceState == null && Prefs.autoOnLaunch(this) && !Prefs.lit(this)) {
            launchDual();
        }

        // 延迟几秒再查更新，别和开灯、权限弹窗抢时机
        ui.postDelayed(this::autoCheckUpdate, 3500);
    }

    /**
     * 启动时静默检查更新：6 小时内只查一次，跳过用户标记过的版本。
     * 没网、仓库 404、解析失败都直接放弃，不打扰用户。
     */
    private void autoCheckUpdate() {
        if (isFinishing() || !Prefs.autoUpdate(this)) {
            return;
        }
        long last = Prefs.lastUpdateCheck(this);
        if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) {
            return;
        }
        Prefs.setLastUpdateCheck(this, System.currentTimeMillis());
        UpdateChecker.check(this, (rel, err) -> {
            if (rel == null || isFinishing()) {
                return;
            }
            if (rel.version.equals(Prefs.skippedVersion(this))) {
                return;
            }
            UpdateDialog.show(this, rel);
        });
    }

    /**
     * 应用已经在后台时，长按音量下键 / 敲击背面不会重新走 onCreate，
     * 只会把已有任务提到前台，所以这里也要补一次「直接开双灯」。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Prefs.autoOnLaunch(this) && !Prefs.lit(this)) {
            launchDual();
        }
    }

    /** 快捷入口：不做任何选择，直接两颗全亮。 */
    private void launchDual() {
        focal = Prefs.FOCAL_DUAL;
        hwMax = controller.maxStrength();
        level = hwMax;
        Prefs.setFocal(this, focal);
        Prefs.setLevelIndex(this, level);
        Prefs.setLit(this, true);
        renderFocal();
        renderStrength(level);
        controller.applyFocal(focal, level);
        TorchService.send(this, TorchService.CMD_ON);
        refresh();
        LogBus.i("快捷启动 → 直接开双灯");
    }

    private void bindViews() {
        btnMaster = findViewById(R.id.btnMaster);
        tvMasterTitle = findViewById(R.id.tvMasterTitle);
        tvMasterSub = findViewById(R.id.tvMasterSub);
        tvStatus = findViewById(R.id.tvStatus);
        tvStrategy = findViewById(R.id.tvStrategy);
        tvStrength = findViewById(R.id.tvStrength);
        tvStrengthHint = findViewById(R.id.tvStrengthHint);
        tvFocalHint = findViewById(R.id.tvFocalHint);
        sbStrength = findViewById(R.id.sbStrength);
        tickRow = findViewById(R.id.tickRow);
        tiles = new TextView[]{
                findViewById(R.id.tileWide), findViewById(R.id.tileDual)
        };
        findViewById(R.id.btnAdvanced).setOnClickListener(v ->
                startActivity(new Intent(this, AdvancedActivity.class)));
    }

    // ---------------- 灯路 ----------------

    private void setupTiles() {
        for (int i = 0; i < tiles.length; i++) {
            final int idx = i;
            tiles[i].setOnClickListener(v -> {
                if (focal == idx) {
                    return;
                }
                focal = idx;
                Prefs.setFocal(this, idx);
                if (level <= Prefs.LEVEL_OFF) {
                    level = hwMax;
                    Prefs.setLevelIndex(this, level);
                }
                renderFocal();
                renderStrength(level);
                if (Prefs.lit(this)) {
                    // 控制器内部会保证两次通路切换之间的最小间隔
                    controller.applyFocal(idx, level);
                } else {
                    refresh();
                }
                LogBus.i("灯路 → " + Prefs.focalName(idx));
            });
        }
    }

    private void renderFocal() {
        for (int i = 0; i < tiles.length; i++) {
            tiles[i].setSelected(i == focal);
            tiles[i].setText(tileText(Prefs.focalName(i), Prefs.focalSub(i)));
        }
        tvFocalHint.setText(Prefs.focalDesc(focal));
        updateMasterSub();
    }

    /** 磁贴两行：主标题 + 小一号的说明。 */
    private static CharSequence tileText(String main, String sub) {
        SpannableStringBuilder sb = new SpannableStringBuilder(main);
        sb.append('\n');
        int start = sb.length();
        sb.append(sub);
        sb.setSpan(new RelativeSizeSpan(0.66f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ---------------- 亮度：关 + 硬件 1..N 档 ----------------

    /**
     * 刻度按硬件档位数生成：4 档就是 关 / 一档 / 二档 / 三档 / 最亮。
     * 用户看到几个刻度，硬件就有几个真实位置，不存在「滑了没变化」。
     */
    private void buildTicks() {
        tickRow.removeAllViews();
        for (int i = 0; i <= hwMax; i++) {
            TextView t = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(lp);
            t.setText(Prefs.levelTick(i, hwMax));
            t.setTextSize(9f);
            t.setTextColor(0xFF5A6373);
            t.setSingleLine(true);
            t.setGravity(i == 0 ? Gravity.START : i == hwMax ? Gravity.END : Gravity.CENTER);
            if (i == hwMax) {
                t.setTypeface(Typeface.DEFAULT_BOLD);
            }
            tickRow.addView(t);
        }
    }

    private void setupSlider() {
        sbStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (uiLock || !fromUser) {
                    return;
                }
                renderStrength(progress);
                scheduleLevel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyLevel(seekBar.getProgress());
            }
        });
    }

    /** 拖动时防抖，松手时立即落定，避免每一格都去敲 HAL。 */
    private void scheduleLevel(final int idx) {
        if (pendingLevel != null) {
            ui.removeCallbacks(pendingLevel);
        }
        pendingLevel = () -> {
            pendingLevel = null;
            applyLevel(idx);
        };
        ui.postDelayed(pendingLevel, 120);
    }

    private void applyLevel(int idx) {
        if (pendingLevel != null) {
            ui.removeCallbacks(pendingLevel);
            pendingLevel = null;
        }
        level = clamp(idx);
        Prefs.setLevelIndex(this, level);

        if (level == Prefs.LEVEL_OFF) {
            Prefs.setLit(this, false);
            controller.allOff();
            TorchService.send(this, TorchService.CMD_OFF);
            lastNote = null;
            refresh();
            LogBus.i("滑到关闭");
            return;
        }
        Prefs.setLit(this, true);
        controller.applyFocal(focal, level);
        TorchService.send(this, TorchService.CMD_ON);
        refresh();
    }

    private void renderStrength(int idx) {
        idx = clamp(idx);
        if (sbStrength.getProgress() != idx) {
            uiLock = true;
            sbStrength.setProgress(idx);
            uiLock = false;
        }

        highlightTick(idx);

        boolean dimmable = focal == Prefs.FOCAL_WIDE;
        sbStrength.setEnabled(dimmable);
        sbStrength.setAlpha(dimmable ? 1f : 0.3f);

        if (!dimmable) {
            tvStrength.setText("满功率");
            tvStrengthHint.setText("双灯固定满功率，HAL 不支持调光。要调亮度请切到「广角」。");
            updateMasterSub();
            return;
        }

        tvStrength.setText(Prefs.levelName(idx, hwMax));
        if (hwMax <= 1) {
            tvStrengthHint.setText("本机未上报硬件档位，只能开 / 关。");
        } else if (idx == Prefs.LEVEL_OFF) {
            tvStrengthHint.setText("拖到最左就是关灯 · 共 " + hwMax + " 档");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("可用 ").append(hwMax).append(" 档 · 当前第 ").append(idx).append(" 档");
            int back = controller.torchReadback();
            if (back > 0 && Prefs.lit(this)) {
                sb.append(" · 回读 ").append(back);
            }
            tvStrengthHint.setText(sb.toString());
        }
        updateMasterSub();
    }

    /** 当前档位那格点亮，其余压暗，一眼能看出停在哪一档。 */
    private void highlightTick(int idx) {
        for (int i = 0; i < tickRow.getChildCount(); i++) {
            TextView t = (TextView) tickRow.getChildAt(i);
            boolean on = i == idx && focal == Prefs.FOCAL_WIDE;
            t.setTextColor(on ? 0xFFFFB020 : 0xFF5A6373);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private int clamp(int idx) {
        return Math.max(Prefs.LEVEL_OFF, Math.min(hwMax, idx));
    }

    // ---------------- 主开关 ----------------

    private void setupMaster() {
        btnMaster.setOnClickListener(v -> {
            if (Prefs.lit(this)) {
                Prefs.setLit(this, false);
                controller.allOff();
                TorchService.send(this, TorchService.CMD_OFF);
                lastNote = null;
                refresh();
                LogBus.i("关灯");
            } else {
                if (level == Prefs.LEVEL_OFF) {
                    level = hwMax;
                    Prefs.setLevelIndex(this, level);
                    renderStrength(level);
                }
                Prefs.setLit(this, true);
                controller.applyFocal(focal, level);
                TorchService.send(this, TorchService.CMD_ON);
                refresh();
                LogBus.i("开灯 → " + Prefs.summary(this));
            }
        });
    }

    private void updateMasterSub() {
        if (tvMasterSub == null) {
            return;
        }
        if (focal != Prefs.FOCAL_WIDE) {
            tvMasterSub.setText("双灯 · 满功率");
            return;
        }
        tvMasterSub.setText("广角 · " + Prefs.levelName(level, hwMax));
    }

    private void refresh() {
        boolean on = Prefs.lit(this);
        btnMaster.setBackgroundResource(on ? R.drawable.bg_power_on : R.drawable.bg_power_off);
        tvMasterTitle.setText(on ? "关灯" : "开灯");
        tvMasterTitle.setTextColor(on ? 0xFF0A0C10 : 0xFFEEF1F6);
        tvMasterSub.setTextColor(on ? 0xCC0A0C10 : 0xFF8B94A7);
        updateMasterSub();
        tvStatus.setText(on ? (lastNote == null ? Prefs.focalName(focal) + " 已点亮" : lastNote) : "已关闭");
        tvStrategy.setText("策略 " + controller.effectiveModeLabel());
    }

    @Override
    public void onState(boolean a, boolean b, String note) {
        runOnUiThread(() -> {
            if (note != null) {
                lastNote = note;
            }
            renderStrength(level);
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 磁贴 / 快捷方式可能在后台改过状态，回来时对齐一次
        hwMax = controller.maxStrength();
        if (sbStrength.getMax() != hwMax) {
            sbStrength.setMax(hwMax);
            buildTicks();
        }
        focal = Prefs.focal(this);
        level = clamp(Prefs.levelIndex(this));
        renderFocal();
        renderStrength(level);
        refresh();

        // 系统在后台可能把相机会话切断（尤其没走 camera 类型前台服务时）。
        // 回到前台发现灯应该亮着、会话却没了，就补点一次。
        if (Prefs.lit(this) && focal == Prefs.FOCAL_DUAL
                && !controller.isSessionRunning() && !controller.isSessionOpening()) {
            LogBus.i("回到前台，双灯会话已断，重新点亮");
            controller.applyFocal(focal, level);
        }
    }

    @Override
    public void onLog(String line) {
        // 主界面不展示日志，日志在「高级」里看
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            LogBus.i(ok ? "相机权限已授予" : "未授予相机权限，双灯档不可用");
            if (ok && Prefs.lit(this)) {
                controller.applyFocal(focal, level);
            }
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        controller.removeListener(this);
        LogBus.removeSink(this);
        if (!Prefs.lit(this)) {
            controller.allOff();
        }
        super.onDestroy();
    }
}
