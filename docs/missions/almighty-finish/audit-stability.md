# Audit B: stability, data loss, undo

Scope: src/main/java/ua/zentix/almighty/{work,world,building,audit,bot,view,rules,script,compat,mixin,bridge}, GmServer,
Api (where it drives these), neoforge/, fabric/. Read-only review. The checkout moved during the audit
(d1feb4a, then 25844e9, then c3b1d60); the line numbers below are from c3b1d60. Sources of truth: plan-1.0 §1.1, §4.9, §4.11,
§4.12, §6 (S6, S7, F2 to F14), §12, critique S3/S4, decisions-1.0.

Severity: **blocker** = crash / lag beyond budget / data loss / broken undo; **major**; **minor**.

---

## Blockers

### B1. `undo {job}` (the MCP `build` tool's `undo`) is a blind restore: it overwrites and destroys player work done since
- Where: building/BuildJob.java:154-174 (`undo` builds a Snapshot with no `expect`), :264 (the conflict check runs only
  when `restore.expect != null`, i.e. journal reverts), :271-273 (clears any container at the position before writing);
  Api.java `undo` (job branch); mcp/Tools.java:178-182 (`build {undo: <job>}` → `undo {job}`).
- Scenario: agent builds a wall (job 7). A player then puts a chest full of items where the wall was, or builds on it.
  The agent (or a player's request) runs `build {undo: 7}` → every position goes back to its pre-build state without
  checking. The player's blocks are replaced, and the chest is emptied by `clearContent()` first, so its items are
  destroyed (no drop).
- Plan: §4.9 "Restores are conflict-aware: only positions whose current state equals the recorded after state are
  reverted"; critique S3 names exactly this code ("Undo is blind … silently deletes what players built since").
  Step 8 fixed only `undo {change}`.
- Fix: give the job undo an `expect` array (the states the build placed, already known in `put`), or route
  `undo {job}` to `revert(job.change)` and drop the snapshot path.

### B2. The journal records an empty container for every block entity a GM build replaces (and for /fill and /setblock on 1.21.1), so undo restores empty chests
- Where: building/BuildJob.java:271-279 (`container.clearContent()` before `level.setBlock`, on all nodes);
  mixin/LevelChunkMixin.java:39-41 (the tag is taken at `setBlockState` HEAD, after the clear). Vanilla 1.21.1
  FillCommand/SetBlockCommand also call `Clearable.tryClear` before placing.
- Scenario: `build {ops: fill air}` over a chest with 27 stacks → the journal records `tagBefore` = chest with no items.
  The player clicks the receipt's [Undo] (`/almighty undo <change>`), or the agent runs `undo {change}` → the chest
  comes back empty. The items are gone for good (the job snapshot, taken before the clear, would have had them, but
  receipts and `undo {change}` use the journal). Redoing that undo loses the contents in the same way
  (BuildJob.put, revert path).
- Plan: §4.9 "records each position's before/after state and block-entity data"; principle 9 "every change is
  recorded and undoable". No GameTest covers a container (GmJournalGameTests has none).
- Fix: in BuildJob, record the snapshot tag in the journal before clearing (for example, an explicit
  `Journal.current().record(...)` with `oldTag`, with the mixin skipping that one write), or have the mixin keep the
  tag captured at the first `getBlockEntity` of a position in the current change. For commands, a mixin or a wrap on
  `Clearable.tryClear` while recording. Add a GameTest with a filled chest.

### B3. One `command`/`function` call runs unbudgeted on the server thread: 10 000 `fill` lines take the server past the watchdog (crash), and the journal writes GBs
- Where: Api.java:110-126 (whole call inside one `onMain`); act/CommandRunner.java:62-63 (`MAX_COMMANDS = 100`,
  `MAX_LINES = 10_000`); GmServer.runWorldCalls:326 (always runs at least one call per tick, however long);
  audit/Journal.java `record` (no per-change cap).
- Scenario: an agent at the private floor (level 3), or the request of a builder-level player (level 2), sends
  `function {lines: [10 000 × "fill … 32768 blocks"]}` → about 327 M block writes in one tick. NeoForge's
  `max-tick-time` (60 s) watchdog kills the server. Smaller cases: 100 fills in `command` (3.3 M writes) freeze the
  server for seconds; each write also appends about 18 B or more to the journal on the server thread (about 6 GB for
  the function case), and then `trim` deletes the whole change because it is larger than 4 M (so it was not
  undoable anyway). The `build_blocks` quota does not apply to commands.
- Plan: principle 7 "All GM work — jobs and world calls — runs within the per-tick budget"; §4.11 / F3 "fill/clone
  over a volume limit are refused with 'use build'" (not implemented: grep finds no such refusal; only vanilla's
  per-command cap of 32 768 exists).
