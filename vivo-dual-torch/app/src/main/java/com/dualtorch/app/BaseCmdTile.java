package com.dualtorch.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.dualtorch.app.core.LogBus;
import com.dualtorch.app.core.Prefs;

/**
 * 三个磁贴的公共骨架。
 *
 * 一个 App 可以有多个磁贴，做法是每个磁贴声明一个独立的 TileService 子类。
 * 状态统一从 Prefs 读，命令统一丢给 TorchService —— 磁贴自己不碰相机。
 */
public abstract class BaseCmdTile extends TileService {

    /** 下发给 TorchService 的命令 */
    protected abstract String cmd();

    protected abstract String label();

    protected abstract String subtitle();

    protected abstract boolean active();

    protected abstract int iconRes();

    @Override
    public void onStartListening() {
        super.onStartListening();
        render();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!TorchService.send(this, cmd())) {
            // 后台启动被拒 → 用一个透明 Activity 兜底
            Intent i = new Intent(this, ShortcutActivity.class)
                    .putExtra(TorchService.EXTRA_CMD, cmd())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 9, i,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                } else {
                    startActivityAndCollapse(i);
                }
            } catch (Throwable t) {
                LogBus.e("磁贴拉起兜底页失败：" + t.getClass().getSimpleName());
            }
        }
        // 命令几乎立刻执行，稍等一下让 Prefs 落定后再刷新磁贴状态
        new Handler(getMainLooper()).postDelayed(this::render, 250);
    }

    protected void render() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        try {
            tile.setState(active() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(label());
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(subtitle());
            }
            tile.setIcon(Icon.createWithResource(this, iconRes()));
            tile.updateTile();
        } catch (Throwable t) {
            LogBus.e("磁贴刷新失败：" + t);
        }
    }

    protected String summary() {
        return Prefs.summary(this);
    }
}
