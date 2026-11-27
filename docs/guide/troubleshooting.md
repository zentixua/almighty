# Troubleshooting

English · [Русский](troubleshooting.ru.md)

| What you see | What to do |
|---|---|
| The console has no "Almighty ready" line | The bridge runs only on a dedicated server, not in single-player. Check `bridge.enabled` in `config/almighty-common.toml` and the log for "Almighty bridge failed to start" (often the port 25650 is taken: change `bridge.port`). |
| The app says it cannot connect / connection refused | The app runs on another machine than the server: use an SSH tunnel ([Hosting](hosting.md)). Check the address ends in `/mcp`. |
| The app says the server needs authentication | Start its sign-in (Claude Code: `/mcp` → Authenticate; Codex: `codex mcp login almighty`; opencode: `opencode mcp auth almighty`). |
| The sign-in page says "Too many logins are waiting" | Five sign-ins already wait: approve or deny them (`/almighty approve` lists them), or wait 10 minutes. |
| `/almighty approve <code>` says "No login is waiting" | The code expired (10 minutes) or was typed wrong: start the sign-in again from the app. |
| The page says "Unknown host" | It was opened under a name the server does not know: use `127.0.0.1`, or set `bridge.public_url`. |
| The app's tools do not appear after adding the server | Start a new session (Codex, Claude Code after `claude mcp add`). |
| A tool answers `403` or `forbidden` | The call is refused by the server's safety list or permissions; the message says why and what is allowed. |
| The server lags while the agent works | Lower `work.ms_per_tick`; ask the agent to stop its jobs (`job` with `cancel`) and rules (`rule remove`). |
| A small local model gets confused | Use `?profile=compact&images=false` ([Local models](connect/local-models.md)). |

Still stuck: `/almighty agents` shows whether the agent's calls arrive; the server log shows every GM command
("GM: /...") and every sign-in.
