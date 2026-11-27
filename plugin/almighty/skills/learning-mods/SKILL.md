---
name: learning-mods
description: "How to learn an unknown mod or modpack on an Almighty server - the mod's own note, the live catalog of its blocks, items, recipes, commands, events and config, trying things safely, and writing down what worked."
---

<!-- Generated from src/main/resources/data/almighty/almighty/notes/learning_mods.md by scripts/export_skills.py; edit the note, not this file. -->

# Learning an unknown mod

The server tells you what it contains; you do not need to guess from memory.

1. `notes {query: "<mod id>"}` - the mod's own note if it ships one, and its catalog slice. Narrow with `kind`:
   `blocks` (with states), `items`, `entities`, `recipes` (type, result, ingredients), `commands` (usage),
   `events` (to react with rules), `config` (its settings), `tags`. `notes {kind: "mods"}` lists every mod.
   Answers are paged: continue with `offset: next_offset`.
2. Look and read: `view {kind: "eye"}` and `view {kind: "blocks"}` (block states), `entities` around a place,
   `status {player}` for a player's inventory.
3. Try in a prepared test area away from players, with `build` so it can be undone. Bots press mod buttons with
   `payload` and drive rider-input vehicles once they are in the `almighty:driven_by_rider` tag.
4. Write what worked into a world note (`notes {id: "world:mods/<mod id>", text}`) so the next session, or another
   agent, starts from it.

Mod authors can ship a note for agents in their jar at `data/<modid>/almighty/notes/<name>.md` (Markdown, optional
`name`/`description` frontmatter); it costs them no dependency.
