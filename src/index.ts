import "dotenv/config";

import { spawn } from "node:child_process";
import { timingSafeEqual } from "node:crypto";
import * as fs from "node:fs/promises";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import express, { type Request, type Response, type NextFunction } from "express";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const PORT = integerEnv("PORT", 3000);
const HOST = process.env.HOST || "127.0.0.1";
const API_KEY = process.env.MCP_API_KEY || "";
const FULL_ACCESS = /^true$/i.test(process.env.FULL_ACCESS || "false");
const FILES_ROOT = path.resolve(process.env.FILES_ROOT || path.join(process.cwd(), "workspace"));
const COMMAND_TIMEOUT_MS = integerEnv("COMMAND_TIMEOUT_MS", 120_000);
const MAX_OUTPUT_BYTES = integerEnv("MAX_OUTPUT_BYTES", 1_048_576);
const MAX_FILE_BYTES = integerEnv("MAX_FILE_BYTES", 10_485_760);
const ALLOWED_HOSTS = new Set(
  (process.env.ALLOWED_HOSTS || `localhost:${PORT};127.0.0.1:${PORT}`)
    .split(";")
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean),
);

if (API_KEY.length < 32) {
  throw new Error("MCP_API_KEY must contain at least 32 characters. Generate one with: npm run token");
}

await fs.mkdir(FILES_ROOT, { recursive: true });

function integerEnv(name: string, fallback: number): number {
  const parsed = Number.parseInt(process.env[name] || "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function safeEqual(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

function authenticate(req: Request, res: Response, next: NextFunction): void {
  const bearer = req.header("authorization")?.replace(/^Bearer\s+/i, "") || "";
  const apiKey = req.header("x-api-key") || "";
  if (!safeEqual(bearer, API_KEY) && !safeEqual(apiKey, API_KEY)) {
    res.setHeader("WWW-Authenticate", 'Bearer realm="notion-terminal-mcp"');
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  next();
}

function validateHost(req: Request, res: Response, next: NextFunction): void {
  const host = (req.header("host") || "").toLowerCase();
  const allowed = ALLOWED_HOSTS.size === 0 || [...ALLOWED_HOSTS].some((pattern) => {
    if (pattern.startsWith("*.")) {
      const hostname = host.replace(/:\d+$/, "");
      return hostname.endsWith(pattern.slice(1)) && hostname.length > pattern.length - 1;
    }
    return pattern === host;
  });
  if (!allowed) {
    res.status(421).json({ error: "Host header is not allowed" });
    return;
  }
  next();
}

function resolveTarget(input: string): string {
  const target = path.resolve(input);
  if (FULL_ACCESS) return target;
  const relative = path.relative(FILES_ROOT, target);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`Path is outside FILES_ROOT (${FILES_ROOT}). Set FULL_ACCESS=true to allow it.`);
  }
  return target;
}

function textResult(value: unknown, isError = false) {
  const text = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  return { content: [{ type: "text" as const, text }], isError };
}

function errorResult(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  return textResult({ error: message }, true);
}

async function killProcessTree(childPid: number): Promise<void> {
  if (process.platform === "win32") {
    await new Promise<void>((resolve) => {
      const killer = spawn("taskkill", ["/pid", String(childPid), "/T", "/F"], { windowsHide: true });
      killer.once("close", () => resolve());
      killer.once("error", () => resolve());
    });
  } else {
    try { process.kill(-childPid, "SIGKILL"); } catch { /* already exited */ }
  }
}

async function executeCommand(command: string, shell: "powershell" | "cmd", cwd?: string, timeoutMs?: number) {
  const actualCwd = cwd ? resolveTarget(cwd) : (FULL_ACCESS ? process.cwd() : FILES_ROOT);
  const timeout = Math.min(timeoutMs || COMMAND_TIMEOUT_MS, 900_000);
  const executable = shell === "cmd" ? "cmd.exe" : "powershell.exe";
  const args = shell === "cmd"
    ? ["/d", "/s", "/c", command]
    : ["-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command];

  return await new Promise<{ exitCode: number | null; stdout: string; stderr: string; timedOut: boolean; truncated: boolean }>((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd: actualCwd,
      windowsHide: true,
      detached: process.platform !== "win32",
      env: process.env,
    });
    let stdout: Buffer<ArrayBufferLike> = Buffer.alloc(0);
    let stderr: Buffer<ArrayBufferLike> = Buffer.alloc(0);
    let truncated = false;
    let timedOut = false;

    const collect = (current: Buffer<ArrayBufferLike>, chunk: Buffer<ArrayBufferLike>): Buffer<ArrayBufferLike> => {
      if (current.length >= MAX_OUTPUT_BYTES) {
        truncated = true;
        return current;
      }
      const remaining = MAX_OUTPUT_BYTES - current.length;
      if (chunk.length > remaining) truncated = true;
      return Buffer.concat([current, chunk.subarray(0, remaining)]);
    };
    child.stdout.on("data", (chunk: Buffer) => { stdout = collect(stdout, chunk); });
    child.stderr.on("data", (chunk: Buffer) => { stderr = collect(stderr, chunk); });
    child.once("error", reject);
    const timer = setTimeout(() => {
      timedOut = true;
      void killProcessTree(child.pid!);
    }, timeout);
    child.once("close", (exitCode) => {
      clearTimeout(timer);
      resolve({
        exitCode,
        stdout: stdout.toString("utf8"),
        stderr: stderr.toString("utf8"),
        timedOut,
        truncated,
      });
    });
  });
}

