# Notion Terminal MCP

Small MCP server that gives a Notion Custom Agent access to a Windows terminal and a filesystem.

I use it as a bridge to a machine I control. By default file access stays inside `./workspace`; full host access has to be enabled explicitly.

## Setup

Requires Node 20+.

```powershell
npm install
Copy-Item .env.example .env
npm run token
```

Put the generated token in `.env` as `MCP_API_KEY`, then:

```powershell
npm run build
npm start
```

The default endpoint is `http://127.0.0.1:3000/mcp`.

For development:

```powershell
npm run dev
```

## Tools

The server exposes PowerShell/cmd execution plus basic file operations: read, write, list, stat, mkdir, move and delete.

Authentication works with a Bearer token or `x-api-key`. `ALLOWED_HOSTS`, command timeout and file/output size limits are all configurable in `.env`.

## Full access

`FULL_ACCESS=false` is the default. In that mode paths are restricted to `FILES_ROOT`.

Setting `FULL_ACCESS=true` removes that filesystem restriction, so only do that on a machine/account where you actually want the agent to have host-level access.

```powershell
npm run check
```

MIT.
