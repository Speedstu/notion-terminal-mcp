package dev.speedstu.phonemcp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.Base64;

public final class MainActivity extends Activity {
    private final SecureRandom random = new SecureRandom();

    private TextView statusView;
    private TextView urlView;
    private TextView tokenView;
    private TextView accessView;
    private TextView logView;
    private Button copyUrlButton;
    private Button copyAuthButton;

    private McpHttpServer server;
    private TunnelProcess tunnel;
    private String apiKey;
    private String publicMcpUrl;
    private boolean active;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshStorageAccess();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startEphemeralMcp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStorageAccess();
    }

    @Override
    protected void onStop() {
        stopEphemeralMcp();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopEphemeralMcp();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("Phone MCP", 26, true);
        root.addView(title);

        TextView subtitle = text("Foreground-only MCP bridge. Leaving this app stops both MCP and the public tunnel.", 14, false);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        statusView = text("Starting…", 18, true);
        root.addView(statusView);

        accessView = text("", 13, false);
        accessView.setPadding(0, dp(6), 0, dp(12));
        root.addView(accessView);

        root.addView(text("MCP URL", 13, true));
        urlView = selectable("Waiting for Cloudflare Quick Tunnel…");
        root.addView(urlView);

        copyUrlButton = button("Copy MCP URL", v -> copy("MCP URL", publicMcpUrl));
        copyUrlButton.setEnabled(false);
        root.addView(copyUrlButton);

        root.addView(spacer(10));
        root.addView(text("Authorization", 13, true));
        tokenView = selectable("Generating ephemeral token…");
        root.addView(tokenView);

        copyAuthButton = button("Copy Authorization header", v -> {
            if (!TextUtils.isEmpty(apiKey)) copy("Authorization", "Bearer " + apiKey);
        });
        copyAuthButton.setEnabled(false);
        root.addView(copyAuthButton);

        root.addView(spacer(10));
        Button storageButton = button("Grant / manage all-files access", v -> openAllFilesSettings());
        root.addView(storageButton);

        TextView storageHint = text("Optional. Without it, file tools stay inside the app sandbox. Granting storage access does not keep MCP running after the app closes.", 12, false);
        storageHint.setPadding(0, dp(4), 0, dp(12));
        root.addView(storageHint);

        root.addView(text("Live log", 13, true));
        ScrollView scroll = new ScrollView(this);
        logView = selectable("");
        logView.setTextSize(12);
        scroll.addView(logView);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        );
        root.addView(scroll, scrollParams);

        TextView footer = text("No background service • no boot receiver • no saved token • no auto-launch", 12, true);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(10), 0, 0);
        root.addView(footer);

        return root;
    }

    private void startEphemeralMcp() {
        if (active) return;
        active = true;
        publicMcpUrl = null;
        apiKey = generateToken();
        tokenView.setText("Authorization: Bearer " + apiKey);
        copyAuthButton.setEnabled(true);
        copyUrlButton.setEnabled(false);
        urlView.setText("Waiting for Cloudflare Quick Tunnel…");
        statusView.setText("Starting local MCP…");
        logView.setText("");

        server = new McpHttpServer(this, apiKey, 3000, new McpHttpServer.Listener() {
            @Override public void onLog(String message) { uiLog("MCP: " + message); }
            @Override public void onFatal(String message) {
                runOnUiThread(() -> statusView.setText("MCP error"));
                uiLog("MCP ERROR: " + message);
            }
        });

        try {
            server.start();
        } catch (Exception e) {
            active = false;
            statusView.setText("Could not start MCP");
            uiLog("MCP ERROR: " + e.getMessage());
            return;
        }

        statusView.setText("Starting public tunnel…");
        tunnel = new TunnelProcess(this, new TunnelProcess.Listener() {
            @Override public void onLog(String message) { uiLog("TUNNEL: " + message); }
            @Override public void onPublicUrl(String baseUrl) {
                runOnUiThread(() -> {
                    if (!active) return;
                    publicMcpUrl = baseUrl + "/mcp";
                    urlView.setText(publicMcpUrl);
                    copyUrlButton.setEnabled(true);
                    statusView.setText("MCP ACTIVE — keep this app open");
                });
            }
            @Override public void onFatal(String message) {
                runOnUiThread(() -> statusView.setText("Tunnel error"));
                uiLog("TUNNEL ERROR: " + message);
            }
        });
        tunnel.start();
    }

    private void stopEphemeralMcp() {
        if (!active && server == null && tunnel == null) return;
        active = false;
        if (tunnel != null) {
            tunnel.stop();
            tunnel = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        publicMcpUrl = null;
        apiKey = null;
    }

    private String generateToken() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void refreshStorageAccess() {
        boolean allFiles = android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        accessView.setText(allFiles
            ? "Storage: all-files access granted"
            : "Storage: sandbox only (all-files access not granted)");
    }

    private void openAllFilesSettings() {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "This Android version does not use all-files access.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void copy(String label, String value) {
        if (TextUtils.isEmpty(value)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + " copied", Toast.LENGTH_SHORT).show();
    }

    private void uiLog(String line) {
        runOnUiThread(() -> {
            String current = logView.getText().toString();
            if (current.length() > 12000) current = current.substring(current.length() - 9000);
            logView.setText(current + (current.isEmpty() ? "" : "\n") + line);
        });
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView selectable(String value) {
        TextView view = text(value, 13, false);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(5), 0, dp(8));
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private View spacer(int sizeDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(sizeDp)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
