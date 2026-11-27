# Configuration

English · [Русский](configuration.ru.md)

## `config/almighty-common.toml`

Written with comments on first start. Edit it while the server is stopped, or restart afterwards: some settings apply
at once, but the bridge settings and the chat names are read only when the server starts.

| Key | Default | Meaning |
|---|---|---|
| `bridge.enabled` | `true` | Start the bridge (the HTTP server the agents connect to) on a dedicated server. |
| `bridge.host` | `"127.0.0.1"` | Address the bridge listens on. Keep it on loopback, or on a network only the server machine reaches, such as a Docker container address whose port is not published ([Hosting](hosting.md)). |
| `bridge.port` | `25650` | Bridge port. |
| `bridge.allowed_origins` | `[]` | Browser origins allowed to call the bridge, for example `"https://example.org"`. Requests from other web pages are refused; agents send no origin. |
| `bridge.public_url` | `""` | The public HTTPS address when a reverse proxy or tunnel puts the bridge on the internet, for example `"https://mc.example.org"`. Sign-in pages and `/almighty connect` use it. |
| `bridge.tls_cert` | `""` | Your certificate chain (PEM, for example `fullchain.pem`), relative to the server folder. With `tls_key`, the bridge speaks only HTTPS. |
| `bridge.tls_key` | `""` | The matching unencrypted PKCS#8 private key (PEM, `BEGIN PRIVATE KEY`). |
| `bridge.integrated` | `false` | Also start the bridge in single-player and LAN worlds, on loopback only. |
| `work.ms_per_tick` | `3.0` | Time per server tick for builds, undos, pictures and other world calls, in ms (0.5 to 20). When the server lags, the game master gets a quarter of it until the server recovers. |
| `work.build_max_blocks` | `2000000` | Most block positions in one build. |
| `work.max_chunks` | `1500` | Most chunks the game master keeps loaded at once for builds and prepared areas, counting a ring of neighbouring chunks. |
| `feed.lag_ms` | `1000` | Write a `lag` event when more than this many ms pass between two ticks. |
| `feed.names` | `[]` | Words that address the game master in public chat (any case; stretched letters are ignored). Empty: its `name`. |
| `feed.typo_names` | `[]` | Longer names also recognised with one typo (first letter exact). Use only names that are not one letter away from a real word. Empty: none. |
| `feed.gm_messages` | `20` | Messages one player may address to the game master per hour; more get a "try again later" reply from the server and never reach the agent. |
| `feed.default_agent` | `"gm"` | The agent that receives messages addressed to the game master. |
| `feed.away_seconds` | `30` | If the agent has not read a message after this many seconds, the server tells the player the game master is away (the message is kept). |
| `audit.days` | `30` | Days of audit log kept in `logs/almighty/`. |
| `scripts.enabled` | `true` | Groovy scripts and rules with a script. Off: both are refused, and saved rules with a script stay in the file but do not load. |
| `bots.max` | `8` | Most bots on the server at once. A bot costs the server as much as a player. |
| `bots.marker` | `"[bot]"` | Marker before a bot's name in the tab list and chat. Empty: no marker. |
| `bots.allow_disguise` | `false` | Allow a bot without the marker, or with the name or skin of a player the server knows. The bot keeps its own UUID and gives a name up when that player joins. |
| `name` | `"Claude"` | The game master's name in chat and as the source of its commands. |

## Files Almighty keeps

| Path | What |
|---|---|
| `config/almighty/policy.toml` | Who may do what ([Permissions](permissions.md)). |
| `config/almighty/policy.history/` | The previous versions of `policy.toml` (50 kept). |
| `config/almighty/token` | The token of the agent `gm`. |
| `config/almighty/agents/<name>.token` | One token per other agent. Tokens are 32 random bytes in hex; on Linux and macOS only the file's owner can read them. They are never printed or logged. |
| `config/almighty/oauth/` | Signed-in apps and their hashed refresh tokens. |
| `config/almighty/roles.json` | Roles given with `/almighty role`. |
| `config/almighty/notes/` | Your notes for every agent (`server:<name>`), Markdown. |
| `config/almighty/scripts/` | Scripts you approve to run by name; `approved.json` holds their hashes. |
| `<world>/almighty/rules.json` | Rules saved with the world. |
| `<world>/almighty/notes/` | World notes the agents write (`world:<name>`). |
| `<world>/almighty/changes/` | The change journal: every block the game master wrote, for undo. Up to 4 million positions and 1000 changes. |
| `logs/almighty/audit-<date>.jsonl` | One line per call with an effect and per refusal. |

Back up `config/almighty/` with the server; anyone who reads a token file controls the server.
