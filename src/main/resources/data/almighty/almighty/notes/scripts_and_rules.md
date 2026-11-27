---
name: scripts_and_rules
description: Groovy scripts and event rules in Almighty - what the script variables are, the rules that keep the server alive (no closures handed to the game, no chunk loads, no sleeping), budgets, and when a rule turns itself off.
---

# Scripts and rules

`script {code, args?, timeout_ms?}` runs Groovy on the server thread; everything in the game and in any mod is
reachable. `rule {action: "add", event, script?}` reacts to any game event in the same tick, without you.

## Variables
`server` (MinecraftServer); `gm`: `gm.command('...')`, `gm.commandAs(entity, '...')`, `gm.emit(data)` (an `emit`
event in the feed), `gm.fields(event)`, `gm.player(name)`, `gm.level('minecraft:the_nether')`, `gm.bot(name).act(steps)`,
`gm.ship(name)` for Sable ships; `state`, a map shared by scripts and rules until the server stops; `args` in a
script; `event` and `rule` in a rule. Common classes are imported (BlockPos, Blocks, Items, ServerPlayer, Vec3, AABB,
Component, BuiltInRegistries...). The returned value comes back as JSON; `println` goes to `output`.

## Rules that keep the server alive
- Never hand a closure or script object to the game: no ticket types with comparators, no listeners on the event
  bus, no deferred tasks, no threads. The game would call them later without a time limit, and their errors crash
  the world tick. React to events with `rule`.
- Never load chunks from a script (`level.getChunk`, own tickets): use `getChunkNow` and `area prepare` for a
  distant place.
- Never sleep or wait on the server thread; never exit the JVM or start processes.
- `timeout_ms` (up to 10000) interrupts loops; what was done stays. There is no undo for script changes: large block
  changes belong in `build`.

## Rules
- `event`: a NeoForge event class by short name (`BlockEvent.BreakEvent`, `LivingDamageEvent.Pre`,
  `CommandEvent`) or `tick`. Find events with `rule {action: "types", query}` or `notes {kind: "events", query}`.
- Without a script, the event's properties go to the feed. `event.canceled = true` cancels a cancellable event; use
  `priority: "highest"` to forbid something.
- `every` (ticks), `limit` (runs, then removed), `name` (replaces the rule with that name), `persist` (survives
  restarts).
- A rule turns itself off (`rule.off` in the feed, with the reason) when its average time per tick goes over
  `budget_ms` (default 5), one run takes over 20 x budget, 10 errors in a row, or it writes over 200 feed entries in
  a second. Bots it drove stop. Fix it and add it again.
