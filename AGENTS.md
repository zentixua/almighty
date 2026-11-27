# Almighty — an AI agent's bridge to a Minecraft server (`almighty`)

These rules apply to every contributor, human or agent. Read this file in full before starting.

The mod presents itself to players as "An almighty AI for your Minecraft server" in README.ru.md and
"An AI game master for your Minecraft server" in README.md, the main, English README. Text for owners and agents
(README, settings, bridge errors, tool descriptions, logs) is in English.

A server-side NeoForge mod for Minecraft 1.21.1 through which an AI agent (game master, GM) sees and acts in the
world: an HTTP bridge on the server that also serves MCP itself (`/mcp`); `mcp/almighty.py` is an optional CLI and stdio relay. No custom blocks, items, packets
or registry entries (the only custom data are the tags `almighty:driven_by_rider` (entities), `almighty:never_build`
(blocks) and `almighty:never_give` (items)); clients need not install it.
No dependencies on other mods: a mod's compatibility module (`compat/`) loads only when that mod is installed.
Public repository: https://github.com/zentixua/almighty. The bridge runs on the Open Skies modpack server
(zentixua/open-skies-friends, `side = "server"`).

## People and rules

- Author: **Artem** (GitHub zentixua, in-game ZentixUA). Communicate in Russian, briefly and to the point.
- **Languages (Artem, 26.10).** Everything in the repository is in English: code, comments, log messages, CLAUDE.md,
  `.claude/rules`, AGENTS.md, internal docs, `tools/`, CI, commits and PRs. English plus Russian only for what players
  need: the public docs (README, `docs/guide`, release notes, store pages) and the mod's interface (`en_us`/`ru_ru`).
  No mixing inside one file. Chat with Artem stays in Russian.
- Quality over speed: build, unit tests, GameTest and diff review before delivery. Merge your ready PR yourself
  after green CI and one review round by a model that did not write the code (only serious errors block).
- Public steps (GitHub releases, Modrinth) require Artem's explicit instruction.
- Use `Locale.ROOT` for numbers in game text and commands: Artem's locale is Russian; `String.format("%.1f")` gives a comma.
- End each commit message with your own agent's `Co-Authored-By:` line.

## How we work

The normal agent workflow: task → branch → code → build and tests → PR → CI → one review round → merge.

- **Plan only large changes:** a few lines in the PR body stating which existing path we use (vanilla, NeoForge,
  bridge code; a new path only if none exists, with a reason) and what agents and players will see. Do small changes directly.
- **Test invariants.** Anything expressible as a check (no synchronous chunk loads, no bot ticks without ready chunks,
  rules disabled on budget overrun) needs a unit test or GameTest. A test must support a promise in a comment, tool
  description or README; if it cannot be tested, say so beside it.
- **One review round**, by a model that did not write the code, from the diff and surrounding code, not the PR body.
  Only serious errors block (wrong behavior, crashes, a blown tick budget); everything else is optional. The author
  fixes minor issues without another round.
- **Manual checks only where CI cannot reach** (the nested Groovy jar; see Checks). Cover an escaped bug with a test
  or a rule here, not another manual step.

## Workflow

```sh
./gradlew build                          # compile (-Xlint:all, no warnings) and run unit tests
./gradlew runGameTestServer              # GameTest: headless server, only this mod
./gradlew runServer                      # trial dedicated server with the bridge (run/server)
uv run mcp/almighty.py call status       # one bridge call (address and token at the top of the script)
```

CI (GitHub Actions) checks every PR and push to `main`: GM toolkit, build, unit tests, GameTest; the jar is the
`almighty-jar` artifact. Release: bump `mod_version` (`gradle.properties`) and both `plugin/almighty` manifests (CI checks), write `docs/releases/<version>.md`, merge
into `main` and manually run `build` on `main` with `release=true`; the workflow releases `v<version>` with the jar.
open-skies-friends picks the jar up by itself (its `update` workflow, every 3 hours; run it manually to get it at once).
Immersive Aircraft and Sable are pinned there: change them in an open-skies-friends PR together with the pins here.
Modrinth and CurseForge pages use the manually triggered `publish` workflow: `draft` (hidden Modrinth draft),
`submit`, `release`; fields: `docs/store/modrinth.json`; procedure: `docs/store/listing.md`.

## Architecture (`ua.zentix.almighty`)

- `Almighty`: subscriptions; `GmServer`: state of one server run (`ServerStartedEvent` … `ServerStoppedEvent`):
  feed, jobs, chunks, bridge. The bridge runs only on a dedicated server (`isDedicatedServer`), not in singleplayer or GameTest.
