# Almighty — an AI agent's bridge to a Minecraft server (`almighty`)

These rules apply to every contributor, human or agent. Read this file in full before starting.

The mod presents itself to players as "An almighty AI for your Minecraft server" in README.ru.md and
"An AI game master for your Minecraft server" in README.md, the main, English README. Text for owners and agents
(README, settings, bridge errors, tool descriptions, logs) is in English.

A server-side NeoForge mod for Minecraft 1.21.1 through which an AI agent (game master, GM) sees and acts in the
world: an HTTP bridge on the server and the agent's MCP adapter `mcp/almighty.py`. No custom blocks, items, packets
or registry entries (the only custom data is the entity tag `almighty:driven_by_rider`); clients need not install it.
No dependencies on other mods: a mod's compatibility module (`compat/`) loads only when that mod is installed.
Public repository: https://github.com/zentixua/almighty. The bridge runs on the Open Skies modpack server
(zentixua/open-skies, `side = "server"`).

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
`almighty-jar` artifact. Release: bump `mod_version` (`gradle.properties`), write `docs/releases/<version>.md`, merge
into `main` and manually run `build` on `main` with `release=true`; the workflow releases `v<version>` with the jar.
Then open an open-skies PR with that release's jar. Modrinth and CurseForge pages use the manually triggered
`publish` workflow: `draft` (hidden Modrinth draft), `submit`, `release`; fields: `docs/store/modrinth.json`;
procedure: `docs/store/listing.md`.

## Architecture (`ua.zentix.almighty`)

- `Almighty`: subscriptions; `GmServer`: state of one server run (`ServerStartedEvent` … `ServerStoppedEvent`):
  feed, jobs, chunks, bridge. The bridge runs only on a dedicated server (`isDedicatedServer`), not in singleplayer or GameTest.
- `bridge/`: Java's own HTTP (`com.sun.net.httpserver`), a dedicated 6-thread pool: `POST /rpc`
  `{"method", "params"}` → `{"ok": true, "result"}` or `{"ok": false, "error": {code, message}}`. Token:
  `config/almighty/token` (32 bytes in hex, permissions 600, created on first start; never logged), compared in
  constant time. The bridge waits 60 s for a method's response (stalled server thread → 504).
- `Api`: method table. Parse parameters on the bridge thread; access the world only on the server thread:
  `GmServer.onMain` (`server.execute`, between ticks) or a job (`work/`) within the tick budget.
- `work/`: job queue, processed in order at tick end (`ServerTickEvent.Post`) while time remains (`work.ms_per_tick`,
  3 ms); the first job gets at least one slice. Completion emits a `job` feed event (except snapshots). History:
  last 50 jobs, undo snapshots totaling at most 4 million positions.
- `world/Areas`: GM chunks, leased as a chunk rectangle with a one-chunk ring of neighbors.
- `building/`: plan (`BuildPlan`: fill/set/layers, no world needed for unit tests) and build/undo job (`BuildJob`).
- `view/`: top-down map (`MapView`, like a vanilla map), side/top/bottom view (`LookView`), blocks as text
  (`BlockText`), PNG without AWT (`Png`).
- `act/`: commands and functions (`CommandRunner`), messages to players (`Chat`); `feed/`: event feed and a player's
  private channel to the GM: `/gm <text>` (any player) emits `gm` in the feed, invisible to others; reply with `say` and `to`.
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
- `mixin/PlayerListMixin`: the only mixin, respawning a bot with its own class (see Bots and vision for why).
- `notes/Notes`: agent notes from mods and datapacks, `data/<namespace>/almighty/notes/<name>.md` in the server's
  current data (`server.getResourceManager()`, refreshed after `/reload`), id `<namespace>:<name>`. A datapack
  overrides a mod note at the same path; an empty file removes it. On startup the adapter fetches notes (5 s) and
  appends them to MCP instructions; on failure it adds a line about the `notes` tool. Mod-specific hints belong
  only in that mod's note, not the adapter: the bridge depends on no mod.

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
`ships {name?, center+radius?}` (Sable ships; 503 without Sable), `notes` → `[{id, source, text}]`.

All accept `dimension` (Overworld by default); long operations accept `wait` (default 30 s, up to 50): if the job
has not finished, return its description and continue with `job`. A snapshot image is returned once (`png_base64`);
the job then releases it. The 14 MCP tools in `mcp/almighty.py` follow Artem's principle, "few levers, each reaching
all the way down": `status` (+ player), `entities`, `command` (commands or function lines), `say`, `script`, `rule`
(add/remove/list/types), `events`, `view` (map/look/blocks/eye), `build` (+ undo), `area`, `job` (+ cancel), `bot`
(spawn/remove/list/state/act), `notes`, `call` (any method). Before adding something, consider a script or rule;
add a separate tool only if it really prevents errors. `from`/`to` are `start`/`end` there.

Alongside it, `mcp/gm/` (README): the GM as one Claude Code session, with the `almighty-chat/` chat channel (Node
plugin), `crash_guard.py` crash-prevention hook (PreToolUse), and `recipes.py` recipe runner. Tests:
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
- MCP SDK: 1.x (`mcp>=1.2,<2`); 2.x renames `FastMCP`, and the adapter targets 1.x.
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
  (a closure coerces to any Java interface), so it is a rule in `mcp/almighty.py` hints; use rules (`rule.add`) for
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

- Unit tests (`src/test`): loopback HTTP bridge, token, feed, PNG (read by a standard decoder), build plan, macro
  parameters, event list, script deadline (`ScriptsTest`: closure and object after execution, interrupting a run,
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
