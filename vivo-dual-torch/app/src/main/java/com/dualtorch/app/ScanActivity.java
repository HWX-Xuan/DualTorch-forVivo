package com.dualtorch.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.dualtorch.app.core.FlashProbe;
import com.dualtorch.app.core.Prefs;
import com.dualtorch.app.core.RootEngine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 真机自检：把「到底能不能只点一颗灯」「亮度档位到底有没有落到硬件」
 * 这两个问题，用可复现的步骤跑一遍，并给出结论。
 *
 * 设计原则：不猜。每一路都实际下发 setTorchMode，再由用户肉眼确认亮了几颗。
 */
public class ScanActivity extends Activity {

    private static final int RES_FAIL = -1;
    private static final int RES_NONE = 0;
    private static final int RES_TOP = 1;
    private static final int RES_BOTTOM = 2;
    private static final int RES_BOTH = 3;

    private static final int PHASE_LED = 0;
    private static final int PHASE_BRIGHT = 1;
    private static final int PHASE_DONE = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> results = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();

    private CameraManager cm;
    private List<FlashProbe.Cam> cams;

    private int phase = PHASE_LED;
    private int idx;
    private boolean waiting;
    private String currentId;
    private Runnable timeout;

    private String singleId;
    private String brightId;
    private int brightMax = 1;
    private int declaredMax = 1;
    private int readAtMax = -1;
    private int readAtOne = -1;
    private String brightError;
    private Boolean dimmed;

    private TextView tvProgress, tvQuestion, tvDetail, tvReport;
    private View rowTop, rowBottom, rowBright, rowActions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        cm = FlashProbe.manager(this);
        tvProgress = findViewById(R.id.tvScanProgress);
        tvQuestion = findViewById(R.id.tvScanQuestion);
        tvDetail = findViewById(R.id.tvScanDetail);
        tvReport = findViewById(R.id.tvReport);
        rowTop = findViewById(R.id.rowAnswerTop);
        rowBottom = findViewById(R.id.rowAnswerBottom);
        rowBright = findViewById(R.id.rowAnswerBright);
        rowActions = findViewById(R.id.rowActions);

