---
name: ship_autopilot
description: Корабль Sable/Aeronautics по точкам — тяга бортов и горелки держат курс и высоту, в конце стоянка над точкой (правило в игре)
kind: show
params: {"ship": "name", "route": "text", "final": {"type": "int", "min": 70, "max": 300}, "transit": {"type": "int", "min": 90, "max": 300, "default": 170}, "heading": {"type": "number", "min": -1, "max": 360, "default": -1}, "base": {"type": "int", "min": 0, "max": 500, "default": 210}, "cruise": {"type": "int", "min": 16, "max": 256, "default": 256}, "reach": {"type": "int", "min": 10, "max": 200, "default": 60}, "thrust": {"type": "bool", "default": true}}
resources: ["ship:${ship}"]
---
Корабль с именем ship (sable name, загружен — sable forceload). Рецепт находит на нём (gm.ship) все моторы
create:creative_motor (левый борт — z участка меньше середины, правый — больше) и горелки
aeronautics:adjustable_burner и ставит правило ship_ap_<ship> (раз в 10 тиков): поза и скорость — gm.ship, курс —
разницей тяги бортов (cruise — полная, до 256), высота — горелками
(base — подача при y 150; дальше подстройка сама, в пределах ±200), над рельефом впереди — запас 14.
route — «x,z;x,z;…»: промежуточная точка засчитана с reach блоков; transit — высота пути, final — высота у последней
точки (палуба ≈ final+1), heading — курс стоянки в градусах (0 — нос на восток, 90 — на юг; -1 — любой).
thrust=false — только высота, моторы 0 (посадка игроков). У последней точки — стоянка, пока не ship_stop.
Ход — state['ship_ap.<ship>'] (phase fly/hold, last: d, pos, want, burner, speed, ps), события emit ship_ap: point,
hold, stopped. Сменить путь на ходу — снова этот рецепт (правило заменится).

Проверено (Almighty 0.3.0, Sable 2.0.5, Create Aeronautics 1.3.2) на корабле массой 19 600: 280 моторов, 96 горелок,
base 210 — высота 170 ±0,1, полный ход 4,9–5,5 б/с, правило 1–1,4 мс за запуск. Другой корабль — base подобрать.
- Корабль строят носом на +x участка, кормовые моторы — по обоим бортам; иначе курс не удержать.
- Без игрока или бота рядом корабль не идёт (стоит и медленно крутится, speed 0 в last): сказать игроку, что
  корабль пойдёт, когда кто-то будет на борту или рядом.
- Над незагруженной землёй рельеф не виден: высота пути — выше всего на маршруте, или игроки и бот рядом.
- Огромный корабль разворачивается на месте десятки секунд на 90°: reach меньше 60 — кружит у точки.
- Сразу после сборки горелки на 500 — корабль уходит вверх: автопилот ставить сразу.
- Скорость мотора и подача горелки — только поведением Create (ScrollValueBehaviour); data merge ломает сеть Create.
  Классы Sable из Groovy не трогать (тянут ClientLevel) — только gm.ship.
- Правило выключилось (rule.off) — моторы крутятся дальше: сразу ship_stop.

```groovy setup
// моторы и горелки корабля: его блок-сущности в готовых чанках участка (gm.ship)
def ship = gm.ship('${ship}')
if (ship == null) return [ok: false, error: 'корабля ${ship} нет: имя (sable name) или не загружен (sable forceload)']
def place = { be -> [be.getBlockPos().x, be.getBlockPos().y, be.getBlockPos().z] }
def motors = ship.blockEntities('create:motor').collect(place)
def burners = ship.blockEntities('aeronautics:adjustable_burner').collect(place)
if (!motors || !burners) return [ok: false, error: "на участке нет моторов или горелок: моторов ${motors.size()}, горелок ${burners.size()}"]
double cz = motors.sum(0d) { it[2] as double } / motors.size()
def route = '${route}'.split(';').collect { it.split(',').collect { it.trim() as double } }
if (route.any { it.size() != 2 }) return [ok: false, error: 'route — x,z;x,z']
def old = state['ship_ap.${ship}']
state['ship_ap.${ship}'] = [phase: 'fly', i: 0, trim: old?.trim ?: 0, applied: [:], route: route,
    dim: ship.level().dimension().location().toString(),
    port: motors.findAll { it[2] < cz }, star: motors.findAll { it[2] > cz }, burners: burners,
    final: ${final}, transit: ${transit}, yaw: ${heading} < 0 ? null : ${heading}, base: ${base}, cruise: ${cruise},
    reach: ${reach}, nothrust: !${thrust}, clear: 14, ahead: 60, kFf: 1.2, kTrim: 0.04, trimMax: 200, kAlt: 1.6, kVy: 14]
return [ok: true, motors: motors.size(), burners: burners.size()]
```