function createServer(): McpServer {
  const server = new McpServer({ name: "notion-terminal-files", version: "1.0.0" });

  server.registerTool("terminal_execute", {
    title: "Execute a terminal command",
    description: "Execute a PowerShell or cmd command on the host. This has unrestricted host privileges when FULL_ACCESS=true.",
    inputSchema: {
      command: z.string().min(1).describe("The command to execute"),
      shell: z.enum(["powershell", "cmd"]).default("powershell"),
      cwd: z.string().optional().describe("Absolute working directory"),
      timeout_ms: z.number().int().positive().max(900_000).optional(),
    },
  }, async ({ command, shell, cwd, timeout_ms }) => {
    try {
      const result = await executeCommand(command, shell, cwd, timeout_ms);
      return textResult(result, result.exitCode !== 0 || result.timedOut);
    } catch (error) { return errorResult(error); }
  });

  server.registerTool("file_read", {
    title: "Read a file",
    description: "Read a text or binary file from the host filesystem.",
    inputSchema: {
      path: z.string().min(1),
      encoding: z.enum(["utf8", "base64"]).default("utf8"),
      offset: z.number().int().nonnegative().default(0),
      length: z.number().int().positive().max(MAX_FILE_BYTES).optional(),
    },
  }, async ({ path: input, encoding, offset, length }) => {
    try {
      const target = resolveTarget(input);
      const handle = await fs.open(target, "r");
      try {
        const stat = await handle.stat();
        const bytesToRead = Math.min(length || MAX_FILE_BYTES, Math.max(0, stat.size - offset), MAX_FILE_BYTES);
        const buffer = Buffer.alloc(bytesToRead);
        const { bytesRead } = await handle.read(buffer, 0, bytesToRead, offset);
        return textResult({ path: target, size: stat.size, offset, bytes_read: bytesRead, truncated: offset + bytesRead < stat.size, encoding, data: buffer.subarray(0, bytesRead).toString(encoding) });
      } finally { await handle.close(); }
    } catch (error) { return errorResult(error); }
  });

  server.registerTool("file_write", {
    title: "Write a file",
    description: "Create, overwrite, or append to a file. Parent directories are created automatically.",
    inputSchema: {
      path: z.string().min(1),
      data: z.string(),
      encoding: z.enum(["utf8", "base64"]).default("utf8"),
      append: z.boolean().default(false),
    },
  }, async ({ path: input, data, encoding, append }) => {
    try {
      const target = resolveTarget(input);
      const buffer = Buffer.from(data, encoding);
      if (buffer.length > MAX_FILE_BYTES) throw new Error(`Write exceeds MAX_FILE_BYTES (${MAX_FILE_BYTES})`);
      await fs.mkdir(path.dirname(target), { recursive: true });
      await fs.writeFile(target, buffer, { flag: append ? "a" : "w" });
      return textResult({ path: target, bytes_written: buffer.length, appended: append });
    } catch (error) { return errorResult(error); }
  });

  server.registerTool("file_list", {
    title: "List files",
    description: "List files and directories at a host path.",
    inputSchema: {
      path: z.string().min(1),
      recursive: z.boolean().default(false),
      max_entries: z.number().int().positive().max(10_000).default(500),
    },
  }, async ({ path: input, recursive, max_entries }) => {
    try {
      const root = resolveTarget(input);
      const entries: Array<{ path: string; type: string; size?: number }> = [];
      const visit = async (directory: string): Promise<void> => {
        for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
          if (entries.length >= max_entries) return;
          const fullPath = path.join(directory, entry.name);
          const item: { path: string; type: string; size?: number } = { path: fullPath, type: entry.isDirectory() ? "directory" : entry.isSymbolicLink() ? "symlink" : "file" };
          if (entry.isFile()) item.size = (await fs.stat(fullPath)).size;
          entries.push(item);
          if (recursive && entry.isDirectory()) await visit(fullPath);
        }
      };
      await visit(root);
      return textResult({ root, entries, truncated: entries.length >= max_entries });
    } catch (error) { return errorResult(error); }
  });

  server.registerTool("file_stat", {
    title: "Inspect a path",
    description: "Return metadata for a file or directory.",
    inputSchema: { path: z.string().min(1) },
  }, async ({ path: input }) => {
    try {
      const target = resolveTarget(input);
      const stat = await fs.stat(target);
      return textResult({ path: target, type: stat.isDirectory() ? "directory" : "file", size: stat.size, created_at: stat.birthtime.toISOString(), modified_at: stat.mtime.toISOString(), mode: stat.mode });
    } catch (error) { return errorResult(error); }
  });

  server.registerTool("file_mkdir", {
    title: "Create a directory",
    description: "Create a directory and any missing parents.",
    inputSchema: { path: z.string().min(1) },
  }, async ({ path: input }) => {
    try { const target = resolveTarget(input); await fs.mkdir(target, { recursive: true }); return textResult({ path: target, created: true }); }
    catch (error) { return errorResult(error); }
  });

  server.registerTool("file_move", {
    title: "Move or rename a path",
    description: "Move or rename a file or directory.",
    inputSchema: { source: z.string().min(1), destination: z.string().min(1), overwrite: z.boolean().default(false) },
  }, async ({ source, destination, overwrite }) => {
    try {
      const from = resolveTarget(source); const to = resolveTarget(destination);
      await fs.mkdir(path.dirname(to), { recursive: true });
      if (overwrite) await fs.rm(to, { recursive: true, force: true });
      await fs.rename(from, to);
      return textResult({ source: from, destination: to });
    } catch (error) { return errorResult(error); }
  });

  server.registerTool("file_delete", {
    title: "Delete a path",
    description: "Permanently delete a file or directory. Recursive directory deletion must be explicitly enabled.",
    inputSchema: { path: z.string().min(1), recursive: z.boolean().default(false) },
  }, async ({ path: input, recursive }) => {
    try {
      const target = resolveTarget(input);
      const parsed = path.parse(target);
      if (target === parsed.root) throw new Error("Refusing to delete a filesystem root");
      const stat = await fs.stat(target);
      if (stat.isDirectory() && !recursive) throw new Error("recursive=true is required to delete a directory");
      await fs.rm(target, { recursive, force: false });
      return textResult({ path: target, deleted: true });
    } catch (error) { return errorResult(error); }
  });

  return server;
}

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "2mb" }));

app.get("/health", (_req, res) => {
  res.json({ status: "ok", server: "notion-terminal-files", full_access: FULL_ACCESS });
});

app.all("/mcp", validateHost, authenticate, async (req, res) => {
  const server = createServer();
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true,
  });
  res.on("close", () => {
    void transport.close();
    void server.close();
  });
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error(new Date().toISOString(), error);
    if (!res.headersSent) res.status(500).json({ error: "Internal MCP server error" });
  }
});

app.listen(PORT, HOST, () => {
  console.log(`Notion Terminal MCP listening on http://${HOST}:${PORT}/mcp`);
  console.log(`Filesystem mode: ${FULL_ACCESS ? "FULL HOST ACCESS" : `restricted to ${FILES_ROOT}`}`);
});
