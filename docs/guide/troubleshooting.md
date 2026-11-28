# Troubleshooting

English · [Русский](troubleshooting.ru.md)

## Installing and connecting

| What you see | What to do |
|---|---|
| The server does not start and names Almighty | Check the jar matches the server's Minecraft version and loader, and the Java version ([requirements](getting-started.md#1-install-the-mod)). On Fabric, install Fabric API (0.155.3 or newer on 26.1.2, 0.167.0 or newer on 26.3). |
| The console has no "Almighty ready" line | The bridge runs only on a dedicated server, not in single-player (see `bridge.integrated`). Check `bridge.enabled` in `config/almighty-common.toml` and the log for "Almighty bridge failed to start": often port 25650 is taken, so change `bridge.port`. |
| The log says "bridge.host is … without TLS" and the bridge does not start | Without TLS the bridge listens only on loopback: set `bridge.tls_cert` and `bridge.tls_key`, keep `127.0.0.1` behind a tunnel or proxy, or, on a network only the server machine reaches, set `bridge.allow_plain_http_off_loopback = true` ([Hosting](hosting.md)). |
| The app says it cannot connect, or the connection is refused | The app runs on another machine than the server: use an SSH tunnel ([Hosting](hosting.md)). Check the address ends in `/mcp`. |
| The app or a test request gets "Not signed in. No key or token is needed" | Nothing to configure: run the command that message names (Claude Code: `claude mcp add --transport http almighty <address>`), then sign in. |
| The app says the server needs authentication | Start its sign-in (Claude Code: `/mcp` → Authenticate; Codex: `codex mcp login almighty`; opencode: `opencode mcp auth almighty`). |
| The sign-in page says "Too many logins are waiting", "Sign-ins from this address are already waiting" or "This app already has sign-ins waiting" | 16 sign-ins wait in all, or 2 from that address or app: approve or deny them (`/almighty approve` lists them), or wait 10 minutes. |
| The sign-in page says "The server owner refused this app's sign-in" | A sign-in from this app was denied: it can try again after 10 minutes, or as soon as an owner approves any sign-in. |
| `/almighty approve <code>` says "No sign-in is waiting with that code" | The code expired (10 minutes) or was typed wrong: start the sign-in again from the app. |
| In game, `/almighty approve <code>` is rejected as an unknown or incomplete command, or says "Not approved: policy.toml lists an owner (run /almighty reload)" | The server has an owner and you are not one, or you are not verified (`/almighty whoami`). Approve from the console, or have an owner add you (`/almighty owner add <name>`). |
| The page says "Unknown host" | It was opened under a name the server does not know: use `127.0.0.1`, or set `bridge.public_url`. |
| The app's tools do not appear after adding the server | Start a new session (Codex, Claude Code after `claude mcp add`). |
| A small local model gets confused | Use `?profile=compact&images=false` ([Local models](connect/local-models.md)). |

## Rights and refusals

| What you see | What to do |
|---|---|
| A call is refused with `policy`, a node and what is allowed | The agent lacks that capability for this call. Unasked it acts at its floor (a member on a friends' server); see [Permissions](permissions.md#agents-ceiling-and-floor). |
| Your own app cannot run operator commands or scripts | Approve it with `owner`, then either ask in game with `/gm` as an owner or give it `floor = "owner"` ([Your own app](permissions.md#your-own-app)). |
| A player's `/gm` gets "The game master here takes requests only from players trusted with what it can do." | The player is below the agent's floor: on `private` only owners are heard, on `friends` only verified players ([Who an agent hears](permissions.md#who-an-agent-hears)). On `private`, switch to `friends` or add the player as an owner; on `friends`, the player must be verified (`/almighty whoami`). |
| Your `/gm` requests do not carry owner rights | `/almighty whoami` must say owner and verified. Approve a sign-in in game as the first level-4 operator (only while `policy.toml` lists no owner), or type `/almighty owner add <name>` in the console. On an offline-mode server behind Velocity set `identity = "proxy"`. |
| Players' `/gm` reaches a different agent than your app | Messages go to the agent in `feed.default_agent` (`gm`) while it runs, else to your own app approved with `owner`. Stop the other agent, or players pick one with `/gm @<agent> <text>`. |
| A call is refused with `denied` | It is on the list refused for every agent because it crashes or freezes servers ([Safety](security.md#what-protects-the-server)). Run it yourself in the console if you mean it. |
| A call answers `423` | The agent is paused: `/almighty resume [agent]`. |
| A call answers `429` | A player's hourly quota is used up, the agent made more than `calls_per_minute` calls, too many confirmations or admin requests wait, or the address is locked out after wrong tokens. |
| A call answers `403` with `not_allowed_from` | The agent's `allow_from` in `policy.toml` does not list the address it called from ([Hosting](hosting.md)). |
| `policy.toml refused, the previous policy stays` | The message names the key and the mistake; fix it and `/almighty reload`. |

## While it runs

| What you see | What to do |
|---|---|
| The server lags while the agent works | Lower `work.ms_per_tick`; ask the agent to stop its jobs (`job` with `cancel`) and rules (`rule` remove). `/almighty pause` stops builds, bots, script rules and rules made below the owner at once. |
| A rule stopped working | It turned itself off: `rules` lists it with the reason (time budget, 10 errors in a row, flooding the feed). |
| A build waits and never starts | Its chunks are not loaded yet; it waits up to 2 minutes. A place on a Sable ship plot is refused. |
| Players get "The game master is away right now; your message is kept." | The agent has not read their message within `feed.away_seconds`. Keep it running in [game master mode](game-master.md). |
| Players get "The game master is paused by the server owner; your message is kept." | The agent is paused: `/almighty resume [agent]`. |

Still stuck: `/almighty agents` shows whether the agent's calls arrive, `/almighty log` shows its latest actions and
refusals, and the server log shows every game master command ("GM: /...") and every sign-in. Then open an
[issue](https://github.com/zentixua/almighty/issues/new/choose).
