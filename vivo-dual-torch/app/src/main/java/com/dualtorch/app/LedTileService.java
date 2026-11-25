package com.dualtorch.app;

import com.dualtorch.app.core.Prefs;

/** 磁贴 ②：切换灯路（广角单灯 ↔ 双灯）。点一下就知道现在点的是几颗。 */
public class LedTileService extends BaseCmdTile {

    @Override
    protected String cmd() {
        return TorchService.CMD_CYCLE_LED;
    }

    @Override
    protected String label() {
        return getString(R.string.tile_led_label);
    }

    @Override
    protected String subtitle() {
        return Prefs.focalName(Prefs.focal(this));
    }

    @Override
    protected boolean active() {
        return Prefs.lit(this) && Prefs.focal(this) != Prefs.FOCAL_WIDE;
    }

    @Override
    protected int iconRes() {
        return R.drawable.ic_tile_led;
    }
}
