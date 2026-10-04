---
name: heal_player
description: Вылечить и накормить игрока (по его просьбе или владельца)
kind: instant
params: {"player": "name"}
resources: []
---
Мгновенное здоровье и сытость на пару секунд; огонь и яды не снимает — для них effect clear.

```steps
[{"method": "command", "params": {"commands": [
  "effect give ${player} minecraft:instant_health 1 4",
  "effect give ${player} minecraft:saturation 2 10"]}}]
```

```check
def p = gm.player(args.player)
return p != null && p.getHealth() >= p.getMaxHealth() - 1
```
