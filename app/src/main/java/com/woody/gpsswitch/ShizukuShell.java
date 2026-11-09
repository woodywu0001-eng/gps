package com.woody.gpsswitch;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/** 透過 Shizuku 以 ADB（shell）權限執行指令。 */
public final class ShizukuShell {

    public static final int PERMISSION_REQUEST_CODE = 1001;

    private static final Pattern PACKAGE_NAME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    private ShizukuShell() {
    }

    public static final class Result {
        public final int code;
        public final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    public static boolean isValidPackageName(String s) {
        return s != null && PACKAGE_NAME.matcher(s).matches();
    }

    /** 等待 Shizuku 連線送達（App 剛啟動時會晚一點才收到）。不要在主執行緒呼叫。 */
    public static boolean waitForBinder(long timeoutMs) {
        if (Shizuku.pingBinder()) return true;
        CountDownLatch latch = new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener listener = latch::countDown;
        Shizuku.addBinderReceivedListenerSticky(listener);
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Shizuku.removeBinderReceivedListener(listener);
        return Shizuku.pingBinder();
    }

    /** 回傳 null 代表可以執行；否則回傳給使用者看的錯誤訊息。 */
    public static String ensureReady(long timeoutMs) {
        if (!waitForBinder(timeoutMs)) {
            return "Shizuku 沒有在執行，請先打開 Shizuku 啟動服務";
        }
        if (Shizuku.isPreV11()) {
            return "Shizuku 版本太舊，請更新 Shizuku";
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "尚未授權：請打開「GPS 切換」App，按「授權 Shizuku」";
        }
        return null;
    }

    public static Result exec(String command) {
        try {
            // Shizuku 13 把 newProcess 設為 private，這是社群普遍的呼叫方式
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null,
                    new String[]{"sh", "-c", command + " 2>&1"}, null, null);
            String out = readAll(p.getInputStream());
            int code = p.waitFor();
            p.destroy();
            return new Result(code, out);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return new Result(-1, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** 找出目前被選為「模擬位置應用程式」的 App。 */
    public static List<String> detectMockApps() {
        List<String> list = new ArrayList<>();
        Result r = exec("appops query-op android:mock_location allow");
        if (r.code != 0) return list;
        for (String line : r.output.split("\\s+")) {
            String s = line.trim();
            if (isValidPackageName(s) && !list.contains(s)) list.add(s);
        }
        return list;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}
