---
name: teleport_player
description: Перенести игрока по его же просьбе в точку x y z (в его измерении)
kind: instant
params: {"player": "name", "x": "int", "y": "int", "z": "int"}
resources: []
---
Только самого просящего (или с согласия того, кого переносят). Точку выбрать на земле, не в блоке: у места —
status игрока или view blocks; без уверенности — y повыше и медленное падение.

```steps
[{"method": "command", "params": {"commands": ["execute as ${player} at @s run tp @s ${x} ${y} ${z}"]}}]
```

```check
def p = gm.player(args.player)
return p != null && p.distanceToSqr(args.x + 0.5, args.y, args.z + 0.5) < 9
```
