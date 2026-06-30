package com.autotapper.app;

import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class AutoTapperTileService extends TileService {

    @Override
    public void onStartListening() {
        syncTileState();
    }

    @Override
    public void onClick() {
        GameAutomationService svc = GameAutomationService.instance;
        if (svc == null) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
            return;
        }
        svc.toggleOverlay();
    }

    private void syncTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;
        GameAutomationService svc = GameAutomationService.instance;
        if (svc == null) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel(getString(R.string.tile_label));
            tile.setSubtitle(getString(R.string.tile_subtitle_disabled));
        } else if (svc.isOverlayVisible) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(getString(R.string.tile_label));
            tile.setSubtitle(getString(R.string.tile_subtitle_on));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_label));
            tile.setSubtitle(getString(R.string.tile_subtitle_off));
        }
        tile.updateTile();
    }
}