- Fix: sum the volume of fill/clone/setblock/place lines while parsing them (CommandZones already parses them) and
  refuse beyond, say, 32 768 per call with "use build". Cap journal records per change (stop recording and mark the
  change `truncated`).

---

## Major

### M1. Idempotency keys keep the full call text: up to 1024 × 16 MB in memory (OOM)
- bridge/Dispatch.java:234 `String call = name + "\0" + p;` stored in `keys` (MAX_KEYS 1024, global, 10-minute TTL).
- Any agent with `command` (node default: guest) sends 1024 keyed calls with 16 MB bodies (for example 100 long
  commands). The call need not succeed: a failed command is `ok:false` inside a normal result, so the key is kept.
  The server holds about 16 GB of strings → OutOfMemoryError → crash. The key map is also global: one agent's 1024
  keys evict every other agent's keys, so their retries after a 504 run twice (F19).
- Fix: store a SHA-256 of the call; cap keys per agent.

### M2. Journal revert does O(n) boxing and string work for the whole change in one server tick; `/almighty undo` also reads the file there
- GmServer.java:618-660 (grouping with `List<Integer>`, and in region mode a `String` key per position,
  `rec.dim()[i] + "/" + …`), then copying per piece and `BuildJob.revert` loops, all inside `onMain`.
  command/AlmightyCommand.java:307 calls `gm.revert` on the server thread, so `journal.read` (disk read and NBT
  decoding of up to 4 M records) runs inside the tick too.
- Scenario: undo of a 2 M-position build (default `build_max_blocks`) → hundreds of ms to over 1 s in one tick;
  clicking [Undo] in chat pays for the file read as well.
- Fix: do the read and grouping on a bridge or worker thread (the data is pure arrays); `onMain` only submits the
  prepared jobs. For the command, read off-thread and complete on main.

