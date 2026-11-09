package com.woody.gpsswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 懸浮按鈕：三顆可拖動的圓形按鈕 1、2、3。 */
public class FloatingService extends Service {

    static final String ACTION_STOP = "com.woody.gpsswitch.STOP_FLOATING";
    private static final String CHANNEL = "floating";
    private static final String KEY_X = "float_x";
    private static final String KEY_Y = "float_y";

    static volatile boolean running;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private BroadcastReceiver locationReceiver;

    private boolean busy;

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, FloatingService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, FloatingService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground(); // 一定要先呼叫，否則系統會讓 App 當掉
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (root == null) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先允許「顯示在其他應用程式上層」", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            showOverlay();
        }
        running = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (locationReceiver != null) {
            try {
                unregisterReceiver(locationReceiver);
            } catch (Exception ignored) {
            }
        }
        if (root != null) {
            savePosition();
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root != null) main.postDelayed(this::snapToEdge, 300);
    }

    // ---------------- 前景服務通知 ----------------

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "懸浮按鈕", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FloatingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_on)
                .setContentTitle("GPS 切換懸浮按鈕執行中")
                .setContentText("點這裡開啟設定")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_tile_off),
                        "關閉懸浮按鈕", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    // ---------------- 畫面 ----------------

    private static final int COLOR_1 = 0xFF5F6368;
    private static final int COLOR_2 = 0xFF1A73E8;
    private static final int COLOR_3 = 0xFF9334E6;

    private final TextView[] buttons = new TextView[3];
    private final GradientDrawable[] buttonBgs = new GradientDrawable[3];

    private void showOverlay() {
        wm = getSystemService(WindowManager.class);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(4), dp(4), dp(4));
        addButton(0, "1", COLOR_1, Actions.REAL_OFF);
        addButton(1, "2", COLOR_2, Actions.LOC_ON);
        addButton(2, "3", COLOR_3, Actions.MOCK_ON);

        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = Actions.prefs(this).getInt(KEY_X, dm.widthPixels - dp(56));
        lp.y = Actions.prefs(this).getInt(KEY_Y, dm.heightPixels / 3);
        wm.addView(root, lp);

        locationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshState();
            }
        };
        IntentFilter f = new IntentFilter(LocationManager.MODE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(locationReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(locationReceiver, f);
        }
        refreshState();
    }

    private void addButton(int index, String text, int color, String action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        t.setBackground(bg);
        t.setElevation(dp(3));
        t.setOnTouchListener(new DragListener(action));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
        if (index > 0) p.topMargin = dp(8);
        root.addView(t, p);
        buttons[index] = t;
        buttonBgs[index] = bg;
    }

    private void snapToEdge() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = root.getWidth() > 0 ? root.getWidth() : dp(52);
        int h = root.getHeight() > 0 ? root.getHeight() : dp(172);
        lp.x = (lp.x + w / 2 < dm.widthPixels / 2) ? 0 : dm.widthPixels - w;
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - h - dp(48)));
        wm.updateViewLayout(root, lp);
        savePosition();
    }

    private void savePosition() {
        Actions.prefs(this).edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
    }

    /** 按住拖動可以移動整組按鈕；輕點就執行該按鈕的動作。 */
    private class DragListener implements View.OnTouchListener {
        private final String action;
        private final int slop = ViewConfiguration.get(FloatingService.this).getScaledTouchSlop();
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        DragListener(String action) {
            this.action = action;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    v.setScaleX(0.9f);
                    v.setScaleY(0.9f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - downX);
                    int dy = (int) (e.getRawY() - downY);
                    if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                    if (moved) {
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        wm.updateViewLayout(root, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    if (moved) snapToEdge();
                    else doAction(action);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    if (moved) snapToEdge();
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---------------- 動作與狀態 ----------------

    private void doAction(String action) {
        if (busy) return;
        busy = true;
        root.setAlpha(0.5f);
        executor.execute(() -> {
            final String msg = Actions.run(getApplicationContext(), action);
            final int state = Actions.currentState(getApplicationContext());
            main.post(() -> {
                busy = false;
                if (root == null) return;
                root.setAlpha(1f);
                applyState(state);
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                if (Actions.MOCK_ON.equals(action) && msg.endsWith("完成")) {
                    Actions.launchMockApp(getApplicationContext());
                }
            });
        });
    }

    private void refreshState() {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            final int state = Actions.currentState(getApplicationContext());
            main.post(() -> {
                if (root != null) applyState(state);
            });
        });
    }

    /** 目前狀態對應的按鈕加上白色外框：1＝定位關閉、2＝真實定位、3＝虛擬定位。 */
    private void applyState(int state) {
        int current = state == Actions.STATE_OFF ? 0 : (state == Actions.STATE_MOCK ? 2 : 1);
        for (int i = 0; i < 3; i++) {
            buttonBgs[i].setStroke(i == current ? dp(3) : 0, 0xFFFFFFFF);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
