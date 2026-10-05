---
name: plane_land
description: Самолёт бота из plane_flight — прервать маршрут и сразу на посадку (заход на полосу из plane_flight)
kind: task
params: {"bot": "name"}
resources: []
---
Пропускает оставшиеся точки: автопилот plane_ap_<bot> сразу идёт к точке входа в глиссаду и садится. Нужен
полёт с посадкой (land=true в plane_flight). Сажать на другую полосу — поправить runway в state и снова этот рецепт:
`state['plane_ap.<bot>'].runway = [x, z, высота полосы, курс]`.

```groovy land
def c = state['plane_ap.${bot}']
if (c == null) return [ok: false, error: 'полёта бота ${bot} нет (plane_flight)']
if (c.runway == null) return [ok: false, error: 'полёт без посадки (land=false): полосы нет']
if (!(c.phase in ['takeoff', 'cruise'])) return [ok: true, phase: c.phase]
c.phase = 'approach'
c.spiral = false
return [ok: true, phase: c.phase]
```

```steps
[{"method": "script", "params": {"code": "${code:land}"}}]
```

```check
def c = state['plane_ap.${bot}']
return [ok: c != null && c.phase in ['approach', 'final', 'rollout', 'stopped', 'out'], phase: c?.phase]
```