### M3. `/almighty undo <change>` reports success even when the revert fails
- AlmightyCommand.java:307-311 ignores the returned future. Failures inside `onMain` (no chunk budget: "holds N
  chunks", dimension not loaded) and failed pieces (chunk timeout) are never shown. The player is told "Undoing
  change N" and nothing happens. There is also no "revert in progress" guard: repeated clicks or calls queue another
  full set of pieces (harmless to blocks, because unchanged positions are skipped, but each one costs a full read
  plus leases).
- Fix: `whenComplete` → `sendFailure`; reject `undo` while an earlier revert of the same change is running.

### M4. No per-agent fairness: jobs FIFO, shared pools, global rule and bot caps (plan S6, F2, F12, F14)
- work/Jobs.java:55-64: the first active job gets the whole budget, and later jobs run only if budget is left. One
  agent's 2 M-block build (minutes of 3 ms slices) starves every other agent's builds and views. The plan says "fair
  share per agent" (S6, F2).
- bridge/Bridge.java:58-60: 8 call and 16 long-poll threads plus 64 queued, shared by all agents. One agent with 8
  slow calls (for example `script {name, timeout_ms: 10000}`) or 16 `events wait=25` / `job wait=50` gets everyone
  else 503. No per-agent rate limit (F14) and no `calls` quota (F2).
- rules/Rules.java:51 `MAX_RULES = 100` is global: one agent can fill it. Bots: `bots.max` (8) is global, and there is
  no bot-minutes quota (F12).
- Fix: round-robin jobs by agent within the budget; a per-agent in-flight cap on the bridge (for example 4 calls and
  4 polls); per-agent caps for rules and bots.

### M5. Unbounded active jobs, leases and programs per agent (memory and per-tick work)
- Views: Api.view → `jobs.submit`; MapView.java:57 allocates the image at construction (up to 512² ints = 1 MB;
  EyeView up to 480×270). `map {wait:0}` in a loop queues jobs without limit: about 1 MB each, and they starve
  behind each other. Observe nodes are guest-level.
- Leases: world/Areas.java:247 has no cap on the number of leases (only on distinct chunks). `area.prepare` on the
  same chunk with `ttl_seconds 3600` in a loop → millions of Lease objects; `Areas.tick` (:326) walks them all every
  tick, and `areas` describes all of them (calling `ready()` on each) on the server thread.
- Bot programs: `Bot.programs` has no length cap (Steps caps 256 steps per program, not the number of programs).
- Fix: per-agent caps on active jobs (for example 16) and leases (for example 64); refuse with 429.

### M6. Script time is not capped below owner (plan §4.11, critique S4: "50 ms unless owner")
- Api.java:403 `timeout_ms` is 1..10 000 for every caller. `script {name}` needs only `scripts.run` (builder;
  policy/Node.java). A builder's request can run an approved script with `timeout_ms 10000` and arguments that make
  it loop → 10-second ticks. Repeating it keeps the server close to frozen (one world call is always run per tick).
- Fix: `timeout_ms` at most 50 ms unless the effective role is owner.

### M7. Below-owner declarative rules can pick `budget_ms` up to 1000, and the total of all rules is unbounded
- rules/Rules.java:156 (`budget_ms` 0.1..1000 for any creator); no sum across rules; rules are outside the GM budget
  and the lag guard.
- Scenario: a builder's request adds a rule on a frequent event (a block or tick-like callback) with
  `then: [{command: "fill … 32768 …"}]` (runs at the creator's level 2) and `budget_ms: 1000` → about 50 to 100 ms
  extra per tick for up to 7 days, and the rule never turns itself off. 100 such rules (the global cap) → several
  seconds per tick.
- Plan: principle 7 ("self-disabling rules", "within the per-tick budget"); §4.12.
- Fix: below owner, `budget_ms` at most 5 (or the GM budget); a global rule-time budget per tick that turns off the
  heaviest rule.

### M8. Shutdown runs queued long polls on the server thread (hangs that can last minutes)
- bridge/Bridge.java:131-132 `for (Runnable r : polls.shutdownNow()) r.run();` called from GmServer.stop
  (ServerStoppingEvent, server thread). `events` reads the feed synchronously (Api.java:344-358, up to 25 s). Each
  queued `events wait=25` therefore blocks shutdown for 25 s (up to 64 of them, about 26 min), before the world is
  saved. Panel stop timeouts then kill the JVM before the save.
- Needs more than 16 concurrent long calls (several agents), so it is less likely, but the effect is data loss.
- Fix: answer queued exchanges with 503 directly instead of running them.

### M9. Writes made by declarative rules are neither journaled nor undoable
- The journal opens a change only in `Dispatch.invoke` (GmServer.java:148-171). `Rules.fire` → `Guard.command` →
  `CommandRunner.runAs` runs with `Journal.current() == null`, unless the event happened inside a GM call.
- A below-owner rule that `fill`s or `setblock`s on events changes the world with no change id, no audit line and no
  undo. Principle 9 and §4.9 ("covers build, commands, functions and scripts alike").
- Fix: open a journal change per rule (one change per rule per minute, or per fire) through `journal.begin(creator…)`.

### M10. Offline permission lookups block the server thread (Fabric: up to 2 s per node)
- fabric/FabricPlatform.java:93 `Permissions.check(uuid,…).get(2, SECONDS)`; NeoForge
  `PermissionAPI.getOfflinePermission` (NeoForgePlatform.java:125; LuckPerms may load the user synchronously).
  Called on the server thread by `Nodes.covers` (loops over all nodes) from `bot` (Api.heardBy, once per heard chat
  line from a player who has left), `Inbox` and the rule guard (`creatorLacks`, an offline creator, once a second
  per rule).
- With fabric-permissions-api and LuckPerms on a remote database, one `bot {name}` call with a few lines from
  players who have left → seconds of stall.
- Fix: cache offline answers (refreshed off-thread, like `Nodes.refresh`); never wait on a future on the server
  thread.

---

## Minor

- m1. A change with more than 4 M positions is dropped as soon as it ends (Journal.trim:345 counts the newest first,
  so it removes the change itself), while `build_max_blocks` goes up to 16 M (GmConfig:60). Job snapshots have the
  same cap (Jobs.RETAINED). Builds above 4 M are silently not undoable. Warn or refuse, or cap the config at 4 M.
- m2. A revert leases all its pieces at once (GmServer:672-678): each 128×128 piece is up to 11×11 chunks with its
  ring, so a change spread over more than about 12 separate regions cannot be undone ("holds N chunks") within
  `max_chunks` 1500. Lease pieces one by one as they start.
- m3. Recovered and interrupted changes have no `positions`, so `trim` counts them as 0 against the 4 M budget.
  Empty `open` entries saved in the index before a crash become 0-record "interrupted" changes that use up slots of
  MAX_CHANGES (1000).
- m4. Journal dimension index is written as one byte (Journal:148, `out.writeByte(d)`): more than 255 dimensions in
  one change wrap around.
- m5. The mixin takes block entities with `EntityCreationType.CHECK`: a script's direct `setBlock` on a fresh
  chunk's pending block entity records no tag.
- m6. Persistence that is not atomic: `scripts/approved.json` (ConfigFiles.java:299, plain `writeString`; a torn file
  unpins every approval), `scripts/<name>.groovy` (:192), first-start `policy.toml` template (Policies.java:56).
  `OwnerApp.grant` (mcp/signin/OwnerApp.java:50) writes `policy.toml` atomically but outside `PolicyFile.LOCK` and
  without history: a lost update can race an agent's `file write`.
- m7. Commands still load chunks synchronously (`tp` or `spreadplayers` to ungenerated land; `locate`). The deny list
  refuses them only at plot distance. This is vanilla cost, but it is GM-initiated and unbudgeted.
- m8. `Json.fields` calls `getClass().getMethods()` on events: a `LinkageError` from a client-only signature
  (NoClassDefFoundError) is an Error, not caught, and goes into the event bus → tick crash (theoretical; AGENTS
  documents such classes for Sable).
- m9. Job.run catches only RuntimeException; a StackOverflowError in a job step escapes into the tick
  (GmServer.tickEnd has no guard).
- m10. A nested script run (a rule fired by a script command) gets its own full deadline, not min(outer, own).
- m11. The bridge accept pool has an unbounded queue (Bridge.pool with Integer.MAX_VALUE); a slow-loris client grows
  it before authentication.

---

## Plan items in this area: status

| Item | Status |
|---|---|
| Per-tick budget for jobs; first job at least one unit (§4.11, F3) | Done (Jobs, Budget) |
| World calls queued under the same budget (critique S4) | Done between calls (GmServer.onMain/runWorldCalls); **not within a call** (B3) |
| Bounded call / long-poll pools, 503 (S6, step 1) | Done; shared, not per agent (M4) |
| Lock-based feed, ring 4096, `dropped` | Done (Feed) |
| Idempotency keys (F19) | Done; memory and global-eviction flaws (M1) |
| Lag guard (10 s above target) | Done, budget at a quarter; rules and bots unaffected |
| `fill`/`clone` volume refusal "use build" (§4.11) | **Missing** (only vanilla's 32 768 per command) |
| `script.timeout_ms` 50 ms below owner | **Missing** (M6) |
| Fair share per agent, `calls` quota, per-agent rate limit (S6, F2, F14) | **Missing** (M4) |
| Bot-minutes quota (F12) | **Missing**; `bots.max`, timed keys and pause stopping bots done |
| Build quota per requester / agent (F4) | Done (Quotas, build_blocks); bypassed by commands (B3) |
| Radius-0 region tickets, ring, refusal of plot chunks, 64 releases per tick, shutdown order | Done (Areas, GmServer.stop) |
| Snapshots and `blocks` never load chunks; rays through ready chunks | Done (getChunkNow everywhere checked) |
| Bots wait for ready chunks (login, movement, vehicles, teleport ack) | Done (Bots.prepare, Controls.ready, Vehicles, Bot.acceptTeleport) |
| Rules: self-disable on budget, errors, emits, wrong thread; dead man's switch | Done; below-owner budget cap and global cap missing (M7) |
| Script deadline, compile-time accident guard | Done (Scripts, DenyList.SCRIPT) |
| Journal: write-recording mixin, incremental file, flush per tick, crash recovery, conflict-aware revert, exact reverse order, mod-gone refusal (§4.9, F5, step 8) | Done; block-entity contents wrong (B2), rule writes missing (M9), size caps (m1, m2) |
| Conflict-aware undo for every undo path | **Partial**: `undo {job}` blind (B1) |
| Own files atomic (F6) | Mostly; exceptions in m6 |
| Audit off the server thread, bounded | Done (Audit: own thread, 10 000 queue, 1000 kept) |
