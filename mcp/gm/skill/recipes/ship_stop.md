---
name: ship_stop
description: Остановить корабль с автопилотом ship_autopilot — моторы 0, правило снять; горелки остаются (корабль висит)
kind: task
params: {"ship": "name"}
resources: ["ship:${ship}"]
---
Моторы корабля — в 0 сразу (места из state['ship_ap.<ship>']), правило ship_ap_<ship> — снять. Горелки не
трогаются: корабль висит на последней высоте и медленно дрейфует (ветра нет, только остаточный ход). Посадить —
ship_autopilot с final у земли (+4 над рельефом сам) и thrust=false на месте, потом ship_stop.

```groovy stop
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour
def c = state['ship_ap.${ship}']
if (c == null) return [ok: false, error: 'автопилота корабля ${ship} нет (ship_autopilot)']
def lvl = gm.level(c.dim ?: 'minecraft:overworld')   // автопилот, поставленный без dim, — в верхнем мире
int stopped = 0
for (p in c.port + c.star) {
    if (lvl.getChunkSource().getChunkNow(p[0] >> 4, p[2] >> 4) == null) continue
    def be = lvl.getBlockEntity(new BlockPos(p[0], p[1], p[2]))
    if (be instanceof SmartBlockEntity) { be.getBehaviour(ScrollValueBehaviour.TYPE)?.setValue(0); stopped++ }
}
c.phase = 'stopped'
c.applied = [port: 0, star: 0]
return [ok: true, motors: stopped]
```

```steps
[{"method": "script", "params": {"code": "${code:stop}"}},
 {"method": "rule.remove", "params": {"name": "ship_ap_${ship}"}}]
```

```check
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour
def c = state['ship_ap.${ship}']
def lvl = gm.level(c.dim ?: 'minecraft:overworld')
def running = (c.port + c.star).findAll { p ->
    lvl.getChunkSource().getChunkNow(p[0] >> 4, p[2] >> 4) != null &&
        lvl.getBlockEntity(new BlockPos(p[0], p[1], p[2]))?.getBehaviour(ScrollValueBehaviour.TYPE)?.getValue() != 0
}
return [ok: c.phase == 'stopped' && running.isEmpty(), running: running.size()]
```
