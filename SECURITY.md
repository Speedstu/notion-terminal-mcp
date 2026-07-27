# Security Policy

## Supported versions

Security fixes are applied to the latest release on the default branch.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting feature under the repository's **Security** tab. Do not disclose a suspected vulnerability in a public issue or discussion.

Include the affected version, reproduction steps, potential impact, and any suggested mitigation. Remove API keys, access tokens, personal paths, tunnel URLs, command output containing secrets, and other private data from the report.

## Operational warning

This project intentionally exposes command execution and filesystem operations. Running with `FULL_ACCESS=true` grants remote MCP tools the permissions of the operating-system account hosting the process. Use a dedicated least-privileged account, require authentication, and avoid exposing the service without an HTTPS gateway.