```groovy autopilot
// Автопилот корабля Sable / Create Aeronautics — правило tick (every 10, полсекунды): дифференциальная тяга кормовых
// моторов ведёт по точкам, горелки держат высоту, в конце — удержание над последней точкой. Поза и скорость (блоков
// в секунду) — gm.ship (классы Sable из Groovy не трогать), моторы и горелки — поведение Create
// ScrollValueBehaviour.TYPE (getBehaviour, открытый путь Create) по местам на участке корабля, только в загруженных чанках.
// Настройки — state['ship_ap.<имя>'] (их кладёт рецепт). Фазы: fly → hold; stop — моторы 0, горелки как есть.
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour
import groovy.transform.CompileStatic
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap

@CompileStatic
class ShipAutopilot {
    static double num(Map c, String k) { ((Number) c.get(k)).doubleValue() }
    static double clamp(double a, double lo, double hi) { Math.max(lo, Math.min(hi, a)) }
    static double wrap(double a) { double r = (a + Math.PI) % (2 * Math.PI); (r < 0 ? r + 2 * Math.PI : r) - Math.PI }

    /** Значение всем местам списка [[x, y, z], …]; меняет только отличающиеся (смена скорости мотора пересчитывает сеть). */
    static int set(ServerLevel lvl, List places, int value) {
        int changed = 0
        for (Object o : places) {
            List p = (List) o
            int x = ((Number) p.get(0)).intValue(), y = ((Number) p.get(1)).intValue(), z = ((Number) p.get(2)).intValue()
            if (lvl.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue   // не грузить: участок Sable
            def be = lvl.getBlockEntity(new BlockPos(x, y, z))
            if (!(be instanceof SmartBlockEntity)) continue
            ScrollValueBehaviour b = ((SmartBlockEntity) be).getBehaviour(ScrollValueBehaviour.TYPE)
            if (b != null && b.getValue() != value) { b.setValue(value); changed++ }
        }
        return changed
    }

    /** Значение группе мест (port, star, burners), если оно ушло от прошлого больше чем на step. */
    static int apply(Map c, ServerLevel lvl, String group, int value, int step) {
        Map applied = (Map) c.get('applied')
        Object was = applied.get(group)
        if (was != null && Math.abs(((Number) was).intValue() - value) < step) return 0
        applied.put(group, value)
        return set(lvl, (List) c.get(group), value)
    }

    /** Самое высокое под путём: по курсу от −20 до ahead блоков, ±8 по бокам, только готовые чанки. */
    static int terrain(ServerLevel lvl, double x, double z, double yaw, double ahead) {
        int top = -64
        double c = Math.cos(yaw), s = Math.sin(yaw)
        for (double d = -20; d <= ahead; d += 5) {
            for (int w = -8; w <= 8; w += 4) {
                int bx = (int) Math.floor(x + c * d - s * w), bz = (int) Math.floor(z + s * d + c * w)
                if (lvl.getChunkSource().getChunkNow(bx >> 4, bz >> 4) != null) top = Math.max(top, lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz))
            }
        }
        return top
    }

    /** Шаг: поза → тяга бортов и горелки. pose: x y z, vx vy vz, yaw (рад, нос +x участка в мире), pitch, roll. */
    static Map step(Map c, ServerLevel lvl, double[] pose) {
        double x = pose[0], y = pose[1], z = pose[2], vx = pose[3], vy = pose[4], vz = pose[5], yaw = pose[6]
        List route = (List) c.get('route')
        int i = ((Number) c.get('i')).intValue()
        List tp = (List) route.get(i)
        double tx = ((Number) tp.get(0)).doubleValue(), tz = ((Number) tp.get(1)).doubleValue()
        double dist = Math.hypot(tx - x, tz - z)
        if (dist < num(c, 'reach') && i + 1 < route.size()) {
            i++
            c.put('i', i)
            c.put('event', [phase: 'point', point: i, pos: [(int) x, (int) y, (int) z]])
            tp = (List) route.get(i)
            tx = ((Number) tp.get(0)).doubleValue(); tz = ((Number) tp.get(1)).doubleValue()
            dist = Math.hypot(tx - x, tz - z)
        }
        boolean last = i + 1 == route.size()
        double e = wrap(Math.atan2(tz - z, tx - x) - yaw)
        int terr = terrain(lvl, x, z, yaw, num(c, 'ahead'))
        double want = last && dist < 70 ? Math.max(num(c, 'final'), terr + 4) : Math.max(num(c, 'transit'), terr + num(c, 'clear'))
        // высота: подача по прямой от высоты + медленная подстройка + P по высоте и D по vy
        double trim = clamp(num(c, 'trim') + num(c, 'kTrim') * (want - y), -num(c, 'trimMax'), num(c, 'trimMax'))
        double ff = num(c, 'base') + (want - 150) * num(c, 'kFf')
        double burner = clamp(ff + trim + num(c, 'kAlt') * (want - y) - num(c, 'kVy') * vy, 0, 500)
        c.put('trim', trim)
        String phase = (String) c.get('phase')
        double cruise = num(c, 'cruise'), port = 0, star = 0
        // стоянка с 15 блоков: огромный корабль на месте разворачивается медленно и к точке в 8 блоков подходит долго
        if (last && dist < 15 && phase == 'fly') { phase = 'hold'; c.put('event', [phase: 'hold', pos: [(int) x, (int) y, (int) z]]) }
        if (phase == 'hold' && dist > 30) phase = 'fly'
        if (phase == 'hold') {
            // удержание: тормоз вдоль курса и тяга к точке; доворот на финальный курс, если задан
            double fwd = vx * Math.cos(yaw) + vz * Math.sin(yaw)
            double along = (tx - x) * Math.cos(yaw) + (tz - z) * Math.sin(yaw)
            double sp = clamp(6 * along - 40 * fwd, -80, 80)
            port = star = sp
            if (c.get('yaw') != null) {
                double dy = clamp(160 * wrap(Math.toRadians(num(c, 'yaw')) - yaw), -90, 90)
                port = sp + dy; star = sp - dy
            }
        } else {
            double speed = dist > 100 ? cruise : Math.max(40, cruise * dist / 100)
            if (last && dist < 40 && Math.hypot(vx, vz) > 3) speed = -60   // тормоз перед точкой
            if (y < terr + 6) speed = 0                                      // ниже рельефа — сперва вверх
            if (Math.abs(e) > 1.0) { port = e > 0 ? cruise / 2 : -cruise / 2; star = -port }
            else { double d = clamp(1.6 * e, -1, 1) * Math.abs(speed); port = speed + d; star = speed - d }
        }
        if (c.get('nothrust')) port = star = 0
        port = clamp(port, -cruise, cruise); star = clamp(star, -cruise, cruise)
        c.put('phase', phase)
        // места обходятся, только когда значение сменилось (376 мест у большого корабля — 3–4 мс на обход)
        int changed = apply(c, lvl, 'port', (int) Math.round(port), 1) + apply(c, lvl, 'star', (int) Math.round(star), 1)
        changed += apply(c, lvl, 'burners', (int) Math.round(burner), 2)
        return [i: i, d: Math.round(dist), pos: [Math.round(x), Math.round(y * 10) / 10.0, Math.round(z)], want: Math.round(want), terr: terr,
                burner: Math.round(burner), trim: Math.round(trim), e: Math.round(Math.toDegrees(e)),
                speed: Math.round(Math.hypot(vx, vz) * 10) / 10.0, vy: Math.round(vy * 10) / 10.0,
                pitch: Math.round(pose[7] * 10) / 10.0, roll: Math.round(pose[8] * 10) / 10.0, ps: [Math.round(port), Math.round(star)], changed: changed, phase: phase]
    }

    /** Остановка: моторы 0, горелки как есть. */
    static void stop(Map c, ServerLevel lvl) {
        set(lvl, (List) c.get('port'), 0)
        set(lvl, (List) c.get('star'), 0)
        ((Map) c.get('applied')).putAll([port: 0, star: 0])
    }
}

long started = System.nanoTime()
def c = state['ship_ap.${ship}']
if (c == null || c.phase == 'stopped') return
def lvl = gm.level(c.dim)
if (c.phase == 'stop') {
    ShipAutopilot.stop(c, lvl)
    c.phase = 'stopped'
    gm.emit([ship_ap: '${ship}', phase: 'stopped'])
    return
}
def ship = gm.ship('${ship}')
if (ship == null) {
    c.miss = (c.miss ?: 0) + 1
    if (c.miss >= 6) { ShipAutopilot.stop(c, lvl); c.phase = 'stopped'; gm.emit([ship_ap: '${ship}', phase: 'stopped', why: 'корабль пропал (gm.ship): разобран, переименован или выгружен']) }
    return
}
c.miss = 0
// ось +x участка (нос) и +z (правый борт) в мире — курс, тангаж, крен
def pos = ship.pos(), vel = ship.velocity()
def nose = ship.toWorldDir(new Vec3(1, 0, 0)), side = ship.toWorldDir(new Vec3(0, 0, 1))
double[] pose = [pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, Math.atan2(nose.z, nose.x),
                 Math.toDegrees(Math.asin(Math.max(-1d, Math.min(1d, nose.y)))),
                 Math.toDegrees(Math.asin(Math.max(-1d, Math.min(1d, side.y))))] as double[]
c.last = ShipAutopilot.step(c, lvl, pose)
if (c.event) { gm.emit([ship_ap: '${ship}'] + c.event); c.event = null }
double ms = (System.nanoTime() - started) / 1e6   // своё время правила: среднее и худшее
c.ms = c.ms == null ? ms : c.ms * 0.95 + ms * 0.05
c.msMax = Math.max(c.msMax ?: 0d, ms)
```

```steps
[
 {
  "method": "script",
  "params": {
   "code": "${code:setup}"
  }
 },
 {
  "method": "rule.add",
  "params": {
   "event": "tick",
   "name": "ship_ap_${ship}",
   "every": 10,
   "script": "${code:autopilot}"
  }
 }
]
```

```check
def c = state['ship_ap.${ship}']
return [ok: c != null && c.port && c.star && c.burners && c.phase in ['fly', 'hold'], phase: c?.phase,
        motors: (c?.port?.size() ?: 0) + (c?.star?.size() ?: 0), burners: c?.burners?.size()]
```
