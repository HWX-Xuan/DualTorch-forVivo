package com.dualtorch.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.dualtorch.app.core.LogBus;

/**
 * 无界面的命令入口。
 *
 * 磁贴、通知按钮、桌面快捷方式、系统「打开应用」都会落到这里：
 * 收一条命令 → 转给 TorchService → 立刻消失，不打断用户正在做的事。
 * 之所以不直接在磁贴里 startForegroundService，是因为 Android 12+ 会以
 * 「后台启动前台服务」为由拒绝；经由一个 Activity 就一定放行。
 */
public class ShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String cmd = getIntent() == null ? null
                : getIntent().getStringExtra(TorchService.EXTRA_CMD);
        if (cmd == null) {
            cmd = TorchService.CMD_TOGGLE;
        }
        if (!TorchService.send(this, cmd)) {
            try {
                startService(new Intent(this, TorchService.class)
                        .setAction(TorchService.ACTION_CMD)
                        .putExtra(TorchService.EXTRA_CMD, cmd));
            } catch (Throwable t) {
                LogBus.e("兜底启动服务失败：" + t);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
