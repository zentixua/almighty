---
name: bots
description: "Driving Almighty player bots - spawning, key and mouse steps, menus, vehicles (horses, boats, Immersive Aircraft), mod packets, timed keys for rule autopilots, and seeing through a bot's eyes."
---

<!-- Generated from src/main/resources/data/almighty/almighty/notes/bots.md by scripts/export_skills.py; edit the note, not this file. -->

# Bots

Bots are your own players. They join like a player (marked `[bot]` in the tab list) and act only through what a
client has: keys, mouse, hotbar, menus, chat. Servers and mods see an ordinary player.

- `bot {action: "spawn", name, pos?, dimension?, gamemode?, skin?}`; `remove`; `list`; `state` (body, inventory,
  vehicle and its keys, open menu, mail: chat, whispers, titles, deaths).
- `bot {action: "act", name, actions: [...]}` runs steps in order; instant steps run back to back in one tick,
  only `wait` and `walk_to` take time. A failed step stops the program; `replace: true` drops the running one.
- Steps: `{"hold": "forward"|["forward","sprint"], "ticks": n}`, `{"release": "all"}`, `{"wait": ticks}`,
  `{"look": [yaw, pitch]}`, `{"turn": [dyaw, dpitch]}`, `{"look_at": [x,y,z] | name}`,
  `{"click": "attack"|"use", "at": ...}`, `{"slot": 0-8}`, `{"drop": "one"|"stack"}`, `{"menu": slot, "mode": ...}`,
  `{"close": true}`, `{"chat": "text" | "/command"}`, `{"sign": [x,y,z], "lines": [...]}`, `{"respawn": true}`,
  `{"walk_to": [x, z]}`, `{"press": "<mod key name>"}`, `{"payload": "mod:packet", "data": base64}`.
- Yaw: 0 south, 90 west, -90 east, 180 north; pitch down is positive.
- Rule autopilots hold keys with `ticks` (at least 2 for a rule running every tick): the key releases itself, so if
  the rule is turned off the controls return to neutral instead of staying held.
- Vehicles: get in with `click use` on it, out with `sneak`. Horses, boats, pigs and camels are driven with keys.
  A mod vehicle that reads rider input works once a datapack adds it to the entity tag `almighty:driven_by_rider`.
  Immersive Aircraft: left/right, jump/sneak, forward/back as for a player; leave with
  `press key.immersive_aircraft.dismount` (twice within a second in the air), boost with
  `key.immersive_aircraft.boost`.
- Mod buttons that send their own packets: the `payload` step with the bytes after the packet id, from the mod's
  sources or its note.
- See what a bot sees: `view {kind: "eye", who: "<bot>"}` - where it looks, the block or entity under the
  crosshair, entities around with angles and visibility, sounds heard, and a small picture.
- A bot costs the server as much as a player: remove bots you no longer need. Bots cannot use a real player's name
  or skin unless the owner allowed it.