- `mcp/`: the MCP endpoint (`McpEndpoint`), both protocol eras (`McpProtocol`), tool definitions and routing (`Tools`).
- `mcp/signin/`: keyless sign-in, the mod as a minimal OAuth 2.1 server on the bridge port (`/.well-known/*`,
  `/oauth/register|authorize|status|token`): PKCE S256, public clients, DCR and Client ID Metadata Documents (Codex
  `https://chatgpt.com/oauth/codex/client.json`, Claude Code `https://claude.ai/oauth/claude-code-client-metadata`;
  `ClientMetadata` fetches HTTPS only, no private addresses, 16 KB, 5 s), RFC 9207 `iss`, refresh rotation. Consent:
  the login page shows a 4-digit code; the owner types `/almighty approve <code> [name]` (console or level-4
  operator); notices never show the code. An approved client becomes an ordinary agent (its access token is the
  agent's token, `config/almighty/agents/<name>.token`); sign-in only ever rotates agents it created. State:
  `config/almighty/oauth/` (clients, hashed refresh tokens). `/almighty connect`: address, per-product lines and
  secret-free links (`mcp/Connect`). The issuer is `bridge.public_url` or the request's loopback/bridge host.
  `/almighty approve <code> <name> owner` (`OwnerApp`): the owner's own app, `[agents.<name>] ceiling = "owner"` added
  to `policy.toml` (validated, then `Policies.reload`); without it the gate's default ceiling applies (`admin`).
- TLS (`bridge/Tls`): with `bridge.tls_cert` + `bridge.tls_key` (owner's PEM chain and PKCS#8 key, JDK only) the bridge
  is HTTPS only; sign-in and `/almighty connect` then use https. A non-loopback bind without TLS logs a warning but
  still starts (plan 7.2 said refuse: that would stop setups binding a private container address, as Open Skies may).
  No self-signed generation: owners put a reverse proxy or tunnel in front, or bring a certificate.
  `bridge.integrated` starts the bridge on single-player/LAN worlds, loopback only.
- `bridge/`: Java's own HTTP (`com.sun.net.httpserver`): `POST /rpc` `{"method", "params"}` → `{"ok": true, "result"}`
  or `{"ok": false, "error": {code, message}}`. Threads: a 4-thread accept pool checks the request and hands it to a
  bounded call pool (8 threads) or long-poll pool (16 threads, methods in `Api.LONG`); a full queue (64) → 503.
  Agents (`Agents`): one bearer token per agent; `gm` is `config/almighty/token`, others
  `config/almighty/agents/<name>.token` (32 bytes in hex, permissions 600; never logged or printed), compared in
  constant time; `/almighty agent add|remove`, `revoke` (rotate), `reload`, `agents` (presence). A present `Origin`
  header must be in `bridge.allowed_origins` (403; browsers, DNS rebinding); 5 failed authentications in a minute lock
  the address out (429) for 5 s, doubling to 5 min. `Caller` (agent, address) comes from the request, never from
  parameters; every transport calls `Dispatch.invoke` (the one choke point for the gate, deny list and audit); other
  endpoints mount with `Bridge.context` and authenticate with `Bridge.authenticate`. The bridge waits 60 s for a
  method's response (stalled server thread → 504).
- `Api`: method table. Parse parameters on the bridge thread; access the world only on the server thread:
  `GmServer.onMain` (`server.execute`, between ticks) or a job (`work/`) within the tick budget.
- `work/`: job queue, processed in order at tick end (`ServerTickEvent.Post`) while time remains (`work.ms_per_tick`,
  3 ms); the first job gets at least one slice. Completion emits a `job` feed event (except snapshots). History:
  last 50 jobs, undo snapshots totaling at most 4 million positions.
- `world/Areas`: GM chunks, leased as a chunk rectangle with a one-chunk ring of neighbors.
- `building/`: plan (`BuildPlan`: fill/set/layers, no world needed for unit tests) and build/undo job (`BuildJob`).
- `view/`: top-down map (`MapView`, like a vanilla map), side/top/bottom view (`LookView`), blocks as text
  (`BlockText`), PNG without AWT (`Png`).
- `command/AlmightyCommand`: owner command `/almighty` (permission level 4): agents, `pause [agent]`/`resume [agent]`,
  `log [agent|player] [count]`.
- `audit/`: `Audit` writes one JSON line per call with effects and per refusal (`Dispatch` listener) to
  `logs/almighty/audit-<date>.jsonl` on its own thread (never the server thread), keeps the last 1000 in memory
  (`/almighty log`, `audit {limit, agent?, method?}`), deletes files older than `audit.days` (30); successful reads
  are not logged. GameTest keeps it in memory. A plain writer instead of a log4j2 appender: configuring an appender
  changes the server's global logging, which panels and other mods own. Parameters are clipped to 4 KB on the calling
  thread (`Dispatch.clipped`); the writer uses a `FileOutputStream` (interrupts cannot close it), stops on an end
  marker, and reopens after an IO error. `Pause`: a paused agent (or all) gets 423 for calls with effects except
  `cancel`, `area.release`, `rule.remove`, `bot.remove` (cleanup); its builds and undos freeze in the queue
  (`Job.effects`, `Jobs.paused`; views and area preparation go on), its bots stop and get no new programs
  (`Bot.controller`); `pause` of all also skips rules with a script.
  `Dispatch.current()` is the caller of the running call; `GmServer.onMain` carries it to the server thread, so jobs
  (`Job.agent`) and bot programs know which agent acts; the future completes inside the caller's scope, so
  continuations on the server thread act as the same caller.
- `policy/` gate (step 5): `config/almighty/policy.toml` (`Policy`, `Policies`: written with comments on first start,
  `/almighty reload`, a bad file keeps the last good policy with the exact error; at startup, with no last good one,
  `Policy.lockdown()`: public, no owners): `preset` (private | friends |
  public), `owners` (UUIDs), `identity`, `[agents.<name>]` `ceiling`/`floor`. `Node`: capabilities `almighty.<id>`
  registered with the NeoForge permission API (`Nodes`; LuckPerms decides when installed; default resolver = role
  ladder `Role`: policy owners (verified) → owner, op level ≥ 3 → admin, 2 → builder, verified → member on friends
  and guest otherwise, unverified → guest whatever a plugin says). `Gate` (a `Dispatch` check after the deny list
  and pause): every method maps to a node (`Gate.node`, parameters refine it: `see` a player, `rule.add` with
  script, creative bots, payloads, `notes` with text); none → refused. Rights: no `for` → the agent's trust floor
  (private: = ceiling; friends: member; public: guest; or `floor`); `for: <ticket>` → that player's rights, capped
  by the ceiling (a ticket only narrows). `say` needs `speak.all` unless it is a chat/actionbar reply to the ticket's
  player alone; `entities` needs `observe.players` unless it asks for a non-player type; `undo`/`cancel` of another
  agent's job need `undo.any` (`Gate.require`). Below owner, `say` components lose `run_command`/`suggest_command`
  clicks (JSON-level, translation arguments included) and may not contain `[Almighty]`. Parameters are read
  null-safe (null = absent, as `Args`) and a malformed one is 400. The gate never reads server lists off the server
  thread: bot names (`Bots.isBotName`), ops and online players (`Nodes.refresh`, once a second) are snapshots.
  `Identity`: in `auto`/`mojang` nothing is verified when `online-mode=false` (use `proxy` behind Velocity).
  Refusal 403 `policy` with `node` and `allowed`; `status` gives `rights` (with `for`: that request's). `command`/`function` need owner until step 6 runs them as the requester.
  In-process calls (`Caller.INTERNAL`: GameTest, the server) are not gated. Preset `private` with `gm` at ceiling
  owner is today's behaviour; unlisted agents default to ceiling admin. `/almighty whoami` (anyone) shows UUID,
  verification and role.
- Origins and confirmations (step 6): `command`/`function` run as `GmServer.commandAs(rights)`: in-process level 4;
  unasked at the floor's level (`Role.commandLevel`: owner 4, admin 3, builder 2, else 0); for a request as that
  player's own source (`withSource` keeps output captured and the feed quiet) at max(op level, role level) capped by
  the ceiling; the requester must be online. So node `command` is guest and vanilla's `requires` decide.
  `Caller.requester` (from a valid `for`, set in `Dispatch`) travels with the call: jobs record `requester`, and a
  receipt ("[Almighty] Done for you: … [Undo]") reaches that player after any call with effects; `/almighty undo
  <job>` works for that player or level 4. `policy/Confirmations` + `Decisions` + `Prompts`: a request's command
  waits for that player's confirmation unless every command in it is in the allowlist `SAFE` (time, weather, say,
  msg, setblock, fill, clone, place …) or `SELF_ONLY` (tp, give, effect, clear, gamemode, kill …) aimed only at
  the requester (`@s`, their name or UUID; no other selector, name, UUID or `execute on`); everything else (any
  mod command, data, summon, function, schedule, ban …) is confirmed. Function macros are expanded before the
  check and in the prompt. Also any kind in `[safety] confirm_first`. → 202 `pending` with an id; the server asks
  that player (the exact call, `[Allow]`/`[Deny]` and the typed `/almighty confirm <nonce>`; never clipped: a call
  too long to show is refused); only that player may answer; at most 3 waiting per player and 20 per agent;
  2 minutes. A request above the player's rights whose kind is in `[safety] ask_admin` → 202 for an admin (for
  commands: when the player's own source cannot parse it; the decider needs `almighty.approve`): merged with an
  identical waiting one, 1 per player per 10 minutes, 20 per hour, one digest line at most every 10 minutes,
  `/almighty inbox`; it runs as the requester with the admin's rights for that one call (`Pending.decider`; no
  ticket is minted). Owners' requests apply at once. Below owner, commands may not carry
  `run_command`/`suggest_command` or `[Almighty]` (forged prompts via tellraw, signs, books). `/gm` and
  `/almighty confirm|deny` accept only the player's own typing (`source.source == player`); `/gm` is on the deny
  list for GM commands (a GM bot's own `/gm` chat is allowed); `/almighty …` stays out of the feed. The agent
  polls `pending {id, wait?}`; every state change is a `confirmation` feed event. 5 policy refusals of one player's
  requests in 10 minutes cool
  their `/gm` down for 10 minutes (`Inbox.refused`).
- Presets, zones, roles, quotas, claims (step 9): a fresh install writes `policy.toml` with `preset = "friends"`
  (decision D1); an existing install (its token predates the start) and GameTest get `private`, so Open Skies keeps
  today's behaviour. `[zones.<name>] dim, from, to, deny, agent_may` (`Policy.Zone`): below owner, a zone meeting a
  call's box and denying its node refuses it (`Gate.zone`, called by `build` with the plan's bounds, `area.prepare`,
  `bot.spawn`, and commands at their source position); unasked above the floor, `build`/`area.prepare`/`bot.spawn`
  pass the gate only with an internal marker (`Gate.ZONE_ONLY`, dropped from incoming parameters) and must lie
  wholly inside a zone whose `agent_may` lists the node. `[quotas] build_blocks, give_items` per player per hour
  below owner (`policy/Quotas`; 429). Role ladder without a plugin: `/almighty role <player> <role|clear>
  [duration]` (admins and the console, only below their own role; never owner; `config/almighty/roles.json`,
  `policy/Roles`); `/almighty owner add|remove <player>` (level 4) edits `owners` in place, validated
  (`policy/PolicyFile`). `Nodes.handler` stands in for a permission handler in GameTest (the LuckPerms path).
  Claims (`world/Claims`, `compat/opac/OpacClaims`, `compat/ftbchunks/FtbChunksClaims`, compile only, loaded only
  when installed): below owner a build runs only on chunks the requester may build in (unasked: unclaimed only),
  checked on the server thread through each mod's own read-only API.
- Content limits (step 7, `policy/ContentLimits`): below owner (the call's effective role), `build` takes block
  states only (any NBT → 403) and never a block in `#almighty:never_build` (command, structure, jigsaw blocks,
  spawners, barrier, light, bedrock, portals, end portal frame, reinforced deepslate); `give {item, count ≤ 64,
  for}` gives the requester a plain item (no components, at most one stack) and never one in `#almighty:never_give`
  nor a spawn egg (any `SpawnEggItem`), within 128 items per player per hour (`policy/Quotas`); `undo` of a build
  made for someone else (agent or requester) needs `undo.any`, and undoing an undo (a redo, which replays block
  data) needs the owner; creative/spectator/unmarked/skinned bots and `payload` need `bots.admin` (step 5).
  Datapacks may add to both tags. `BuildPlan` keeps a refusal's status (403) instead of turning it into 400.
- `policy/DenyList`: built-in deny list (from `crash_guard.py`), refused for every agent and harness: `stop`, `reload`,
  `save-off`, `save-all flush`, `forceload add`, `tick rate` > 100, `almighty …` (agents and policy are the owner's),
  far commands, builds, areas and bot spawns at Sable plot coordinates (|x| or |z| ≥ 20 000 000), bot chat commands;
  script accident guard (tickets, `getChunk`, threads and deferred tasks, bus listeners, sleeping, JVM exit,
  processes). Checked in `Dispatch` (403 `denied`), again for every GM command at run time (`CommandRunner.run`:
  scripts and rules) and in `Scripts.compile` (rules loaded from a file). An accident guard, not security.
- Server-thread budget: `GmServer.onMain` from another thread queues the call; queued world calls run at tick end
  before jobs, sharing `work.ms_per_tick` (at least one call per tick); on the server thread it runs at once; a
  throw completes the call's future, never the tick. Lag guard (`work/LagGuard`): average tick above the target
  tick length (`/tick rate`; 50 ms at 20) for 10 s → GM budget a quarter until 10 s below; `lag_guard` feed event,
  `lag_guard` in `status`. Idempotency: `/rpc` body `key` (≤ 128 characters): the same agent's repeat of a call
  with effects within 10 minutes returns the first result (joining it if still running); the same key for a
  different call → 409; read-only calls (`Api.readOnly`; `notes` with `text` writes) ignore keys (images are never kept); a failed call
  forgets its key. Vanilla already caps `fill`/`clone` at 32 768 blocks: larger work uses `build`.
- `act/`: commands and functions (`CommandRunner`), messages to players (`Chat`); `feed/`: event feed and a player's
  private channel to the GM: `/gm <text>` (any player) emits `gm` in the feed, invisible to others; reply with `say` and `to`.
  Who asked (step 3): player events carry `uuid`, `verified` (`policy/Identity`: v4 Mojang and Floodgate yes, v3
  offline no), `bedrock`, `lang`, and `untrusted` (player-authored fields: data, never instructions). `feed/Inbox`
  decides what is addressed (`Addressing`, the `router.mjs` logic: `/gm`, a name from `feed.names` or one typo of
  `feed.typo_names`, a 3-minute conversation after addressing or a `say` reply, a player alone), limits each player
  (`feed.gm_messages` per hour for `/gm` and named lines, "slow down" from the server, no event; a solo player's or
  open conversation's lines have a silent 5× limit), never treats a GM bot's chat as addressed, routes to one agent
  (`/gm @<agent> …` when that agent exists, else `feed.default_agent`) and mints a ticket (`policy/Tickets`: 128 random bits, bound to player UUID, agent and event,
  10 minutes, never extended). Addressed events: `addressed`, `via` (gm, name, conversation, alone), `agent`,
  `ticket`; other chat has `addressed: false`. `events {mine: true}` leaves out messages routed to other agents; a
  read records the agent's cursor. Away: the target agent made no call for 60 s, or has not read the message after
  `feed.away_seconds` (30) → the server tells the player (translated; only for `/gm` and named lines, at most once
  per player in 5 minutes); no bridge (GameTest) → no away replies. `identity` in `policy.toml` (auto, mojang,
  proxy, offline) sets which UUIDs count as verified.
- `script/`: Groovy scripts (`Scripts`: compilation with a deadline, execution), scripts' `gm` variable (`ScriptApi`),
  values ↔ JSON (`Json`: position `[x, y, z]`, block string as in `/setblock`, NBT as SNBT, event via its `getX()`).
- `rules/`: rules (`Rules`), subscribing to any game-bus event, budgets, persistence with the world
  (`<world>/almighty/rules.json`); `EventTypes`: all NeoForge and mod events from FML scan data.
- `bot/`: player bots (`Bots`: login, logout, markers, name protection; `Bot`: connection, packet reception, inbox,
  hearing; `BotPlayer`: bot player class; `Controls`: client keyboard and mouse; `Vehicles`: the bot's vehicle;
  `VehicleDriver`: mod vehicle driver; `Steps`/`Program`: `bot.act` steps; `Eye`: vision without an image).
  `view/EyeView`: first-person image; `world/Rays`: rays through ready chunks. Bots know nothing about other mods;
  the mod id is not in bot data (UUID: `AgentBot:<name>`).
- `compat/`: mod compatibility only for what common paths (client packets, player input, tags, notes) cannot do.
  `Compat` selects modules using `ModList`; a mod's module is `compat/<mod>`, the mod is `compileOnly`, and the module
  class loads only when the mod is installed. Modules: `immersiveaircraft/ImmersiveAircraftDriver` drives IA aircraft
  (the pilot's client drives them with its own code); `sable/SableShips` exposes Sable ships (`world/Ship`,
  `world/Ships`: position, rotation, velocity, plot, block entities) through sable-companion, Sable's public library
  (MIT, shipped by Sable itself; `compileOnly` here). Do not touch Sable internals: no API guarantees, and some classes
  do not load on a dedicated server (signatures with `ClientLevel`; Groovy cannot see them either; scripts access
  ships only through `gm.ship`).
- `mixin/PlayerListMixin`: respawning a bot with its own class (see Bots and vision for why). `mixin/LevelChunkMixin`:
  the change journal's hook on `LevelChunk.setBlockState` (no event reports every block write); records only
  while GM work runs on that thread (`Journal.current`), otherwise one volatile read.
- `audit/Journal` (step 8): every call with effects opens a change (`Dispatch.Around`); its writes on the server
  thread (world calls via `GmServer.onMain`, and its jobs, which retain the change) are appended to
  `<world>/almighty/changes/<id>.bin` (header, block-state palette, position, state before and after, block entity
  data before), flushed every tick, so a crash mid-build leaves an undoable change (`index.json`, rebuilt from file
  headers after a crash: `interrupted`). Kept up to 4 million positions, oldest dropped. `undo {change}` /
  `/almighty undo <change>` revert in reverse write order, conflict-aware: a position goes back only if it still
  holds what the change put there; the rest are `conflicts` in the job (`BuildJob.revert`). Another agent's or
  requester's change needs `undo.any`; reverting an undo (a redo) needs the owner. `changes {limit?}` lists them;
  receipts and audit lines carry the change id. Block-entity-only edits (container contents), entities and
  inventories are not recorded. Writes are recorded at the start of `setBlockState` (true order; a block that
  rewrites itself while placed, such as redstone wire, reverts exactly). The index is saved at most every 5 s
  (tick end) and keeps at most 1000 changes; a change still running cannot be reverted; a revert is split by
  region (128×128 blocks per job, so spread-out changes stay within the chunk budget), all or nothing, and the
  change is marked undone only when every piece finished. A block whose mod is gone refuses the revert instead of
  writing air. Caveat: work the server thread runs inside a GM call (a script that loads a chunk drains queued
  tasks, player packets included) is recorded into that call's change; scripts must not load chunks (deny list).
- Policy edits by asking (`policy/ConfigFiles`, `PolicyEdits`): `file` reads and writes only `policy.toml` and
  `scripts/<name>.groovy`. Friction follows origin: the owner (the console, an internal caller, or a request
  whose player is an owner) applies at once; a player's request is refused (403); the agent's own initiative, on
  any preset (private too, where its floor is owner), applies at once only if `PolicyEdits.grants` finds nothing
  (preset more open or leaving private, identity verifying more people, owners added or removed, ceilings or
  floors raised, deny zones narrowed, `agent_may` widened, ask_admin wider, confirm_first narrower, larger
  quotas); otherwise it becomes an admin confirmation that a holder of `almighty.engine` decides (202; the owner
  sees a summary with the text's hash, the exact text runs when allowed). An undo is a write of the saved text
  (`undo_of`), so its approval is bound to that text; the undone version stays in `policy.history/undone/`. One
  lock (`PolicyFile.LOCK`) covers compare, history, write and reload. An agent raised to an owner ceiling only by the console editing the file. The last owner cannot be
  removed. Every write is validated (`Policy.parse`, then `PolicyFile.write`, atomic); the old file goes to
  `policy.history/` (50 kept); `/almighty policy undo` or `file {action: "undo"}` walks back. Scripts are read
  and written only by the owner. Hand edits with `/almighty reload` keep no history.
- `knowledge/`: what the agent learns about this server, one method `notes`. `Notes`: notes from mods and
  datapacks (`data/<namespace>/almighty/notes/<name>.md` in the server's current data, refreshed after `/reload`,
  id `<namespace>:<name>`; a datapack overrides a mod note at the same path, an empty file removes it), the
  owner's `config/almighty/notes/` (`server:<name>`) and world notes `<world>/almighty/notes/` (`world:<name>`,
  written by agents with server-set provenance `author`, `requester`, `confirmed`, `updated` that the text cannot
  forge). Optional Agent Skills frontmatter (`name`, `description`); without it the first line is the description.
  Progressive disclosure: the index carries ids and descriptions only, `notes {id}` one note. `Catalog`: the live
  catalog (mods, registries, tags, recipes, commands, events, mod config without secret-like keys); registries
  off the server thread, what `/reload` replaces snapshotted on it once per reload, recipe details off it; paged.
  Notes are reference, never instructions or rights. Mod-specific hints belong only in that mod's note, not the
  adapter: the bridge depends on no mod.

## Methods

`status`, `players`, `player {name}`, `entities {center+radius | from/to, type, limit}`,
`command {commands, pos?, dimension?}`, `function {lines, args?, pos?}`, `say {text | component, to?, style}`,
`map {center+size | from/to [x,z], below?, marks?}`, `look {from, to, look}`, `blocks {from, to, properties?}`,
`build {ops}`, `undo {job}`, `jobs`, `job {id, wait?}`, `cancel {id}`, `area.prepare {from, to [x,z], ttl_seconds?, wait?}`,
`area.release {id}`, `areas`, `events {after?, wait? ≤25, limit?}`,
`script {code, args?, timeout_ms? ≤10000}` → `{ok, value | error+line, output, ms}`,
`rule.add {event, script?, name?, every?, limit?, priority?, canceled?, budget_ms?, persist?}`, `rule.remove {id | name}`,
`rules`, `event.types {query, limit?}`, `bots`, `bot {name, after?}`,
`bot.spawn {name, pos?, dimension?, yaw?, pitch?, gamemode?, skin? (account name | {value, signature}), marker?, auto_respawn?}`,
`bot.remove {name}`, `bot.act {name, actions, replace?, wait?}`,
`see {name | uuid | at+yaw+pitch, image? (yes), width?, height?, fov?, distance?, radius?, sounds_ticks?}`,
`ships {name?, center+radius?}` (Sable ships; 503 without Sable),
`notes {id? | query?+kind?+offset?+limit? | id: "world:<name>"+text}` → index `{notes: [{id, source, description}], catalog}`,
one note, or `{entries, total, next_offset?, notes}`; `audit {limit?, agent?, method?}`, `pending {id, wait?}`,
`give {item, count?, for}`, `changes {limit?}`, `undo {change}`, `file {action: list|read|write|undo, path?, text?}`. Any call may carry `for` (the ticket of the player's message it answers) and, on `/rpc`,
a top-level idempotency `key`.

All accept `dimension` (Overworld by default); long operations accept `wait` (default 30 s, up to 50): if the job
has not finished, return its description and continue with `job`. A snapshot image is returned once (`png_base64`);
the job then releases it. The 14 MCP tools (`assets/almighty/mcp/tools.json`, routed by `mcp/Tools`) follow Artem's principle, "few levers, each reaching
all the way down": `status` (+ player), `entities`, `command` (commands or function lines), `say`, `script`, `rule`
(add/remove/list/types), `events`, `view` (map/look/blocks/eye), `build` (+ undo), `area`, `job` (+ cancel), `bot`
(spawn/remove/list/state/act), `notes`, `call` (any method). Before adding something, consider a script or rule;
add a separate tool only if it really prevents errors. `from`/`to` are `start`/`end` there.

Plugin `plugin/almighty/` (README; marketplaces `.claude-plugin/marketplace.json`, `.agents/plugins/marketplace.json`):
GM skills in Claude and Agent Plugins formats, generated from the jar's notes `data/almighty/almighty/notes/`
(`scripts/export_skills.py`, CI `--check`); edit the notes, not `skills/`. Alongside it, `mcp/gm/` (README): the GM as one Claude Code session, with the `almighty-chat/` chat channel (Node
plugin), `crash_guard.py` crash-prevention hook (PreToolUse; the bridge now enforces the same list for every harness,
`policy/DenyList`; keep both in step until the hook retires), and `recipes.py` recipe runner. Tests:
`python3 -m unittest discover -s mcp/gm/tests` and `node --test` in `mcp/gm/almighty-chat/`.

Scripts and rules run Groovy on the server thread. Variables: `server`, `gm` (`command`, `commandAs(entity, …)`
as an entity, `emit(data)` as an `emit` feed event, `fields(event)`, `player(name)`, `level(dim)`, `bot(name)` with
`act(steps[, replace])`, `send(packet)`, `ship(name)`/`ships()` for Sable ships), `state` (shared map until server
stop), plus `args` for scripts, `event` and `rule` for rules. Common game classes are imported (`Scripts.IMPORTS`).
A rule without a script writes event properties to the feed; a rule script's return value is ignored; only
`gm.emit` writes to the feed.

A rule disables itself (`rule.off` with a reason; stays in `rules`) on: average time above `budget_ms` per tick
over the last 20 ticks; one run over 20×`budget_ms` (at least 50 ms; the first such abort emits only `rule.error`);
10 consecutive errors (`rule.error` at most once a second); over 200 feed entries in 20 ticks; an event off the
server thread. Disabling stops bots its script controlled (`act`, `send`) at the start of their next tick:
programs removed, keys released (`bot_stopped`, `bots_stopped` in `rule.off`). Otherwise a disabled autopilot's
controls would stay held (nose-down control → aircraft into the sea). `limit` removes the rule permanently
(does not stop bots: a planned end). Emergency switch: `scripts.enabled` in `config/almighty-common.toml`.
A persisted rule that could not load at startup (scripts disabled, event missing) stays in `rules` as `unloaded`
and in the file until removed by name; move an unreadable rules file aside (`rules.json.bad`).

## Pitfalls

- Load chunks only with vanilla radius-0 region tickets (`ServerChunkCache.addRegionTicket`, level 33 = full chunk
  without ticking; `TickingTracker` level 33 does not tick either). Radius above 0 starts ticking immediately,
  before neighbors are ready; NeoForge `TicketController.forceChunk` and `level.getChunk` load synchronously.
  Identical vanilla tickets merge; `Areas` counts leases. The ticket entry point is shared and guarded by mods
  with custom chunks: Sable refuses tickets on ship plots (x from 20 480 000 blocks; it adds and removes plot chunk
  holders in `ChunkMap.updatingChunkMap` itself). Bypassing the entry point (`DistanceManager.addTicket`) creates
  vanilla holders and generation there. Sable removes a ship chunk holder (hole, repair), the vanilla level stays,
  and subsequent generation nearby finds no holder: NPE in `ChunkMap.acquireGeneration`, "Exception in server tick
  loop" (GM building at ship-plot coordinates; `GmSableGameTests` reproduces this). Check ticket acceptance only
  through vanilla's queue (`DistanceManager.tickets`, read-only reflection). A lease must not remove a refused
  ticket: `removeTicket` recalculates the chunk level even without its own ticket, unloading the ship holder
  (31 → 45). A lease cannot await a refused, unready chunk: building, undo, `area.prepare` and bot login refuse
  immediately (`Lease.refusal`, `refused` and `refusal` in `areas`).
- Block writes read neighbors (neighbor shapes, neighbor updates, Sable up to two blocks): leases require the
  neighbor ring, or a neighboring chunk not fully loaded would load synchronously. Jobs wait until every leased
  chunk is full (`getChunkNow`), at most 2400 ticks.
- Release at most 64 chunks per tick (vanilla unloads over 2000 holders at once in a tick). Server shutdown releases
  all at once in `ServerStoppingEvent` at normal priority, before mods waiting for generation at `LOWEST`
  (Incoming's `StopDrain`); otherwise they would also wait on GM tickets. Limit: `work.max_chunks` (1500), including rings.
- Read only through `getChunkNow` and `LevelChunk` itself: snapshots and `blocks` never load unloaded chunks
  (checkerboard, `?`); use `area.prepare` to load. Entities: `getEntities` by bounding box (loaded sections, list snapshot).
- Building places positions as `/fill` (`FillCommand`): first `getBlockEntity` (initializes a fresh chunk's pending
  block entity; otherwise its data would survive the new block, "DUMMY"), snapshot via `saveWithFullMetadata`,
  `Clearable.tryClear`, `BlockInput.place` with `UPDATE_CLIENTS`, then `blockUpdated` after the slice. Undo does not
  restore entities or anything detached outside the box (a torch on a removed wall becomes an item).
- A completed job's description in its future is shared by all waiters: copy responses (`GmServer.await`), or a
  snapshot would append the image to the shared object, retaining it in history and returning it again.
- A method called on the server thread (GameTest) runs immediately (`server.execute` does not enqueue on that
  thread); `join` on a future waiting for a tick deadlocks the server thread.
- GM commands pass through `CommandEvent` (the feed skips them: only player commands, without private messages)
  and log an INFO line, "GM: /…"; operators receive no chat notification (`shouldInformAdmins` is false).
- Functions: `CommandFunction.fromLines` + `ExecutionContext.queueInitialFunctionCall`, like `/function`, without
  a file or `/reload`. JSON macro parameters: integers as `IntTag`/`LongTag` (`NbtOps` would turn a number into `5.0`).
- PNG uses our own writer (`Png`, Deflater): a server image may lack AWT and fonts. Encode on the bridge thread,
  not the server thread.
- MCP (`mcp/`): `POST /mcp` on the bridge port, hand-written on the bridge's `HttpServer` (the Java SDK's modern
  server rejects legacy `initialize` and needs a servlet container). Both eras on one endpoint without sessions:
  legacy `initialize` (Codex CLI 0.172: 2025-06-18; Claude Code channels need it) and modern 2026-07-28 (Claude
  Code 2.1.332 probes `server/discover` first and stays modern). Modern headers must match the body (400, -32020);
  unknown versions 400, -32022. `McpProtocolTest` replays requests recorded from both clients
  (`src/test/resources/mcp/`); re-record with a new client release. Tool argument checks live in `Tools.route`
  (the bridge would ignore unknown parameters); tool errors are `isError` results, not protocol errors.
  Instructions: the core rule in the first 512 characters (Codex and ChatGPT read only those), notes one line each,
  under 3,000 characters (Claude Code cuts at 4,096). `?profile=compact` (6 tools) and `?images=false` per URL.
- Groovy 4 ships as a nested jar (`jarJar`; dedicated NeoForge servers load it into the game layer, exposing game
  and mod classes). Gradle runs use `additionalRuntimeClasspath` (nested jars are not read there). Each script has
  its own `GroovyClassLoader` over the mod's loader; call `Compiled.close` after the last run, or classes never unload.
- Script deadline: `@ConditionalInterrupt` with `Scripts.expired()`, checked at loop, method and closure entry
  (except static methods; the transform leaves them alone, as does `@TimedInterrupt`). The deadline is `ThreadLocal`,
  present only while `Scripts.run` executes on that thread. Nested runs (a rule triggered by a script command) have
  their own deadline, then restore the outer one. `@TimedInterrupt` measures from instance creation and is unsuitable:
  `DistanceManager.purgeStaleTickets` calls a closure given to the game as a ticket comparator (`TicketType.create`)
  after its deadline: `UndeclaredThrowableException` in `Ticket.compareTo`, "Exception ticking world". The deadline
  cannot interrupt Java code called by a script (waiting, `level.getChunk`).
- Script calls must finish within the script. Never give script closures or objects to the game (tickets,
  comparators, bus listeners, deferred tasks): later game calls run without a deadline (an infinite loop trips the
  tick watchdog), errors crash the tick, and the script loader never unloads. This cannot be reliably prohibited
  (a closure coerces to any Java interface), so it is a rule in the `script` tool description and the `scripts_and_rules` note; use rules (`rule.add`) for
  reactions. Scripts must not load chunks (custom `TicketType`, `level.getChunk`); prepare distant locations with
  `area.prepare` (mods loading their own areas do not need this).
- The first script run links Groovy calls: 5–80 ms (GameTest). Rules therefore omit the first run from timing and
  average over 20 ticks: timing is wall-clock; a GC pause inside a run would otherwise disable rules.
- FML scan data (`EventTypes`): `module-info` (present in many mod jars) has no parent, so `ClassData.parent()` is
  null. Without a null check, every `rule.add` crashes (absent in the cloud with only the GM mod, present on a modpack server).
- Rules attach to the static `NeoForge.EVENT_BUS`: remove them in `GmServer.stop` (the bus outlives singleplayer
  worlds). Reentry into the same rule (its script triggers the same event) does not run it again.
- The bridge grants operator level 4 and, with scripts (`scripts.enabled`, default true), arbitrary code in the
  server JVM as its OS user (files, processes). Listen only on loopback or a host-only network (`bridge.host`).
- A command submitted inside ongoing command execution (a rule on an event from a command or function) is only
  queued by vanilla (`Commands.executeCommandInContext`): no result, runs after the rule exits, bypassing its
  reentry guard. `CommandRunner` temporarily clears `CURRENT_EXECUTION_CONTEXT` (reflection) for its commands
  and runs them immediately in their own execution.
- The feed records a command only if its source is the player itself (`CommandSourceStack.source`); otherwise
  `commandAs` as a player would look like that player's command.
  Cloud bridge-server tests use loopback (`server-ip=127.0.0.1`, no LAN announcement).

## Bots and vision

Before planning or changing bot or vision behavior (including compatibility drivers and tests), read
[Bots and vision](docs/agent-bots.md) in full.

## Checks

- Ladder: `LadderTest` (roles below one's own, durations, expiry, owner never granted; zones and quotas parse with
  named mistakes; hourly quota; the fresh-install template is friends); GameTest `GmLadderGameTests` (a zone denies
  building; an arena lets a guest-floor agent build inside only; the marker cannot be forged; a granted role, the
  block quota, a permission handler and a claim source decide a guest's request).
- Journal: GameTest `GmJournalGameTests` (a command's fill recorded with before/after on disk; revert leaves a
  position changed since; a script's write recorded and reverted; mid-build the change is on disk and a journal
  reopened on the directory lists it as interrupted and reads it; redstone wire beside a redstone block reverts with
  no conflict; one command writing 400 blocks apart reverts both places).
- Content limits: GameTest `GmLimitsGameTests` (command block, jigsaw and a sign with data refused below owner, a
  plain sign built, the owner builds a command block; bread given to the requester, `never_give`, components and
  no requester refused; a creative bot at the member floor refused).
- Confirmations: `ConfirmationsTest` (what is irreversible for others, only the requester decides, first answer,
  expiry, admin merge and limits); GameTest `GmConfirmGameTests` (a request's command runs at the player's level,
  unasked at the floor's; an irreversible request waits for its requester; ask_admin only for listed kinds, then
  with the admin's rights).
- Gate: `PolicyTest` (template = today's behaviour, floors, UUID owners, named mistakes, bad file keeps the last
  good one), `GateTest` (every method has a node, private allows all, no ticket never exceeds the floor, tickets
  narrow to the requester and the ceiling, parameter-dependent nodes); GameTest `GmGateGameTests` (live method
  table, friends floor, owner and offline tickets from real `/gm` messages, `status` rights).
- Audit and pause: `AuditTest` (effects and refusals recorded, reads not, JSON lines, pruning, bounds);
  GameTest `GmAuditGameTests` (a paused agent reads but cannot act, its build freezes and finishes after resume,
  another agent goes on, the build is audited with its job).
- Who asked: `AddressingTest` (router fixtures), `TicketsTest`, `IdentityTest`, `FeedTest.agentSeesOnlyItsOwnMessages`;
  GameTest `GmInboxGameTests` (identity and ticket on `/gm`, routing, chat addressing and conversation, flood limit).
- Deny list: `DenyListTest` (one case per entry and near misses), `DispatchTest` (checks first, idempotency),
  `LagGuardTest`; GameTest `GmSafetyGameTests` (choke point, a script's run-time `gm.command`, compile guard, queued
  world calls). `GmGameTests.call` goes through `Dispatch`; `direct` bypasses it for tests of the layers below.
- Unit tests (`src/test`): loopback HTTP bridge (per-agent callers, origin rule, lockout, long polls do not starve
  calls, full pool → 503), agents (add, revoke, remove, reload), failed-auth backoff, token, feed, PNG (read by a
  standard decoder), build plan, macro parameters, event list, notes (`NotesTest`: real packs, datapack override,
  provenance; `CatalogTest`: secret keys), script deadline (`ScriptsTest`: closure and object after execution, interrupting a run,
  nested execution), bot movement packets (`MovePacketsTest`).
- Bot and vision GameTest: `GmBotGameTests`: sprinting, mining and building, chest, chat and whisper (14 messages
  without a spam kick), death and respawn (same class), Nether, vehicles (horse gallops and jumps; boat reaches
  client speed 0.3993 per tick after 60 ticks and turns; Immersive Aircraft biplane: throttle, takeoff roll, brake,
  dismount with an IA key; airborne dismount twice and again in another aircraft; IA airship 500 blocks outside
  the platform, pilot boards remotely like `ride`, bot stays seated each tick, its chunks follow the airship,
  the world ticks it), bot mod packets (IA dismount bytes, unknown packet, trailing byte), distant login (no
  immediate chunk load, bot waits), vision (wall, mob in front and behind, image), name/skin protection, "dead man's
  switch" (timed key; a rule controlling the bot disables itself → bot stops). Tests load the distant login
  location and airship path themselves (`level.getChunk`): background loading cannot keep up with GameTest ticks.
  Remove the bot at test end. Platform: GameTest structure block is below the template; `absolutePos` counts from
  it, so floor stone is at y=1 and standing height at 2.
- Script/rule GameTest: `GmScriptGameTests`; remove rules in `finally` (the server shares the bus). GameTest does
  not check the nested Groovy jar: after build changes, use a dedicated NeoForge server from the installer
  (`java -jar neoforge-<version>-installer.jar --installServer`, directory under `run/`), put the `build/libs` jar
  in `mods/`, set `server-ip=127.0.0.1`, call `script` through `mcp/almighty.py call`.
- GameTest runs include Immersive Aircraft and Sable (`devtestRuntimeOnly` from Modrinth Maven, using the `devtest`
  set; Sable compile types also use sable-companion from maven.ryanhcode.dev). Mods send custom packets on player
  login, so test mock players need all mod channels (`NetworkRegistry.configureMockConnection`), like bots.
- Sable ship GameTest: `GmSableGameTests`: assemble with `sable assemble area`, a lease on its plot places no
  tickets, building there refuses immediately, releasing the lease leaves the live ship's chunk level unchanged;
  remove the ship with a lease active, then lease nearby again without a generation crash. Autopilot ship:
  assembled in midair, it falls; Sable velocity is blocks per second (per-tick movement × 20), find a barrel on the
  plot by type, plot ↔ world; when spinning, velocity, angular velocity and velocity at a point to the side match
  Sable's body (`getVelocity` takes a plot point).
- GameTest (`src/devtest`, `almighty:floor` template from `scripts/gen_test_structures.py`): one batch per test
  (job clock and GM tickets are shared server-wide). Replace the job clock (`GmServer.clock`, 1 ms per call) for
  per-tick slices without wall-clock timing; tests load build chunks immediately (`level.getChunk`). A snapshot
  response (and its `job` response) is completed off the server thread (`thenApplyAsync`, PNG): GameTest runs
  through the tick timeout before it finishes (CI: 200 ticks in 0.3 s). Await job completion by ticks, the response
  with timed `get`.
- Trial server: `./gradlew runServer` (directory `run/server`, `eula.txt`, `server.properties` with
  `server-ip=127.0.0.1`), then `ALMIGHTY_TOKEN_FILE=run/server/config/almighty/token uv run mcp/almighty.py call status`.
