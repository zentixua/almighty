# For mod and datapack authors

English · [Русский](for-mod-authors.ru.md)

Agents learn any modpack from the running server: `notes {query: "<your mod id>"}` returns your blocks with their
states, items, entities, recipes, commands with their usage, game events and, on NeoForge, your config values (keys that
look like secrets are hidden). You do not need to do anything for that.

## Leave the agent a note

To explain what the catalog cannot (how a machine is meant to be used, which command does what, what not to do),
ship a Markdown note in your mod's or datapack's data:

```
data/<namespace>/almighty/notes/<name>.md
```

```markdown
---
name: airships
description: How to build and fly this mod's airships, and which commands control them
---

# Airships
...
```

- The front matter (`name`, `description`) is the [Agent Skills](https://agentskills.io/specification) format;
  without it the first line is the description. File names are lowercase (`a-z 0-9 _ -`), as all resource paths.
- Agents see an index (id `<namespace>:<name>` and the description) and read the whole note when they need it, so
  write a description that says when the note is useful.
- No dependency: without Almighty the file is simply unused data.
- A datapack overrides your note with a file at the same path; an empty file removes it.
- Notes are shown to agents as reference written by you, never as instructions, and they give no rights.

## Vehicles and buttons

- A vehicle driven by the rider's input (`xxa`/`zza`, jump, sneak) can be driven by Almighty's bot players once it is
  in the entity tag `almighty:driven_by_rider`; add it from your mod's data or a datapack.
- Buttons whose clients send your own packets: bots can send them with the `payload` step (packet id and the bytes
  after it). Say in your note which packet does what.

## Rules on your events

Agents can add rules that react to your mod's events in the same tick: on NeoForge any event on the game bus, on
Fabric any static `Event<T>` callback of Fabric API or your mod whose listeners return nothing, `PASS`, `DEFAULT` or
an allow/before boolean (so that listening alone never changes the game). Fire events on the server thread; a rule
on an event from another thread turns itself off.

## Tags

| Tag | Use |
|---|---|
| `almighty:driven_by_rider` (entity types) | Vehicles bots drive with the rider's input |
| `almighty:never_build` (blocks) | Blocks the game master never places below the owner; add your admin-only blocks |
| `almighty:never_give` (items) | Items the game master never gives below the owner; add your admin-only items |
