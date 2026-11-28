# Safety and security

English · [Русский](security.ru.md)

## What the AI is

- Almighty contains no AI model and calls no AI service. The agent is an app you run yourself (Claude Code, Codex,
  the ChatGPT desktop app, a local model app or your own script), under your own account with its maker and that
  maker's terms. Usage is billed to you by the app's maker, not by Almighty.
- Whatever the agent reads from the server goes to the model behind that app: for a cloud model, to its provider.
  That can include the world, player names, UUIDs, positions, health and inventories, public chat, commands players
  type, and `/gm` messages. Private messages between players (`/msg`, `/tell`, `/w`, `/teammsg`) are not in the
  feed, though an agent with scripts (owner rights) can read anything the server holds. A local model keeps
  everything on your machine.
- Tell your players that an AI game master is on the server and that it reads chat. Bots are marked `[bot]` in the
  tab list and chat unless you allow disguise (`bots.allow_disguise`).
- Almighty itself connects to the internet only to fetch an app's public sign-in description during sign-in (for
  example Claude Code's or Codex's, over HTTPS) and, when the agent asks for a bot with an account's skin, through the
  server's own Mojang profile lookup. It sends no telemetry.

## What an agent can do

An agent at owner rights is as powerful as a server operator of level 4. With scripts on (the default), it also runs
code in the server's Java process with the rights of the operating system user that runs the server: files,
processes, network. Treat owner access to the bridge like shell access to the server machine.

Below the owner, [the policy](permissions.md) decides: an agent acts with its floor unasked and with the requesting
player's rights on request, never above its ceiling. Commands go through vanilla's own permission checks at that
level. Scripts and rules with scripts need the owner.

## What protects the server

- **The bridge listens on loopback** (`127.0.0.1`) by default. Nothing outside the machine reaches it. When it must
  listen elsewhere, give it your certificate (`bridge.tls_cert`, `bridge.tls_key`) so it speaks only HTTPS.
- **Every app signs in with a code.** The owner types, in the console or in game as a level-4 operator, the
  four-digit code shown on the sign-in page. Approving an app with owner rights (`owner`) takes the console or an
  owner from `policy.toml`. The notice in game never shows the code, so approving something you did
  not start would mean typing a code you never saw. Waiting sign-ins expire after 10 minutes; at most 5 wait at once.
- **One identity per app.** Each approved app is its own agent with its own token: `/almighty agents` shows who
  called last and from where; `/almighty agent remove <name>` ends one at once; `/almighty revoke <name>` replaces its
  token.
- **Tokens stay secret.** They live in files only the server's owner can read (on Windows the folder's own
  permissions apply), are never printed or logged, and sign-in links carry no secret. Five wrong tokens within a
  minute lock the address out, for 5 seconds at first and up to 5 minutes.
- **Browsers cannot drive the bridge.** Requests from a web page are refused unless its origin is in
  `bridge.allowed_origins`; sign-in pages are refused under a host name the server does not know.
- **Text from players and notes is data.** Every agent is told so: chat, `/gm`, signs, books, names and notes from
  mods, datapacks or other agents are never instructions to it, and a player's request carries that player's rights,
  not the agent's.
- **Crash-prone calls are refused for every agent**, whatever its rights: `stop`, `reload`, `save-off`,
  `save-all flush`, `forceload add`, `tick rate` above 100, `/almighty`, building, loading or teleporting at Sable
  ship plot coordinates, and scripts that take chunk tickets, load chunks synchronously, start threads, timers or
  processes, register listeners, sleep or exit the JVM. You can still run such commands in the console.
- **The server keeps its tick time.** Game master work shares a fixed time per tick (`work.ms_per_tick`, 3 ms). When
  the average tick runs longer than the target tick length for 10 seconds, game master work gets a quarter of it until
  the server recovers. A rule that runs over its time budget, fails 10 times in a row or floods the feed turns itself
  off, and bots its script was driving stop.

## Undo, audit and the red button

- **Change journal.** Every block the game master writes is recorded on disk with what was there before, so a change
  can be undone even after a crash. An undo puts back only positions that still hold what the change put there;
  anything changed since stays. Edits that change only a block entity's contents (a chest's items without replacing the chest),
  entities and inventories are not recorded.
- **Receipts.** After a request with an effect, the player who asked gets a line in chat with `[Undo]`.
- **Audit log.** Every call with an effect and every refusal is a line in `logs/almighty/audit-<date>.jsonl` (kept
  30 days, `audit.days`); `/almighty log [agent | player] [count]` shows the latest, of one
  agent or made for one player.
- **Pause.** `/almighty pause [agent]`: that agent, or every agent, may only look and clean up until
  `/almighty resume`. Its builds wait and its bots stop. With a name every rule that agent made stops too; without a
  name every rule with a script and every rule made below the owner.
- **Off switches.** `scripts.enabled = false` refuses scripts and rules with a script; `bridge.enabled = false` turns
  the bridge off. Both in `config/almighty-common.toml`.

## Before opening the server to strangers

- Set `preset = "public"` in `config/almighty/policy.toml` and add yourself as an owner
  (`/almighty owner add <name>`). Check `identity` matches how players join ([verified players](permissions.md#verified-players)).
- Keep the bridge on loopback; reach it through an SSH tunnel or a reverse proxy with HTTPS ([Hosting](hosting.md)).
- Protect spawn and builds with zones, and keep quotas ([Permissions](permissions.md#zones)).
- Turn scripts off if you do not need them: `scripts.enabled = false`.
- Approve only sign-ins you started yourself, and remove agents you no longer use.

## Reporting a vulnerability

See [SECURITY.md](../../SECURITY.md).
