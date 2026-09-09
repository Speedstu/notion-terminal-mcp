# Phone MCP

Android version of the terminal/files MCP bridge.

## Behavior

- The MCP server starts when the main Activity becomes visible.
- A fresh random Bearer token is generated in RAM on every launch.
- A Cloudflare Quick Tunnel is started and the public `/mcp` URL is shown in the app.
- As soon as the Activity is no longer visible, the tunnel and MCP server are stopped.
- There is no Android Service, no boot receiver, no scheduled job, no auto-launch and no saved token.

This strict foreground-only behavior means that if ChatGPT runs on the same phone, use Android split-screen so Phone MCP remains visible. Otherwise, opening ChatGPT full-screen will stop Phone MCP by design.

## Tools

- `terminal_execute` — `/system/bin/sh` as the app UID (not root)
- `device_info`
- `file_read`
- `file_write`
- `file_list`
- `file_stat`
- `file_mkdir`
- `file_move`
- `file_delete`
- `clipboard_get`
- `clipboard_set`

By default, file tools are limited to the app's own storage. The UI has an optional button for Android's all-files-access setting; even if granted, the MCP still stops when the app leaves the foreground.

## ChatGPT / MCP connection

When the app shows `MCP ACTIVE`, copy:

1. the displayed `https://...trycloudflare.com/mcp` URL;
2. `Authorization: Bearer <ephemeral token>`.

The server is stateless Streamable HTTP JSON-RPC and implements `initialize`, `ping`, `tools/list`, `tools/call` and initialization notifications.

## Build

The GitHub Actions workflow downloads the pinned ARM64 `cloudflared` release directly from Cloudflare's official GitHub release, verifies its SHA-256, packages it as the app's executable native helper, then builds a debug-signed installable APK.

The current build targets ARM64 Android devices.
