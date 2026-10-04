---
name: clear_weather
description: Ясная погода во всех мирах на время (дождь, гроза мешают)
kind: instant
params: {"seconds": {"type": "int", "min": 60, "max": 7200, "default": 1200}}
resources: ["weather"]
---
Погода общая для всех: ясно у всех игроков. Время — в секундах игры (20 тиков в секунду).

```steps
[{"method": "command", "params": {"commands": ["weather clear ${seconds}s"]}}]
```

```check
return !server.overworld().isRaining()
```
