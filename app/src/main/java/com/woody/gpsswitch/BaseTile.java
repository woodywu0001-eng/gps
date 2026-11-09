package com.woody.gpsswitch;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** 下拉選單快速設定磚的共用邏輯。 */
public abstract class BaseTile extends TileService {

    protected abstract String action();

    @Override
    public void onStartListening() {
        setState(Tile.STATE_INACTIVE);
    }

    @Override
    public void onClick() {
        setState(Tile.STATE_ACTIVE);
        final String action = action();
        new Thread(() -> {
            final String msg = Actions.run(getApplicationContext(), action);
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                setState(Tile.STATE_INACTIVE);
            });
        }).start();
    }

    private void setState(int state) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(state);
        t.updateTile();
    }
}
