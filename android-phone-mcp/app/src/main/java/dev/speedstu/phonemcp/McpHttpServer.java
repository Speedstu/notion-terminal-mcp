package dev.speedstu.phonemcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class McpHttpServer {
    interface Listener {
        void onLog(String message);
        void onFatal(String message);
    }

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 120_000;

    private final Context context;
    private final String apiKey;
    private final int port;
    private final Listener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final ExecutorService clients = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    McpHttpServer(Context context, String apiKey, int port, Listener listener) {
        this.context = context.getApplicationContext();
        this.apiKey = apiKey;
        this.port = port;
        this.listener = listener;
    }

    void start() throws IOException {
        serverSocket = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(this::acceptLoop, "phone-mcp-http");
        acceptThread.start();
        listener.onLog("listening on http://127.0.0.1:" + port + "/mcp");
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
        Thread t = acceptThread;
        if (t != null) t.interrupt();
    }

    private void acceptLoop() {
        try {
            while (!stopped.get()) {
                Socket socket = serverSocket.accept();
                clients.execute(() -> handle(socket));
            }
        } catch (IOException e) {
            if (!stopped.get()) listener.onFatal(e.toString());
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            s.setSoTimeout(30_000);
            Request req = readRequest(in);
            if (req == null) return;

            if ("GET".equals(req.method) && "/health".equals(req.path)) {
                JSONObject health = new JSONObject()
                    .put("status", "ok")
                    .put("server", "phone-mcp")
                    .put("foreground_only", true)
                    .put("all_files", hasAllFilesAccess());
                writeJson(out, 200, health);
                return;
            }

            if ("OPTIONS".equals(req.method)) {
                writeEmpty(out, 204);
                return;
            }

            if (!"/mcp".equals(req.path)) {
                writeJson(out, 404, new JSONObject().put("error", "Not found"));
                return;
            }

            if (!authenticate(req)) {
                writeJson(out, 401, new JSONObject().put("error", "Unauthorized"),
                    Map.of("WWW-Authenticate", "Bearer realm=\"phone-mcp\""));
                return;
            }

            if (!"POST".equals(req.method)) {
                writeJson(out, 405, new JSONObject().put("error", "Use POST for stateless MCP"));
                return;
            }

            JSONObject json;
            try {
                json = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                writeJson(out, 400, rpcError(JSONObject.NULL, -32700, "Parse error"));
                return;
            }

            Object id = json.has("id") ? json.opt("id") : null;
            String method = json.optString("method", "");
            JSONObject params = json.optJSONObject("params");
            if (params == null) params = new JSONObject();

            if (id == null) {
                writeEmpty(out, 202);
                return;
            }

            JSONObject response;
            try {
                response = dispatch(id, method, params);
            } catch (Exception e) {
                response = rpcError(id, -32603, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            writeJson(out, 200, response);
        } catch (Exception e) {
            if (!stopped.get()) listener.onLog("client error: " + e.getMessage());
        }
    }

    private JSONObject dispatch(Object id, String method, JSONObject params) throws Exception {
        switch (method) {
            case "initialize": {
                String requested = params.optString("protocolVersion", "2025-06-18");
                JSONObject result = new JSONObject()
                    .put("protocolVersion", requested)
                    .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                    .put("serverInfo", new JSONObject()
                        .put("name", "phone-terminal-files")
                        .put("version", "1.0.0"));
                return rpcResult(id, result);
            }
            case "ping":
                return rpcResult(id, new JSONObject());
            case "tools/list":
                return rpcResult(id, new JSONObject().put("tools", toolDefinitions()));
            case "tools/call": {
                String name = params.optString("name", "");
                JSONObject args = params.optJSONObject("arguments");
                if (args == null) args = new JSONObject();
                return rpcResult(id, callTool(name, args));
            }
            default:
                return rpcError(id, -32601, "Method not found: " + method);
        }
    }

    private JSONArray toolDefinitions() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("terminal_execute", "Execute a shell command",
            "Execute /system/bin/sh as the Android app UID. This is not root and stops being reachable when the app leaves the foreground.",
            objectSchema(
                prop("command", stringSchema("Shell command")),
                prop("cwd", stringSchema("Optional working directory")),
                prop("timeout_ms", intSchema("Timeout in milliseconds", 1, 900000))
            ).put("required", new JSONArray().put("command"))));
        tools.put(tool("device_info", "Inspect phone", "Return Android/device and MCP storage information.", objectSchema()));
        tools.put(tool("file_read", "Read a file", "Read a file accessible to the app.",
            objectSchema(
                prop("path", stringSchema("Absolute path or path relative to the MCP root")),
                prop("encoding", enumSchema("utf8", "base64")),
                prop("offset", intSchema("Byte offset", 0, Integer.MAX_VALUE)),
                prop("length", intSchema("Maximum bytes", 1, MAX_FILE_BYTES))
            ).put("required", new JSONArray().put("path"))));
        tools.put(tool("file_write", "Write a file", "Create, overwrite, or append to an accessible file.",
            objectSchema(
                prop("path", stringSchema("Absolute path or path relative to the MCP root")),
                prop("data", stringSchema("Data to write")),
                prop("encoding", enumSchema("utf8", "base64")),
                prop("append", boolSchema("Append instead of overwrite"))
            ).put("required", new JSONArray().put("path").put("data"))));
        tools.put(tool("file_list", "List files", "List files and directories at an accessible path.",
            objectSchema(
                prop("path", stringSchema("Directory path")),
                prop("recursive", boolSchema("Recurse into subdirectories")),
                prop("max_entries", intSchema("Maximum entries", 1, 10000))
            ).put("required", new JSONArray().put("path"))));
        tools.put(tool("file_stat", "Inspect a path", "Return metadata for a file or directory.",
            objectSchema(prop("path", stringSchema("Path"))).put("required", new JSONArray().put("path"))));
        tools.put(tool("file_mkdir", "Create a directory", "Create a directory and missing parents.",
            objectSchema(prop("path", stringSchema("Directory path"))).put("required", new JSONArray().put("path"))));
        tools.put(tool("file_move", "Move or rename a path", "Move or rename an accessible file or directory.",
            objectSchema(
                prop("source", stringSchema("Source path")),
                prop("destination", stringSchema("Destination path")),
                prop("overwrite", boolSchema("Replace destination when it exists"))
            ).put("required", new JSONArray().put("source").put("destination"))));
        tools.put(tool("file_delete", "Delete a path", "Delete an accessible file or directory. Directories require recursive=true.",
            objectSchema(
                prop("path", stringSchema("Path")),
                prop("recursive", boolSchema("Allow recursive directory deletion"))
            ).put("required", new JSONArray().put("path"))));
        tools.put(tool("clipboard_get", "Read clipboard", "Read the current clipboard while this app is in the foreground.", objectSchema()));
        tools.put(tool("clipboard_set", "Write clipboard", "Replace the current clipboard text.",
            objectSchema(prop("text", stringSchema("Clipboard text"))).put("required", new JSONArray().put("text"))));
        return tools;
    }

    private JSONObject callTool(String name, JSONObject args) {
        try {
            switch (name) {
                case "terminal_execute": return textResult(executeShell(args), false);
                case "device_info": return textResult(deviceInfo(), false);
                case "file_read": return textResult(fileRead(args), false);
                case "file_write": return textResult(fileWrite(args), false);
                case "file_list": return textResult(fileList(args), false);
                case "file_stat": return textResult(fileStat(args), false);
                case "file_mkdir": return textResult(fileMkdir(args), false);
                case "file_move": return textResult(fileMove(args), false);
                case "file_delete": return textResult(fileDelete(args), false);
                case "clipboard_get": return textResult(clipboardGet(), false);
                case "clipboard_set": return textResult(clipboardSet(args), false);
                default: return textResult(new JSONObject().put("error", "Unknown tool: " + name), true);
            }
        } catch (Exception e) {
            try {
                return textResult(new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()), true);
            } catch (JSONException impossible) {
                return new JSONObject();
            }
        }
    }

    private JSONObject executeShell(JSONObject args) throws Exception {
        String command = requiredString(args, "command");
        String cwd = args.optString("cwd", "");
        int timeout = Math.min(900_000, Math.max(1, args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS)));
        File workDir = cwd.isEmpty() ? defaultRoot() : resolveTarget(cwd);
        if (!workDir.isDirectory()) throw new IOException("cwd is not a directory: " + workDir);

        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        LimitedCollector collector = new LimitedCollector(process.getInputStream(), MAX_OUTPUT_BYTES);
        Thread reader = new Thread(collector, "phone-mcp-shell-output");
        reader.start();
        boolean done = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        boolean timedOut = !done;
        if (!done) {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
        reader.join(2000);
        int exitCode = done ? process.exitValue() : -1;
        return new JSONObject()
            .put("exitCode", exitCode)
            .put("output", collector.text())
            .put("timedOut", timedOut)
            .put("truncated", collector.truncated)
            .put("cwd", workDir.getAbsolutePath())
            .put("uid_note", "Runs as the Android app UID; no root privileges are added by Phone MCP.");
    }

    private JSONObject deviceInfo() throws Exception {
        File root = defaultRoot();
        return new JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android_release", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", new JSONArray(Build.SUPPORTED_ABIS))
            .put("mcp_root", root.getAbsolutePath())
            .put("all_files_access", hasAllFilesAccess())
            .put("foreground_only", true)
            .put("server", "phone-terminal-files/1.0.0");
    }

    private JSONObject fileRead(JSONObject args) throws Exception {
        File file = resolveTarget(requiredString(args, "path"));
        if (!file.isFile()) throw new IOException("Not a file: " + file);
        String encoding = args.optString("encoding", "utf8");
        long offset = Math.max(0, args.optLong("offset", 0));
        int requested = args.has("length") ? args.optInt("length", MAX_FILE_BYTES) : MAX_FILE_BYTES;
        int length = Math.min(MAX_FILE_BYTES, Math.max(1, requested));
        long size = file.length();
        int bytesToRead = (int) Math.min(length, Math.max(0, size - offset));
        byte[] data = new byte[bytesToRead];
        int read;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(Math.min(offset, size));
            read = raf.read(data);
        }
        if (read < 0) read = 0;
        byte[] actual = data;
        if (read != data.length) {
            actual = new byte[read];
            System.arraycopy(data, 0, actual, 0, read);
        }
        String encoded = "base64".equalsIgnoreCase(encoding)
            ? Base64.getEncoder().encodeToString(actual)
            : new String(actual, StandardCharsets.UTF_8);
        return new JSONObject()
            .put("path", file.getCanonicalPath())
            .put("size", size)
            .put("offset", offset)
            .put("bytes_read", read)
            .put("truncated", offset + read < size)
            .put("encoding", encoding)
            .put("data", encoded);
    }

    private JSONObject fileWrite(JSONObject args) throws Exception {
        File file = resolveTarget(requiredString(args, "path"));
        String data = requiredStringAllowEmpty(args, "data");
        String encoding = args.optString("encoding", "utf8");
        boolean append = args.optBoolean("append", false);
        byte[] bytes = "base64".equalsIgnoreCase(encoding)
            ? Base64.getDecoder().decode(data)
            : data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Write exceeds " + MAX_FILE_BYTES + " bytes");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create parent directories");
        try (FileOutputStream out = new FileOutputStream(file, append)) {
            out.write(bytes);
        }
        return new JSONObject()
            .put("path", file.getCanonicalPath())
            .put("bytes_written", bytes.length)
            .put("appended", append);
    }

    private JSONObject fileList(JSONObject args) throws Exception {
        File root = resolveTarget(requiredString(args, "path"));
        if (!root.isDirectory()) throw new IOException("Not a directory: " + root);
        boolean recursive = args.optBoolean("recursive", false);
        int maxEntries = Math.min(10000, Math.max(1, args.optInt("max_entries", 500)));
        JSONArray entries = new JSONArray();
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && entries.length() < maxEntries) {
            File dir = queue.removeFirst();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (entries.length() >= maxEntries) break;
                File safe = resolveTarget(child.getAbsolutePath());
                JSONObject item = new JSONObject()
                    .put("path", safe.getCanonicalPath())
                    .put("type", safe.isDirectory() ? "directory" : "file");
                if (safe.isFile()) item.put("size", safe.length());
                item.put("modified_at_ms", safe.lastModified());
                entries.put(item);
                if (recursive && safe.isDirectory()) queue.addLast(safe);
            }
        }
        return new JSONObject()
            .put("root", root.getCanonicalPath())
            .put("entries", entries)
            .put("truncated", entries.length() >= maxEntries);
    }

    private JSONObject fileStat(JSONObject args) throws Exception {
        File file = resolveTarget(requiredString(args, "path"));
        if (!file.exists()) throw new IOException("Path does not exist: " + file);
        return new JSONObject()
            .put("path", file.getCanonicalPath())
            .put("type", file.isDirectory() ? "directory" : "file")
            .put("size", file.length())
            .put("modified_at_ms", file.lastModified())
            .put("readable", file.canRead())
            .put("writable", file.canWrite())
            .put("executable", file.canExecute());
    }

    private JSONObject fileMkdir(JSONObject args) throws Exception {
        File dir = resolveTarget(requiredString(args, "path"));
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create directory: " + dir);
        if (!dir.isDirectory()) throw new IOException("Path is not a directory: " + dir);
        return new JSONObject().put("path", dir.getCanonicalPath()).put("created", true);
    }

    private JSONObject fileMove(JSONObject args) throws Exception {
        File source = resolveTarget(requiredString(args, "source"));
        File destination = resolveTarget(requiredString(args, "destination"));
        boolean overwrite = args.optBoolean("overwrite", false);
        if (!source.exists()) throw new IOException("Source does not exist: " + source);
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create destination parent");
        if (destination.exists() && !overwrite) throw new IOException("Destination exists; use overwrite=true");
        if (overwrite) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source.toPath(), destination.toPath());
        }
        return new JSONObject().put("source", source.getCanonicalPath()).put("destination", destination.getCanonicalPath());
    }

    private JSONObject fileDelete(JSONObject args) throws Exception {
        File target = resolveTarget(requiredString(args, "path"));
        boolean recursive = args.optBoolean("recursive", false);
        if (!target.exists()) throw new IOException("Path does not exist: " + target);
        for (File protectedRoot : allowedRoots()) {
            if (target.getCanonicalFile().equals(protectedRoot.getCanonicalFile())) {
                throw new IOException("Refusing to delete an MCP filesystem root");
            }
        }
        if (target.isDirectory() && !recursive) throw new IOException("recursive=true is required to delete a directory");
        deleteTree(target, recursive);
        return new JSONObject().put("path", target.getCanonicalPath()).put("deleted", true);
    }

    private JSONObject clipboardGet() throws Exception {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = "";
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence value = clip.getItemAt(0).coerceToText(context);
                if (value != null) text = value.toString();
            }
        }
        return new JSONObject().put("text", text);
    }

    private JSONObject clipboardSet(JSONObject args) throws Exception {
        String text = requiredStringAllowEmpty(args, "text");
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) throw new IOException("Clipboard service unavailable");
        cm.setPrimaryClip(ClipData.newPlainText("Phone MCP", text));
        return new JSONObject().put("written", true).put("length", text.length());
    }

    private File defaultRoot() throws IOException {
        File external = context.getExternalFilesDir(null);
        File root = external != null ? external : context.getFilesDir();
        return root.getCanonicalFile();
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private List<File> allowedRoots() throws IOException {
        List<File> roots = new ArrayList<>();
        roots.add(context.getFilesDir().getCanonicalFile());
        roots.add(context.getCacheDir().getCanonicalFile());
        File external = context.getExternalFilesDir(null);
        if (external != null) roots.add(external.getCanonicalFile());
        if (hasAllFilesAccess()) {
            File shared = Environment.getExternalStorageDirectory();
            if (shared != null) roots.add(shared.getCanonicalFile());
        }
        return roots;
    }

    private File resolveTarget(String input) throws IOException {
        File candidate = new File(input);
        if (!candidate.isAbsolute()) candidate = new File(defaultRoot(), input);
        File canonical = candidate.getCanonicalFile();
        for (File root : allowedRoots()) {
            String rootPath = root.getCanonicalPath();
            String targetPath = canonical.getCanonicalPath();
            if (targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator)) return canonical;
        }
        throw new IOException("Path is outside accessible MCP roots. Grant all-files access for shared storage: " + canonical);
    }

    private void deleteTree(File target, boolean recursive) throws IOException {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null && children.length > 0 && !recursive) throw new IOException("Directory is not empty");
            if (children != null) for (File child : children) deleteTree(resolveTarget(child.getAbsolutePath()), true);
        }
        if (!target.delete()) throw new IOException("Could not delete: " + target);
    }

    private boolean authenticate(Request req) {
        String bearer = req.headers.getOrDefault("authorization", "");
        if (bearer.toLowerCase(Locale.ROOT).startsWith("bearer ")) bearer = bearer.substring(7).trim();
        String headerKey = req.headers.getOrDefault("x-api-key", "");
        return constantTimeEquals(bearer, apiKey) || constantTimeEquals(headerKey, apiKey);
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int diff = 0;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    private Request readRequest(BufferedInputStream in) throws IOException {
        String requestLine = readAsciiLine(in, 8192);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) throw new IOException("Bad HTTP request line");
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readAsciiLine(in, 16384);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        int length = 0;
        String rawLength = headers.get("content-length");
        if (rawLength != null && !rawLength.isEmpty()) {
            length = Integer.parseInt(rawLength);
            if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("Request body too large");
        }
        byte[] body = readExactly(in, length);
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return new Request(parts[0].toUpperCase(Locale.ROOT), path, headers, body);
    }

    private String readAsciiLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() < max) {
            int b = in.read();
            if (b < 0) return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.US_ASCII);
            if (b == '\n') break;
            if (b != '\r') buffer.write(b);
        }
        if (buffer.size() >= max) throw new IOException("HTTP line too long");
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n < 0) throw new IOException("Unexpected EOF");
            offset += n;
        }
        return data;
    }

    private void writeJson(OutputStream out, int status, JSONObject json) throws IOException {
        writeJson(out, status, json, Map.of());
    }

    private void writeJson(OutputStream out, int status, JSONObject json, Map<String, String> extraHeaders) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        headers.append("Content-Type: application/json; charset=utf-8\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Cache-Control: no-store\r\n");
        headers.append("Connection: close\r\n");
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            headers.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void writeEmpty(OutputStream out, int status) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + reason(status) + "\r\n" +
            "Content-Length: 0\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Error";
        }
    }

    private JSONObject rpcResult(Object id, Object result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private JSONObject rpcError(Object id, int code, String message) throws JSONException {
        return new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id == null ? JSONObject.NULL : id)
            .put("error", new JSONObject().put("code", code).put("message", message));
    }

    private JSONObject textResult(Object value, boolean isError) throws JSONException {
        String text = value instanceof String ? (String) value : String.valueOf(value);
        return new JSONObject()
            .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)))
            .put("isError", isError);
    }

    private JSONObject tool(String name, String title, String description, JSONObject inputSchema) throws JSONException {
        return new JSONObject()
            .put("name", name)
            .put("title", title)
            .put("description", description)
            .put("inputSchema", inputSchema);
    }

    private JSONObject objectSchema(Map.Entry<String, JSONObject>... props) throws JSONException {
        JSONObject properties = new JSONObject();
        for (Map.Entry<String, JSONObject> prop : props) properties.put(prop.getKey(), prop.getValue());
        return new JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false);
    }

    private Map.Entry<String, JSONObject> prop(String name, JSONObject schema) {
        return Map.entry(name, schema);
    }

    private JSONObject stringSchema(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private JSONObject boolSchema(String description) throws JSONException {
        return new JSONObject().put("type", "boolean").put("description", description);
    }

    private JSONObject intSchema(String description, int min, int max) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", description).put("minimum", min).put("maximum", max);
    }

    private JSONObject enumSchema(String... values) throws JSONException {
        JSONArray arr = new JSONArray();
        for (String value : values) arr.put(value);
        return new JSONObject().put("type", "string").put("enum", arr);
    }

    private String requiredString(JSONObject obj, String key) throws JSONException {
        if (!obj.has(key)) throw new JSONException("Missing argument: " + key);
        String value = obj.getString(key);
        if (value.trim().isEmpty()) throw new JSONException("Argument must not be empty: " + key);
        return value;
    }

    private String requiredStringAllowEmpty(JSONObject obj, String key) throws JSONException {
        if (!obj.has(key)) throw new JSONException("Missing argument: " + key);
        return obj.getString(key);
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class LimitedCollector implements Runnable {
        private final InputStream in;
        private final int limit;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        volatile boolean truncated;

        LimitedCollector(InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override public void run() {
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    int remaining = limit - out.size();
                    if (remaining <= 0) {
                        truncated = true;
                        continue;
                    }
                    int write = Math.min(remaining, n);
                    out.write(buffer, 0, write);
                    if (write < n) truncated = true;
                }
            } catch (IOException ignored) { }
        }

        String text() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
