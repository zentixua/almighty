# Scripts, CI and headless runs

English · [Русский](headless.ru.md)

Runs without a browser (`claude -p`, the Agent SDK, `codex exec`, CI, your own scripts) use an agent token: a
secret the server creates for one agent and keeps in a file only the server's owner can read.

1. In the server console: `/almighty agent add ci` → "Agent ci added; its token is in
   `config/almighty/agents/ci.token`". The token itself is never printed.
2. Put the token where the run reads it, as an environment variable (never in a config file or shell history):
   `export ALMIGHTY_TOKEN="$(cat /path/to/server/config/almighty/agents/ci.token)"`.
3. Point the client at the server with the header taken from that variable:

Claude Code (`--mcp-config` file or `.mcp.json`; Claude Code expands `${VAR}`):

```json
{"mcpServers": {"almighty": {"type": "http", "url": "http://127.0.0.1:25650/mcp", "headers": {"Authorization": "Bearer ${ALMIGHTY_TOKEN}"}}}}
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.almighty]
url = "http://127.0.0.1:25650/mcp"
bearer_token_env_var = "ALMIGHTY_TOKEN"
```

Checked: `claude -p` with only this entry read the server status, ran commands and read a map picture. A run that
already signed in with a code (on the same computer) needs no token: the stored login works headless too.

To accept the token only from the CI machine, add `allow_from = ["203.0.113.7"]` under `[agents.ci]` in
`policy.toml` and run `/almighty reload` ([Hosting](../hosting.md#approving-sign-ins-on-a-remote-server)).

`/almighty revoke ci` replaces the token (the old one stops at once); `/almighty agent remove ci` ends the agent.

The command line `uv run mcp/almighty.py call status` (and `follow` for the event feed) uses the same token, in
`ALMIGHTY_TOKEN` or `ALMIGHTY_TOKEN_FILE`. `uv run mcp/almighty.py mcp` is a stdio relay for apps that cannot speak
HTTP.
