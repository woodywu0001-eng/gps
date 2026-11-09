package com.woody.gpsswitch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** 設定畫面：授權、設定虛擬定位 App、測試、把按鈕加到桌面。 */
public class MainActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView logView;
    private EditText pkgInput;

    private final Shizuku.OnBinderReceivedListener binderReceived = this::refreshStatus;
    private final Shizuku.OnBinderDeadListener binderDead = this::refreshStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (requestCode, grantResult) -> {
                refreshStatus();
                if (grantResult == PackageManager.PERMISSION_GRANTED) detectMockApp(true);
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        executor.shutdown();
        super.onDestroy();
    }

    // ---------------- 畫面 ----------------

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header("1. Shizuku 狀態"));
        statusView = new TextView(this);
        statusView.setTextSize(15);
        root.addView(statusView);
        root.addView(button("授權 Shizuku", v -> requestShizukuPermission()));

        root.addView(header("2. 虛擬定位 App"));
        root.addView(note("先在開發者選項把你的虛擬定位 App 選好一次，再按「自動偵測」。之後就不用再進開發者選項了。"));
        pkgInput = new EditText(this);
        pkgInput.setHint("套件名稱，例如 com.lexa.fakegps");
        pkgInput.setSingleLine(true);
        pkgInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pkgInput.setText(Actions.mockPackage(this));
        root.addView(pkgInput);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("自動偵測", v -> detectMockApp(false)), weight());
        row.addView(button("儲存", v -> savePackage()), weight());
        root.addView(row);

        CheckBox forceStop = new CheckBox(this);
        forceStop.setText("關閉時一併強制停止虛擬定位 App");
        forceStop.setChecked(Actions.prefs(this).getBoolean(Actions.KEY_FORCE_STOP, true));
        forceStop.setOnCheckedChangeListener((b, checked) ->
                Actions.prefs(this).edit().putBoolean(Actions.KEY_FORCE_STOP, checked).apply());
        root.addView(forceStop);

        root.addView(header("3. 測試"));
        root.addView(button("① 關閉手機定位＋虛擬定位", v -> runAction(Actions.REAL_OFF)));
        root.addView(button("② 開啟手機定位", v -> runAction(Actions.LOC_ON)));
        root.addView(button("③ 開啟虛擬定位", v -> runAction(Actions.MOCK_ON)));

        root.addView(header("4. 懸浮按鈕"));
        root.addView(note("畫面上會出現 1、2、3 三顆按鈕，按住可以拖動。1＝關定位+虛擬、2＝開手機定位、3＝開虛擬定位；有白框的是目前狀態。關閉請按通知列的「關閉懸浮按鈕」。"));
        LinearLayout floatRow = new LinearLayout(this);
        floatRow.setOrientation(LinearLayout.HORIZONTAL);
        floatRow.addView(button("開啟懸浮按鈕", v -> startFloating()), weight());
        floatRow.addView(button("關閉懸浮按鈕", v -> FloatingService.stop(this)), weight());
        root.addView(floatRow);

        root.addView(header("5. 加到桌面"));
        root.addView(note("也可以長按本 App 圖示使用捷徑，或在下拉選單「編輯」裡加入快速設定磚。"));
        root.addView(button("把 ① 加到桌面", v -> pin(Actions.REAL_OFF, R.string.label_real_off, R.drawable.sc_off)));
        root.addView(button("把 ② 加到桌面", v -> pin(Actions.LOC_ON, R.string.label_loc_on, R.drawable.sc_on)));
        root.addView(button("把 ③ 加到桌面", v -> pin(Actions.MOCK_ON, R.string.label_mock_on, R.drawable.sc_mock)));

        root.addView(header("紀錄"));
        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setAlpha(0.75f);
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void log(String s) {
        runOnUiThread(() -> logView.append(s + "\n"));
    }

    // ---------------- 動作 ----------------

    private void refreshStatus() {
        runOnUiThread(() -> {
            String s;
            if (!Shizuku.pingBinder()) {
                s = "✗ Shizuku 沒有在執行（請打開 Shizuku 啟動服務）";
            } else if (Shizuku.isPreV11()) {
                s = "✗ Shizuku 版本太舊";
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                s = "✓ Shizuku 執行中，已授權";
            } else {
                s = "△ Shizuku 執行中，尚未授權（按下方按鈕）";
            }
            statusView.setText(s);
        });
    }

    private void requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "請先打開 Shizuku 啟動服務", Toast.LENGTH_LONG).show();
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "已經授權了", Toast.LENGTH_SHORT).show();
            return;
        }
        Shizuku.requestPermission(ShizukuShell.PERMISSION_REQUEST_CODE);
    }

    private void detectMockApp(boolean silentIfNone) {
        executor.execute(() -> {
            String err = ShizukuShell.ensureReady(2000);
            if (err != null) {
                if (!silentIfNone) log(err);
                return;
            }
            List<String> apps = ShizukuShell.detectMockApps();
            if (apps.isEmpty()) {
                if (!silentIfNone) {
                    log("沒有偵測到。請先到開發者選項 → 選擇模擬位置應用程式，選好後再按一次。");
                }
                return;
            }
            String pkg = apps.get(0);
            Actions.prefs(this).edit().putString(Actions.KEY_PKG, pkg).apply();
            runOnUiThread(() -> pkgInput.setText(pkg));
            log("偵測到並已儲存：" + pkg);
        });
    }

    private void savePackage() {
        String pkg = pkgInput.getText().toString().trim();
        if (!pkg.isEmpty() && !ShizukuShell.isValidPackageName(pkg)) {
            Toast.makeText(this, "套件名稱格式不對", Toast.LENGTH_SHORT).show();
            return;
        }
        Actions.prefs(this).edit().putString(Actions.KEY_PKG, pkg).apply();
        Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show();
    }

    private void runAction(String action) {
        savePackageSilently();
        log("執行：" + Actions.label(this, action));
        executor.execute(() -> log(Actions.run(getApplicationContext(), action)));
    }

    private void savePackageSilently() {
        String pkg = pkgInput.getText().toString().trim();
        if (pkg.isEmpty() || ShizukuShell.isValidPackageName(pkg)) {
            Actions.prefs(this).edit().putString(Actions.KEY_PKG, pkg).apply();
        }
    }

    private void startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請允許「顯示在其他應用程式上層」，回來後再按一次", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // 通知用來顯示「懸浮按鈕執行中」與關閉按鈕；拒絕也能用
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
        FloatingService.start(this);
        log("已開啟懸浮按鈕");
    }

    private void pin(String action, int labelRes, int iconRes) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "這個桌面不支援加入捷徑，請改用長按 App 圖示", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, ActionActivity.class).setAction(action);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "pin_" + action)
                .setShortLabel(getString(labelRes))
                .setLongLabel(Actions.label(this, action))
                .setIcon(Icon.createWithResource(this, iconRes))
                .setIntent(intent)
                .build();
        sm.requestPinShortcut(info, null);
    }
}
