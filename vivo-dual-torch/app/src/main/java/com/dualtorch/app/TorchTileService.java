package com.dualtorch.app;

import com.dualtorch.app.core.Prefs;

/** 磁贴 ①：开 / 关手电。副标题显示当前灯路与亮度。 */
public class TorchTileService extends BaseCmdTile {

    @Override
    protected String cmd() {
        return TorchService.CMD_TOGGLE;
    }

    @Override
    protected String label() {
        return getString(R.string.tile_label);
    }

    @Override
    protected String subtitle() {
        return Prefs.summary(this);
    }

    @Override
    protected boolean active() {
        return Prefs.lit(this);
    }

    @Override
    protected int iconRes() {
        return R.drawable.ic_tile;
    }
}
