package com.dualtorch.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.dualtorch.app.core.CameraProbe;
import com.dualtorch.app.core.LogBus;
import com.dualtorch.app.core.SystemTorchEngine;

public class ProbeActivity extends Activity {

    private TextView tvDump;
    private SystemTorchEngine engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_probe);
        tvDump = findViewById(R.id.tvDump);

        try {
            engine = new SystemTorchEngine(this);
        } catch (Throwable t) {
            LogBus.e("初始化失败: " + t);
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> render());
        findViewById(R.id.btnOnAll).setOnClickListener(v -> {
            if (engine == null) {
                return;
            }
            int n = engine.setAll(true, 0);
            LogBus.i("已对 " + n + " 颗相机下发手电 ON，观察哪几颗 LED 亮起");
            Toast.makeText(this, "已点亮 " + n + " 路，请观察灯珠", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnOffAll).setOnClickListener(v -> {
            if (engine != null) {
                engine.forceOff();
                LogBus.i("已关闭所有相机手电");
            }
        });

        tvDump.setOnClickListener(v -> copy());

        render();
    }

    private void render() {
        tvDump.setText("探测中…（含 root 检测，可能需要几秒）");
        new Thread(() -> {
            final String text = CameraProbe.dump(this);
            runOnUiThread(() -> tvDump.setText(text));
        }, "probe").start();
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("probe", tvDump.getText().toString()));
        Toast.makeText(this, "探测结果已复制", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (engine != null) {
            engine.forceOff();
        }
        super.onDestroy();
    }
}
