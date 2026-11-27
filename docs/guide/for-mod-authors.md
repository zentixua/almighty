# For mod and datapack authors

English · [Русский](for-mod-authors.ru.md)

Agents learn any modpack from the running server: `notes {query: "<your mod id>"}` returns your blocks with their
states, items, entities, recipes, commands with their usage, game events and your NeoForge config values (keys that
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
