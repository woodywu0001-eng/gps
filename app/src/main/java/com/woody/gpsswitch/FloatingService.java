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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 懸浮按鈕：可拖動的圓形按鈕，點一下展開 ①②③。 */
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
    private FrameLayout bubble;
    private ImageView bubbleIcon;
    private GradientDrawable bubbleBg;
    private LinearLayout panel;
    private BroadcastReceiver locationReceiver;

    private boolean expanded;
    private boolean busy;
    private int collapsedX;
    private int collapsedY;

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
        if (root != null) main.postDelayed(() -> {
            collapse();
            snapToEdge();
        }, 300);
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

    private void showOverlay() {
        wm = getSystemService(WindowManager.class);
        int size = dp(56);

        bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(0xFF1A73E8);
        bubble = new FrameLayout(this);
        bubble.setBackground(bubbleBg);
        bubble.setElevation(dp(4));
        bubbleIcon = new ImageView(this);
        bubbleIcon.setImageResource(R.drawable.ic_tile_on);
        bubble.addView(bubbleIcon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        bubble.setOnTouchListener(new DragListener());

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        panel.addView(panelButton("① 關定位+虛擬", 0xFF5F6368, v -> doAction(Actions.REAL_OFF)));
        panel.addView(panelButton("② 開手機定位", 0xFF1A73E8, v -> doAction(Actions.LOC_ON)));
        panel.addView(panelButton("③ 開虛擬定位", 0xFF9334E6, v -> doAction(Actions.MOCK_ON)));
        panel.addView(panelButton("✕ 關閉懸浮按鈕", 0xFF333333, v -> stopSelf()));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.addView(bubble, new LinearLayout.LayoutParams(size, size));
        root.addView(panel);

        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = Actions.prefs(this).getInt(KEY_X, dm.widthPixels - size - dp(12));
        lp.y = Actions.prefs(this).getInt(KEY_Y, dm.heightPixels / 3);
        updateSideGravity();
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

    private TextView panelButton(String text, int color, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(15);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(20));
        t.setBackground(bg);
        t.setElevation(dp(3));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        t.setLayoutParams(p);
        return t;
    }

    private boolean onRightSide() {
        int w = root.getWidth() > 0 ? root.getWidth() : dp(68);
        return lp.x + w / 2 > getResources().getDisplayMetrics().widthPixels / 2;
    }

    private void updateSideGravity() {
        boolean right = lp.x > getResources().getDisplayMetrics().widthPixels / 2 - dp(34);
        root.setGravity(right ? Gravity.END : Gravity.START);
    }

    private void toggle() {
        if (expanded) collapse();
        else expand();
    }

    private void expand() {
        if (expanded || busy) return;
        expanded = true;
        collapsedX = lp.x;
        collapsedY = lp.y;
        int oldW = root.getWidth();
        boolean right = onRightSide();
        panel.setVisibility(View.VISIBLE);
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int newW = root.getMeasuredWidth();
        int newH = root.getMeasuredHeight();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (right) lp.x = collapsedX + oldW - newW;   // 靠右時往左展開
        if (lp.y + newH > dm.heightPixels - dp(48)) {   // 太靠下時往上移
            lp.y = Math.max(0, dm.heightPixels - dp(48) - newH);
        }
        wm.updateViewLayout(root, lp);
    }

    private void collapse() {
        if (!expanded) return;
        expanded = false;
        panel.setVisibility(View.GONE);
        lp.x = collapsedX;
        lp.y = collapsedY;
        wm.updateViewLayout(root, lp);
    }

    private void snapToEdge() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = root.getWidth() > 0 ? root.getWidth() : dp(68);
        int h = root.getHeight() > 0 ? root.getHeight() : dp(68);
        lp.x = (lp.x + w / 2 < dm.widthPixels / 2) ? 0 : dm.widthPixels - w;
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - h - dp(48)));
        updateSideGravity();
        wm.updateViewLayout(root, lp);
        savePosition();
    }

    private void savePosition() {
        int x = expanded ? collapsedX : lp.x;
        int y = expanded ? collapsedY : lp.y;
        Actions.prefs(this).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply();
    }

    private class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(FloatingService.this).getScaledTouchSlop();
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - downX);
                    int dy = (int) (e.getRawY() - downY);
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        if (expanded) {
                            collapse();
                            startX = lp.x;
                            startY = lp.y;
                        }
                    }
                    if (moved) {
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        wm.updateViewLayout(root, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) snapToEdge();
                    else toggle();
                    return true;
                case MotionEvent.ACTION_CANCEL:
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
        collapse();
        bubble.setAlpha(0.5f);
        executor.execute(() -> {
            final String msg = Actions.run(getApplicationContext(), action);
            final int state = Actions.currentState(getApplicationContext());
            main.post(() -> {
                busy = false;
                if (root == null) return;
                bubble.setAlpha(1f);
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

    private void applyState(int state) {
        if (state == Actions.STATE_OFF) {
            bubbleBg.setColor(0xFF5F6368);
            bubbleIcon.setImageResource(R.drawable.ic_tile_off);
        } else if (state == Actions.STATE_MOCK) {
            bubbleBg.setColor(0xFF9334E6);
            bubbleIcon.setImageResource(R.drawable.ic_tile_mock);
        } else {
            bubbleBg.setColor(0xFF1A73E8);
            bubbleIcon.setImageResource(R.drawable.ic_tile_on);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
