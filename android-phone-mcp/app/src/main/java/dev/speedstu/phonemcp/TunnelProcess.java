package dev.speedstu.phonemcp;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TunnelProcess {
    interface Listener {
        void onLog(String message);
        void onPublicUrl(String baseUrl);
        void onFatal(String message);
    }

    private static final Pattern PUBLIC_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Process process;
    private Thread readerThread;

    TunnelProcess(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        readerThread = new Thread(this::run, "phone-mcp-cloudflared");
        readerThread.start();
    }

    private void run() {
        try {
            File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
            if (!binary.isFile()) {
                listener.onFatal("Bundled cloudflared binary is missing: " + binary);
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(
                binary.getAbsolutePath(),
                "tunnel",
                "--no-autoupdate",
                "--url", "http://127.0.0.1:3000"
            );
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("HOME", context.getFilesDir().getAbsolutePath());
            env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
            env.put("SSL_CERT_DIR", "/system/etc/security/cacerts");

            process = pb.start();
            boolean announced = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (!stopped.get() && (line = reader.readLine()) != null) {
                    listener.onLog(line);
                    if (!announced) {
                        Matcher matcher = PUBLIC_URL.matcher(line);
                        if (matcher.find()) {
                            announced = true;
                            listener.onPublicUrl(matcher.group());
                        }
                    }
                }
            }

            if (!stopped.get()) {
                int code = process.waitFor();
                listener.onFatal("cloudflared exited with code " + code);
            }
        } catch (Exception e) {
            if (!stopped.get()) listener.onFatal(e.toString());
        }
    }

    void stop() {
        stopped.set(true);
        Process current = process;
        if (current != null) {
            current.destroy();
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26 && current.isAlive()) current.destroyForcibly();
            } catch (Exception ignored) { }
        }
        Thread thread = readerThread;
        if (thread != null) thread.interrupt();
    }
}
