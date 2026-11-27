# Troubleshooting

English · [Русский](troubleshooting.ru.md)

## Installing and connecting

| What you see | What to do |
|---|---|
| The server does not start and names Almighty | Check the jar matches the server's Minecraft version and loader, and the Java version ([requirements](getting-started.md#1-install-the-mod)). On Fabric, install Fabric API. |
| The console has no "Almighty ready" line | The bridge runs only on a dedicated server, not in single-player (see `bridge.integrated`). Check `bridge.enabled` in `config/almighty-common.toml` and the log for "Almighty bridge failed to start": often port 25650 is taken, so change `bridge.port`. |
| The app says it cannot connect, or the connection is refused | The app runs on another machine than the server: use an SSH tunnel ([Hosting](hosting.md)). Check the address ends in `/mcp`. |
| The app says the server needs authentication | Start its sign-in (Claude Code: `/mcp` → Authenticate; Codex: `codex mcp login almighty`; opencode: `opencode mcp auth almighty`). |
| The sign-in page says "Too many logins are waiting" | Five sign-ins already wait: approve or deny them (`/almighty approve` lists them), or wait 10 minutes. |
| `/almighty approve <code>` says "No login is waiting with code …" | The code expired (10 minutes) or was typed wrong: start the sign-in again from the app. |
| The page says "Unknown host" | It was opened under a name the server does not know: use `127.0.0.1`, or set `bridge.public_url`. |
| The app's tools do not appear after adding the server | Start a new session (Codex, Claude Code after `claude mcp add`). |
| A small local model gets confused | Use `?profile=compact&images=false` ([Local models](connect/local-models.md)). |

## Rights and refusals

| What you see | What to do |
|---|---|
| A call is refused with `policy`, a node and what is allowed | The agent lacks that capability for this call. Unasked it acts at its floor (a member on a friends' server); see [Permissions](permissions.md#agents-ceiling-and-floor). |
| Your own app cannot run operator commands or scripts | Approve it with `owner`, then either ask in game with `/gm` as an owner or give it `floor = "owner"` ([Your own app](permissions.md#your-own-app)). |
| Your `/gm` requests do not carry owner rights | Add yourself with `/almighty owner add <name>` in the console; `/almighty whoami` must say verified. On an offline-mode server behind Velocity set `identity = "proxy"`. |
| A call is refused with `denied` | It is on the list refused for every agent because it crashes or freezes servers ([Safety](security.md#what-protects-the-server)). Run it yourself in the console if you mean it. |
| A call answers `423` | The agent is paused: `/almighty resume [agent]`. |
| A call answers `429` | A player's hourly quota is used up, too many confirmations or admin requests wait, or the address is locked out after wrong tokens. |
| `policy.toml refused, the previous policy stays` | The message names the key and the mistake; fix it and `/almighty reload`. |

## While it runs

| What you see | What to do |
|---|---|
| The server lags while the agent works | Lower `work.ms_per_tick`; ask the agent to stop its jobs (`job` with `cancel`) and rules (`rule` remove). `/almighty pause` stops builds, bots and script rules at once. |
| A rule stopped working | It turned itself off: `rules` lists it with the reason (time budget, 10 errors in a row, flooding the feed). |
| A build waits and never starts | Its chunks are not loaded yet; it waits up to 2 minutes. A place on a Sable ship plot is refused. |
| Players get "The game master is away right now; your message is kept." | The agent has not read their message within `feed.away_seconds`. Keep it running in [game master mode](game-master.md). |

Still stuck: `/almighty agents` shows whether the agent's calls arrive, `/almighty log` shows its latest actions and
refusals, and the server log shows every game master command ("GM: /...") and every sign-in. Then open an
[issue](https://github.com/zentixua/almighty/issues/new/choose).
