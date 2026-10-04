---
name: set_time
description: Время суток для всех: утро 1000, полдень 6000, закат 12000, ночь 13000, полночь 18000
kind: instant
params: {"ticks": {"type": "int", "min": 0, "max": 23999}}
resources: ["time"]
---
Время общее для всех игроков; ночь — мобы. Просят «день» — 1000, «ночь» — 13000.

```steps
[{"method": "command", "params": {"commands": ["time set ${ticks}"]}}]
```

```check
return Math.abs(server.overworld().getDayTime() % 24000 - (args.ticks as long)) < 100
```
