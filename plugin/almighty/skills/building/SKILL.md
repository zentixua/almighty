---
name: building
description: Building in the world with Almighty - build ops (fill, set, layers), undo, loading distant places, reading blocks back, and how to stay inside the tick budget.
---

<!-- Generated from src/main/resources/data/almighty/almighty/notes/building.md by scripts/export_skills.py; edit the note, not this file. -->

# Building

- `build {ops}` runs in portions between ticks, so the server keeps running, and records an undo snapshot.
  Ops, applied in order:
  - `{"op": "fill", "from": [x,y,z], "to": [x,y,z], "block": "stone", "mode": "replace|keep|hollow|outline"}`
  - `{"op": "set", "pos": [x,y,z], "block": "oak_stairs[facing=east]"}`
  - `{"op": "layers", "origin": [x,y,z], "palette": {"#": "stone", "D": "oak_door[half=lower]"}, "layers": [["#.#", "..."], ...]}`:
    layers bottom to top, rows north to south, characters west to east; a symbol not in the palette leaves the
    block as it is.
- Blocks are written as in `/setblock` (`minecraft:` optional, states in brackets).
- The answer holds the job id. `build {undo: id}` restores blocks and block entity contents of that job. Undo does
  not bring back entities or anything that dropped outside the box (a torch on a removed wall becomes an item).
- A build waits for its chunks by itself. To look at or read a place where no player is, first
  `area {action: "prepare", start: [x,z], end: [x,z], wait: 30}`; release it when done.
- Check your work: `view {kind: "blocks", start, end}` returns the blocks as text in the same layer format as
  `build`; `view {kind: "look", start, end, direction: "north"}` and `view {kind: "map", center, size}` return pictures.
- Plan large structures as several builds (one per part) so a mistake can be undone alone.
- `/fill` and `/clone` in `command` are for small changes; anything large belongs in `build`.
- Ask before building over what players made; prefer empty land or the area the owner gave you.
