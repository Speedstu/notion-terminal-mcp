# Contributing

Thanks for helping improve Notion Terminal MCP.

## Development

```powershell
npm install
Copy-Item .env.example .env
npm run check
npm run build
```

Keep restricted mode enabled while developing. Never commit `.env`, access tokens, personal filesystem paths, tunnel logs, generated output, or the downloaded `cloudflared` binary.

## Pull requests

1. Keep each change focused.
2. Explain the behavior and security impact.
3. Add or update tests when applicable.
4. Run `npm run check` and `npm run build`.
5. Document new tools and configuration variables.

Security vulnerabilities must be reported privately as described in [SECURITY.md](SECURITY.md).
