package com.dualtorch.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.dualtorch.app.core.RootEngine;

import java.util.List;
import java.util.Locale;

/**
 * Root 面板：把驱动层的 LED 节点直接摊开。
 *
 * 这里不做"智能判断"，只做两件事：列出节点、按你点的按钮写值。
 * 因为不同平台节点名字和语义差别很大，靠猜不如让人看着灯点。
 */
public class RootActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private TextView tvInfo, tvLog, tvLevel;
    private int percent = 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root);

        box = findViewById(R.id.rootBox);
        tvInfo = findViewById(R.id.tvRootInfo);
        tvLog = findViewById(R.id.tvRootLog);
        tvLevel = findViewById(R.id.tvRootLevel);

        SeekBar sb = findViewById(R.id.sbRootLevel);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                percent = progress;
                tvLevel.setText(percent + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        new Thread(this::build, "root-build").start();
    }

    private void build() {
        final boolean root = RootEngine.hasRoot();
        final String platform = RootEngine.platform();
        final List<RootEngine.Node> nodes = RootEngine.scan();
        final List<RootEngine.MtkNode> mtk = RootEngine.scanMtk();
        final boolean mtkStrobe = RootEngine.hasMtkFlashlight();

        ui.post(() -> {
            tvInfo.setText((root ? "已获得 root" : "未获得 root（下面写入会失败）")
                    + "\n平台：" + platform
                    + "\nLED 节点：" + nodes.size() + " 个"
                    + "\nMTK 节点：" + mtk.size() + " 个"
                    + (mtkStrobe ? "（含 flashlight_strobe）" : ""));

            if (!root) {
                addHint("没有 root 时这些节点都写不进去。vivo 旗舰官方不开放 BL 解锁，"
                        + "这一步只对已解锁并装好 KernelSU / Magisk 的机器有效。");
            }

            if (!nodes.isEmpty()) {
                addSection("LED 节点（高通 / 通用 leds 子系统）");
                for (RootEngine.Node n : nodes) {
                    addNodeRow(n);
                }
            }

            if (mtkStrobe) {
                addSection("联发科 flashlight_strobe（单灯 + 电流档）");
                addHint("命令格式：TYPE CT PART LEVEL DURATION。PART 是灯路，LEVEL 0–30 是电流档。");
                for (int part = 0; part < 2; part++) {
                    final int p = part;
                    addButtonRow("灯路 " + part + " 常亮（torch）", "熄灭",
                            () -> mtkTorch(p, true), () -> mtkTorch(p, false));
                }
                addButtonRow("灯路 0 闪一下（flash 27 档 300ms）", "灯路 1 闪一下",
                        () -> mtkPulse(0), () -> mtkPulse(1));
            } else if (!mtk.isEmpty()) {
                addSection("flashlight_core 节点");
                StringBuilder sb = new StringBuilder();
                for (RootEngine.MtkNode n : mtk) {
                    sb.append(n.name).append("  ");
                }
                addHint(sb.toString());
            }

            if (nodes.isEmpty() && mtk.isEmpty()) {
                addHint("没有扫到任何闪光灯节点。可能是没有 root，也可能是这台机器把 LED "
                        + "挂在别的 class 下（例如 /sys/class/leds/flashlight）。");
            }
        });
    }

    private void mtkTorch(int part, boolean on) {
        int level = on ? Math.max(1, Math.round(30 * percent / 100f)) : 0;
        String err = RootEngine.mtkTorch(part, on, level);
        log((on ? "点亮" : "熄灭") + " MTK 灯路 " + part + "（level " + level + "）"
                + (err == null ? " 成功" : " 失败：" + err));
    }

    private void mtkPulse(int part) {
        String err = RootEngine.mtkStrobe(0, part, 27, 300);
        log("MTK 灯路 " + part + " 闪 300ms" + (err == null ? " 成功" : " 失败：" + err));
    }

    private void addNodeRow(RootEngine.Node n) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView name = new TextView(this);
        name.setText(n.shortName() + "\n" + n.path + "  max " + n.max);
        name.setTextColor(0xFFEEF1F6);
        name.setTextSize(11);
        name.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, lp);

        TextView on = chip("开");
        on.setOnClickListener(v -> {
            int val = Math.max(1, n.max * percent / 100);
            String err = RootEngine.setBrightness(n.path + "/brightness", val);
            log("写 " + n.path + "/brightness = " + val + (err == null ? " 成功" : " 失败：" + err));
        });
        row.addView(on);

        TextView off = chip("关");
        off.setOnClickListener(v -> {
            String err = RootEngine.setBrightness(n.path + "/brightness", 0);
            log("写 " + n.path + "/brightness = 0" + (err == null ? " 成功" : " 失败：" + err));
        });
        row.addView(off);

        box.addView(row);
    }

    private void addButtonRow(String left, String right, Runnable l, Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView a = chip(left);
        a.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        a.setOnClickListener(v -> l.run());
        row.addView(a);

        TextView b = chip(right);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        b.setOnClickListener(v -> r.run());
        row.addView(b);

        box.addView(row);
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(0xFFEEF1F6);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackgroundResource(R.drawable.bg_chip);
        return t;
    }

    private void addSection(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFFB020);
        t.setTextSize(12);
        t.setPadding(0, dp(18), 0, dp(6));
        box.addView(t);
    }

    private void addHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF8B94A7);
        t.setTextSize(11);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(6), 0, dp(6));
        box.addView(t);
    }

    private void log(String line) {
        ui.post(() -> {
            tvLog.append(line + "\n");
            tvLog.append("");
        });
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
