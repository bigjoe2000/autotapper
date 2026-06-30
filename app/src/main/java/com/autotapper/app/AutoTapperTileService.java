package com.autotapper.app;

import android.content.Intent;
import android.os.Build;
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
        // Update the tile directly — requestListeningState is unreliable
        // when the QS panel is still open after the tap.
        syncTileState();
    }

    private void syncTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;
        GameAutomationService svc = GameAutomationService.instance;
        if (svc == null) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            setSubtitleCompat(tile, getString(R.string.tile_subtitle_disabled));
        } else if (svc.isOverlayVisible) {
            tile.setState(Tile.STATE_ACTIVE);
            setSubtitleCompat(tile, getString(R.string.tile_subtitle_on));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            setSubtitleCompat(tile, getString(R.string.tile_subtitle_off));
        }
        tile.updateTile();
    }

    private void setSubtitleCompat(Tile tile, String subtitle) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
        }
    }
}
