package com.woody.gpsswitch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/** 三個一鍵動作的實際內容。 */
public final class Actions {

    public static final String REAL_OFF = "com.woody.gpsswitch.REAL_OFF"; // 關定位＋關虛擬定位
    public static final String LOC_ON = "com.woody.gpsswitch.LOC_ON";     // 開手機定位
    public static final String MOCK_ON = "com.woody.gpsswitch.MOCK_ON";   // 開虛擬定位
    public static final String TOGGLE_FLOAT = "com.woody.gpsswitch.TOGGLE_FLOAT"; // 開關懸浮按鈕

    public static final int STATE_OFF = 0;   // 定位關閉
    public static final int STATE_REAL = 1;  // 真實定位
    public static final int STATE_MOCK = 2;  // 虛擬定位

    static final String KEY_PKG = "mock_pkg";
    static final String KEY_FORCE_STOP = "force_stop";

    private static final String LOC_OFF_CMD =
            "cmd location set-location-enabled false || settings put secure location_mode 0";
    private static final String LOC_ON_CMD =
            "cmd location set-location-enabled true || settings put secure location_mode 3";

    private Actions() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static String mockPackage(Context c) {
        return prefs(c).getString(KEY_PKG, "").trim();
    }

    public static String label(Context c, String action) {
        if (REAL_OFF.equals(action)) return c.getString(R.string.long_real_off);
        if (LOC_ON.equals(action)) return c.getString(R.string.long_loc_on);
        if (MOCK_ON.equals(action)) return c.getString(R.string.long_mock_on);
        return action;
    }

    /** 執行動作，回傳給使用者看的訊息。請在背景執行緒呼叫。 */
    public static String run(Context ctx, String action) {
        String err = ShizukuShell.ensureReady(4000);
        if (err != null) return err;

        String pkg = mockPackage(ctx);
        boolean hasPkg = ShizukuShell.isValidPackageName(pkg);
        boolean forceStop = prefs(ctx).getBoolean(KEY_FORCE_STOP, true);

        List<String> cmds = new ArrayList<>();
        if (REAL_OFF.equals(action)) {
            if (hasPkg) {
                cmds.add("appops set " + pkg + " android:mock_location deny");
                if (forceStop) cmds.add("am force-stop " + pkg);
            }
            cmds.add(LOC_OFF_CMD);
        } else if (LOC_ON.equals(action)) {
            cmds.add(LOC_ON_CMD);
        } else if (MOCK_ON.equals(action)) {
            if (!hasPkg) return "還沒設定虛擬定位 App，請先打開「GPS 切換」App 設定";
            cmds.add("appops set " + pkg + " android:mock_location allow");
            cmds.add(LOC_ON_CMD);
        } else {
            return "未知的動作";
        }

        StringBuilder fail = new StringBuilder();
        for (String c : cmds) {
            ShizukuShell.Result r = ShizukuShell.exec(c);
            if (r.code != 0) {
                fail.append(c).append("\n→ ").append(r.output.trim()).append('\n');
            }
        }
        if (fail.length() > 0) return "部分指令失敗：\n" + fail;

        String msg = label(ctx, action) + "：完成";
        if (REAL_OFF.equals(action) && !hasPkg) msg += "（未設定虛擬定位 App，只關了定位）";
        return msg;
    }

    /** 目前的定位狀態。請在背景執行緒呼叫。 */
    public static int currentState(Context ctx) {
        boolean locOn;
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            locOn = lm != null && lm.isLocationEnabled();
        } else {
            locOn = Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.LOCATION_MODE, 0) != 0;
        }
        if (!locOn) return STATE_OFF;

        String pkg = mockPackage(ctx);
        if (ShizukuShell.isValidPackageName(pkg) && ShizukuShell.ensureReady(1500) == null) {
            ShizukuShell.Result r = ShizukuShell.exec("appops get " + pkg + " android:mock_location");
            if (r.code == 0 && r.output.contains("allow")) return STATE_MOCK;
        }
        return STATE_REAL;
    }

    /** 打開虛擬定位 App，方便直接選位置。 */
    public static void launchMockApp(Context ctx) {
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(mockPackage(ctx));
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(launch);
        } catch (Exception ignored) {
        }
    }
}