        findViewById(R.id.btnTopOnly).setOnClickListener(v -> answer(RES_TOP));
        findViewById(R.id.btnBottomOnly).setOnClickListener(v -> answer(RES_BOTTOM));
        findViewById(R.id.btnBothOn).setOnClickListener(v -> answer(RES_BOTH));
        findViewById(R.id.btnNoneOn).setOnClickListener(v -> answer(RES_NONE));
        findViewById(R.id.btnDimmed).setOnClickListener(v -> answerBright(true));
        findViewById(R.id.btnNoChange).setOnClickListener(v -> answerBright(false));
        findViewById(R.id.btnRestart).setOnClickListener(v -> restart());
        findViewById(R.id.btnCopyReport).setOnClickListener(v -> {
            ClipboardManager c = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            c.setPrimaryClip(ClipData.newPlainText("dualtorch-report", tvReport.getText()));
            Toast.makeText(this, "报告已复制", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnShareReport).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "双芯闪光灯 · 真机自检报告");
            i.putExtra(Intent.EXTRA_TEXT, tvReport.getText().toString());
            startActivity(Intent.createChooser(i, "分享报告"));
        });
        findViewById(R.id.btnRootPanel).setOnClickListener(v ->
                startActivity(new Intent(this, RootActivity.class)));

        restart();
    }

    private void restart() {
        cancelTimeout();
        allOff();
        results.clear();
        errors.clear();
        phase = PHASE_LED;
        idx = 0;
        waiting = false;
        brightId = null;
        singleId = null;
        brightMax = 1;
        declaredMax = 1;
        readAtMax = -1;
        readAtOne = -1;
        brightError = null;
        dimmed = null;

        tvReport.setVisibility(View.GONE);
        rowActions.setVisibility(View.GONE);
        findViewById(R.id.btnUseSingle).setVisibility(View.GONE);
        rowBright.setVisibility(View.GONE);
        rowTop.setVisibility(View.VISIBLE);
        rowBottom.setVisibility(View.VISIBLE);

        tvQuestion.setText("正在读取相机列表…");
        tvDetail.setText("");
        tvProgress.setText("");

        cams = FlashProbe.scan(this);
        ui.postDelayed(this::nextLed, 250);
    }

    // ---------------- 第一阶段：逐路点亮 ----------------

    private void nextLed() {
        if (cams == null) {
            return;
        }
        while (idx < cams.size()) {
            FlashProbe.Cam c = cams.get(idx);
            boolean worthTrying = c.flash || c.physical || (c.error == null && !c.listed);
            if (!worthTrying) {
                // 明确没有闪光灯、或者根本不存在这个编号，不必打扰用户
                results.put(c.id, RES_NONE);
                idx++;
                continue;
            }
            currentId = c.id;
            String err = FlashProbe.setTorch(cm, c.id, true);
            tvProgress.setText(String.format(Locale.US, "灯路 %d/%d", idx + 1, cams.size()));
            if (err != null) {
                results.put(c.id, RES_FAIL);
                errors.put(c.id, err);
                tvQuestion.setText("camera " + c.id + " 无法点亮");
                tvDetail.setText(err);
                idx++;
                ui.postDelayed(this::nextLed, 500);
                return;
            }
            waiting = true;
            tvQuestion.setText("现在亮了几颗灯？");
            tvDetail.setText(c.label() + "\n" + c.caps()
                    + "\n（9 秒内不选，按「没亮」处理）");
            scheduleTimeout();
            return;
        }
        startBrightness();
    }

    private void scheduleTimeout() {
        cancelTimeout();
        timeout = () -> {
            if (waiting) {
                answer(RES_NONE);
            }
        };
        ui.postDelayed(timeout, 9000);
    }

    private void cancelTimeout() {
        if (timeout != null) {
            ui.removeCallbacks(timeout);
            timeout = null;
        }
    }

    private void answer(int res) {
        if (!waiting) {
            return;
        }
        waiting = false;
        cancelTimeout();
        FlashProbe.setTorch(cm, currentId, false);
        results.put(currentId, res);
        idx++;
        ui.postDelayed(this::nextLed, 350);
    }

    // ---------------- 第二阶段：亮度 ----------------

    private void startBrightness() {
        phase = PHASE_BRIGHT;
        brightId = null;
        for (Map.Entry<String, Integer> e : results.entrySet()) {
            int v = e.getValue();
            if (v == RES_BOTH || v == RES_TOP || v == RES_BOTTOM) {
                brightId = e.getKey();
                break;
            }
        }
        if (brightId == null) {
            finishScan();
            return;
        }

        declaredMax = 1;
        for (FlashProbe.Cam c : cams) {
            if (c.id.equals(brightId)) {
                declaredMax = Math.max(c.torchMax, c.infoMax);
                break;
            }
        }

        rowTop.setVisibility(View.GONE);
        rowBottom.setVisibility(View.GONE);
        tvProgress.setText("亮度自检");
        tvQuestion.setText("亮度：实测 HAL 到底接受几档");
        tvDetail.setText("目标 camera " + brightId + " · 厂商声明 " + declaredMax + " 档"
                + "\n正在逐档试，灯会闪几下，正常现象");

        final int probed = FlashProbe.probeMaxLevel(cm, brightId, declaredMax);
        FlashProbe.setTorch(cm, brightId, false);

        if (probed == 0) {
            brightError = "连 1 档都被拒绝，档位接口不可用";
            finishScan();
            return;
        }
        if (probed <= 1) {
            brightError = "厂商声明 " + declaredMax + " 档，实测只接受 1 档 → 这颗灯不能调光";
            finishScan();
            return;
        }
        brightMax = probed;

        tvDetail.setText("目标 camera " + brightId
                + "\n厂商声明 " + declaredMax + " 档，实测可用 1–" + probed + " 档"
                + "\n注意看灯，稍后会问你是否变暗");

        String err = FlashProbe.setTorchLevel(cm, brightId, brightMax);
        if (err != null) {
            brightError = err;
            finishScan();
            return;
        }
        ui.postDelayed(() -> {
            readAtMax = FlashProbe.readLevel(cm, brightId);
            String err2 = FlashProbe.setTorchLevel(cm, brightId, 1);
            if (err2 != null) {
                brightError = err2;
                FlashProbe.setTorch(cm, brightId, false);
                finishScan();
                return;
            }
            ui.postDelayed(() -> {
                readAtOne = FlashProbe.readLevel(cm, brightId);
                rowBright.setVisibility(View.VISIBLE);
                tvQuestion.setText("现在比刚才暗吗？");
                tvDetail.setText("设 " + brightMax + " 档 → 回读 " + readAtMax
                        + "\n设 1 档 → 回读 " + readAtOne);
            }, 2200);
        }, 2200);
    }

    private void answerBright(boolean dim) {
        dimmed = dim;
        FlashProbe.setTorch(cm, brightId, false);
        finishScan();
    }

    // ---------------- 结论 ----------------

    private void finishScan() {
        phase = PHASE_DONE;
        allOff();
        rowTop.setVisibility(View.GONE);
        rowBottom.setVisibility(View.GONE);
        rowBright.setVisibility(View.GONE);
        tvProgress.setText("完成");
        tvQuestion.setText("自检结果");
        tvDetail.setText("");

        singleId = null;
        for (Map.Entry<String, Integer> e : results.entrySet()) {
            int v = e.getValue();
            if (v == RES_TOP || v == RES_BOTTOM) {
                singleId = e.getKey();
                break;
            }
        }

        String report = buildReport();
        tvReport.setText(report);
        tvReport.setVisibility(View.VISIBLE);
        rowActions.setVisibility(View.VISIBLE);

        View use = findViewById(R.id.btnUseSingle);
        if (singleId != null) {
            use.setVisibility(View.VISIBLE);
            use.setOnClickListener(v -> {
                Prefs.setTorchId(this, singleId);
                Toast.makeText(this, "已把 camera " + singleId + " 设为手电相机", Toast.LENGTH_SHORT).show();
                finish();
            });
        } else {
            use.setVisibility(View.GONE);
        }
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 双芯闪光灯 · 真机自检 ===\n");
        sb.append("设备 : ").append(FlashProbe.platformTag()).append('\n');
        sb.append("Root : ").append(RootEngine.hasRoot() ? "有" : "无").append('\n');
        sb.append("相机 : ").append(cams == null ? 0 : cams.size()).append(" 个候选\n\n");

        sb.append("--- 灯路扫描 ---\n");
        int single = 0;
        int both = 0;
        int alive = 0;
        if (cams != null) {
            for (FlashProbe.Cam c : cams) {
                Integer r = results.get(c.id);
                if (r == null || r == RES_NONE) {
                    continue;
                }
                String res;
                switch (r) {
                    case RES_TOP:
                        res = "只亮上面那颗";
                        single++;
                        alive++;
                        break;
                    case RES_BOTTOM:
                        res = "只亮下面那颗";
                        single++;
                        alive++;
                        break;
                    case RES_BOTH:
                        res = "两颗都亮";
                        both++;
                        alive++;
                        break;
                    default:
                        res = "失败：" + (errors.containsKey(c.id) ? errors.get(c.id) : "未知");
                        break;
                }
                sb.append(String.format(Locale.US, "%-34s %s\n", c.label(), res));
            }
        }
        if (alive == 0) {
            sb.append("（没有任何 cameraId 成功点亮）\n");
        }

        sb.append("\n--- 亮度 ---\n");
        if (brightId == null) {
            sb.append("未执行（没有可点亮的相机）\n");
        } else if (brightError != null) {
            sb.append("目标 camera ").append(brightId).append("：").append(brightError).append('\n');
        } else {
            sb.append("目标 camera ").append(brightId)
                    .append("，厂商声明 ").append(declaredMax)
                    .append(" 档，实测可用 1–").append(brightMax).append('\n');
            sb.append("  设 ").append(brightMax).append(" 档 → 回读 ").append(readAtMax).append('\n');
            sb.append("  设 1 档 → 回读 ").append(readAtOne).append('\n');
            sb.append("  肉眼：").append(dimmed == null ? "未判定"
                    : (dimmed ? "确实变暗" : "没有变化")).append('\n');
        }

        sb.append("\n--- 结论 ---\n");
        if (single > 0) {
            sb.append("· 存在单灯通道：").append(singleId == null ? "某个" : singleId)
                    .append(" 号 camera 只点亮一颗灯。\n");
            sb.append("  这是应用层能拿到的唯一「单灯」粒度，普通手电筒就用它。\n");
            if (hasOnlyTop()) {
                sb.append("  但只亮的是上面那颗（广角/主摄补光），下面那颗长焦灯没有被单独暴露。\n");
            }
        } else if (both > 0) {
            sb.append("· 所有能点亮的 cameraId 都是「两颗都亮」。\n");
            sb.append("  说明 HAL 把 setTorchMode 绑到了整组 LED，应用层没有单灯入口。\n");
            sb.append("  这不是参数没调对，是相机服务没有把两路 LED 拆开暴露。\n");
        } else {
            sb.append("· 所有候选都拒绝点亮，可能是相机被占用或厂商完全禁用了第三方 torch。\n");
        }
        if (dimmed != null) {
            if (dimmed) {
                sb.append("· 亮度可用：档位真的落到了驱动，滑杆可以直接用。\n");
            } else if (brightMax > 1) {
                sb.append("· 亮度不可用：HAL 报告了 ").append(brightMax)
                        .append(" 档、也接受设置，但灯没有变 → 档位没有落到驱动电流上。\n");
            }
        } else if (brightError != null) {
            sb.append("· 亮度不可用：").append(brightError).append('\n');
        }

        sb.append("\n--- 闪光相关 key ---\n");
        if (brightId != null) {
            sb.append(FlashProbe.dumpFlashKeys(cm, brightId));
        } else if (cams != null) {
            for (FlashProbe.Cam c : cams) {
                if (c.listed && c.facing == 1) {
                    sb.append(FlashProbe.dumpFlashKeys(cm, c.id));
                    break;
                }
            }
        }

        sb.append("\n--- 下一步 ---\n");
        if (singleId != null) {
            sb.append("· 单灯已可用：主页「系统手电」指到 camera ").append(singleId).append("。\n");
            sb.append("· 想点长焦那颗：进「进阶实验」→ 物理子相机点灯 / 单次闪光。\n");
        } else if (both > 0) {
            sb.append("· torch 只能整组点亮，单灯要等 vivo 开放私有接口或 root。\n");
        }
        sb.append("· 有 root：进「Root 直控」，按 MTK / 高通节点单独点灯并调电流。\n");
        sb.append("· 把这份报告发我，可以按你机器的实际返回继续收敛。\n");
        return sb.toString();
    }

    /** 是否存在「只亮上面那颗」的通道（说明单灯可行，但拿到的是广角那颗）。 */
    private boolean hasOnlyTop() {
        for (Integer v : results.values()) {
            if (v == RES_TOP) {
                return true;
            }
        }
        return false;
    }

    private void allOff() {
        if (cm == null) {
            return;
        }
        try {
            for (String id : cm.getCameraIdList()) {
                try {
                    cm.setTorchMode(id, false);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        cancelTimeout();
        allOff();
        super.onDestroy();
    }
}
