package com.woody.gpsswitch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** 桌面捷徑點下去時啟動：沒有畫面，執行完顯示提示就關掉。 */
public class ActionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String action = getIntent() != null ? getIntent().getAction() : null;
        final Context app = getApplicationContext();

        new Thread(() -> {
            final String msg = Actions.run(app, action);
            runOnUiThread(() -> {
                Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                // 開啟虛擬定位後，順便打開虛擬定位 App，方便直接選位置
                if (Actions.MOCK_ON.equals(action) && msg.endsWith("完成")) {
                    Intent launch = getPackageManager()
                            .getLaunchIntentForPackage(Actions.mockPackage(app));
                    if (launch != null) {
                        try {
                            startActivity(launch);
                        } catch (Exception ignored) {
                        }
                    }
                }
                finish();
            });
        }).start();
    }
}
