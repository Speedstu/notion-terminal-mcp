# Notion Terminal MCP

[![MCP](https://img.shields.io/badge/MCP-Streamable_HTTP-7c3aed)](https://modelcontextprotocol.io/)
[![Node.js](https://img.shields.io/badge/Node.js-20%2B-339933?logo=node.js&logoColor=white)](https://nodejs.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Give a **Notion Custom Agent** authenticated access to a Windows terminal and filesystem through the Model Context Protocol (MCP).

No domain? No Cloudflare account? The included one-command launcher creates a temporary HTTPS URL for you.

> [!CAUTION]
> This server can execute commands and modify files on its host. Start in restricted mode, use a dedicated Windows account, keep the API key private, and enable full access only when you understand the consequences.

## Why this project?

- Native MCP Streamable HTTP endpoint at `/mcp`
- PowerShell and Command Prompt execution
- Read, write, list, inspect, move, create, and delete filesystem entries
- Constant-time Bearer token or `X-API-Key` authentication
- Restricted workspace mode by default
- Explicit opt-in full host access
- Command timeouts and configurable output/file limits
- Host-header allowlist and filesystem-root deletion protection
- Free, zero-account HTTPS quick tunnel for testing with Notion
- Windows-first setup with Node.js 20+

## Quick start

Requirements: Windows, Node.js 20 or newer, and npm.

```powershell
git clone https://github.com/Speedstu/notion-terminal-mcp.git
cd notion-terminal-mcp
npm install
.\setup.ps1
.\start-public.ps1
```

The launcher verifies the official Cloudflare binary signature, starts the MCP server, and prints:

1. The public URL to paste into Notion, such as `https://random-name.trycloudflare.com/mcp`.
2. The authentication header name: `Authorization`.
3. The value: `Bearer <your generated key>`.

Keep the PowerShell window open. A Quick Tunnel URL is temporary and changes after a restart.

## Connect it to Notion

In your Notion Custom Agent settings:

1. Open **Tools & Access**.
2. Choose **Add connection** → **Custom MCP server**.
3. Paste the `/mcp` URL printed by `start-public.ps1`.
4. Select header-based authentication.
5. Add `Authorization: Bearer <your MCP_API_KEY>`.
6. Review every exposed tool and its confirmation policy before enabling it.

Notion Custom MCP connections require a publicly reachable hosted URL and support OAuth or header-based authentication. See the [Notion documentation](https://www.notion.com/help/mcp-connections-for-custom-agents).

## Local development

```powershell
npm install
.\setup.ps1
npm run check
npm run build
npm start
```

Health check: `http://127.0.0.1:3000/health`

MCP endpoint: `http://127.0.0.1:3000/mcp`

## Tools

| Tool | Purpose |
| --- | --- |
| `terminal_execute` | Run a PowerShell or cmd command with a working directory and timeout |
| `file_read` | Read UTF-8 or base64 file data with offsets and size limits |
| `file_write` | Create, overwrite, or append UTF-8/base64 data |
| `file_list` | List a directory, optionally recursively |
| `file_stat` | Inspect file or directory metadata |
| `file_mkdir` | Create a directory and missing parents |
| `file_move` | Move or rename a filesystem entry |
| `file_delete` | Permanently delete a file or explicitly recursive directory |

## Configuration

`setup.ps1` generates an ignored `.env` file with a random 256-bit key.

| Variable | Default | Description |
| --- | --- | --- |
| `MCP_API_KEY` | generated | Bearer/API key; minimum 32 characters |
| `HOST` | `127.0.0.1` | Listening interface |
| `PORT` | `3000` | Listening port |
| `FULL_ACCESS` | `false` | Allow terminal and file tools outside `FILES_ROOT` |
| `FILES_ROOT` | `./workspace` | Allowed root in restricted mode |
| `ALLOWED_HOSTS` | localhost + Quick Tunnels | Semicolon-separated Host patterns |
| `COMMAND_TIMEOUT_MS` | `120000` | Default command timeout |
| `MAX_OUTPUT_BYTES` | `1048576` | Maximum captured stdout/stderr per stream |
| `MAX_FILE_BYTES` | `10485760` | Maximum bytes per file read/write |

To deliberately grant the server the Windows account's full permissions:

```dotenv
FULL_ACCESS=true
```

## Security model

- Requests to `/mcp` require a valid API key.
- The API key is compared in constant time and never committed by default.
- Restricted mode prevents paths outside `FILES_ROOT`.
- Full access means exactly that: commands may read secrets, install software, or destroy data accessible to the Windows account.
- Quick Tunnels are intended for development, have no uptime guarantee, and expose the endpoint to the public Internet. Authentication remains mandatory.
- Never paste an API key, GitHub token, `.env`, or tunnel logs into an issue.

Please report vulnerabilities according to [SECURITY.md](SECURITY.md), not through a public issue.

## Roadmap

- Linux and macOS shell adapters
- Fine-grained per-tool allow/deny policies
- Audit log with secret redaction
- Long-running job management
- Optional OAuth authorization server
- Automated MCP protocol compatibility tests and CI

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
