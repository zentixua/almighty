---
name: plane_flight
description: Самолёт Immersive Aircraft с ботом-пилотом — взлёт с полосы, полёт по точкам, посадка и высадка (правило в игре)
kind: show
params: {"bot": "name", "plane": "block", "x": "number", "y": "number", "z": "number", "yaw": "number", "route": "text", "runway": {"type": "text", "default": ""}, "land": {"type": "bool", "default": true}, "final_dist": {"type": "int", "min": 200, "max": 1200, "default": 500}, "wait": {"type": "int", "min": 0, "max": 300, "default": 0}}
resources: []
---
Бот-пилот (новый бот с именем bot, творческий — топливо не нужно) встаёт на полосу (x y z — где стоять, курс yaw:
0 — на юг, 90 — на запад), перед ним появляется самолёт plane (id сущности самолёта Immersive Aircraft) с тегом
ap_<bot>, бот садится пилотом, правило plane_ap_<bot> (раз в 2 тика) ведёт: разбег по
оси (wait секунд сперва стоит с газом 0 — посадка пассажиров), отрыв, точки route «x,z,высота;x,z,высота;…» (до 200 знаков), над рельефом впереди — запас 30, препятствие
выше, чем успеть набрать, — набор в вираже на месте; потом заход на полосу runway «x,z,высота полосы,курс посадки»
(вход в глиссаду 5° за final_dist до порога, касание в 150 за порогом), посадка, торможение, высадка. land=false — после последней точки
бот отпускает клавиши и самолёт планирует: только над морем и без пассажиров.
runway не задан — посадка туда же, откуда взлёт: порог — x z старта, курс — yaw; ровной полосы вперёд от старта
нужно не меньше 200 блоков (разбег 40–90, касание в 150, пробег 15–30). Пассажира посадить, пока самолёт ждёт (wait):
`ride <игрок> mount @e[tag=ap_<bot>,limit=1]` (место второе у economy_plane). Ход — state['plane_ap.<bot>'] (phase,
last), события emit plane_ap: point, spiral, go_around, out, done, failed. Сажать сразу — рецепт plane_land.
Конец полёта: phase out — бот стоит у самолёта; убрать бота (bot remove) и самолёт (kill @e[tag=ap_<bot>]),
снять правило (rule remove plane_ap_<bot>).

Проверено (Almighty 0.3.0, Immersive Aircraft 1.5.2) на man_of_many_planes:economy_plane — под него подобраны
числа автопилота; другой самолёт — сперва проба на тестовом сервере.
- Клавиши бота — как у игрока: left/right — рыскание, back — нос вверх, forward — вниз (только в воздухе), jump —
  газ +0,1 за тик, sneak — газ −0,1 и тормоз. economy_plane: курс 1,5°/тик, тангаж 2°/тик, разбег на полном газу
  ~13 б/с, набор 1,5–2 б/с — круче ~1:8 рельеф не перебрать, поэтому набор в вираже. Крейсер 13–13,5 б/с.
- Скорость на глиссаде держит газ (10 б/с): с газом 0,4 самолёт терял скорость, проседал и садился за сотни блоков
  до полосы. Касание ближе 150 за порогом цепляло огни и постройки перед полосой.
- Правило выключилось (rule.off) — мост отпускает клавиши бота, самолёт летит прямо с прежним газом, автопилот на
  него обратно не поставить: сразу сказать пассажирам и вернуть их на землю, бота и самолёт убрать.
- Водить самолёт вызовами из сессии — нет: задержки моста, самолёты разбивались; только это правило в игре.

```groovy board
def p = gm.player('${bot}')
if (p == null) return [ok: false, error: 'бота нет']
def plane = p.serverLevel().getEntities((Entity) null, p.getBoundingBox().inflate(8)).find { it.getTags().contains('ap_${bot}') }
if (plane == null) return [ok: false, error: 'самолёта с тегом ap_${bot} рядом нет']
String id = plane.getUUID().toString()
gm.bot('${bot}').act([[release: 'all'], [look_at: id], [click: 'use', at: id]])
return [ok: true, plane: id]
```

```groovy setup
def route = '${route}'.split(';').collect { it.split(',').collect { it.trim() as double } }
if (!route || route.any { it.size() != 3 }) return [ok: false, error: 'route — x,z,высота;x,z,высота']
// без runway — посадка на полосу взлёта: порог — место старта, курс — курс взлёта
def rwy = '${runway}' ? '${runway}'.split(',').collect { it.trim() as double } : [${x}, ${z}, ${y}, ${yaw}]
if (rwy.size() != 4) return [ok: false, error: 'runway — x,z,высота полосы,курс']
state['plane_ap.${bot}'] = [phase: 'takeoff', t: 0, every: 2, i: 0, route: route, runway: ${land} ? rwy : null,
    takeoffYaw: ${yaw}, y0: ${y}, reach: 60, look: 160, clearance: 30, blindAlt: 200, climb: 0.12, margin: 12,
    kAlt: 0.5, kVy: 30, maxUp: 20, maxDown: 15, pitchBand: 1.5, yawBand: 2,
    finalDist: ${final_dist}, glide: 5, touch: 150, finalSpeed: 10, waitTicks: ${wait} * 20, trace: []]
return [ok: true, points: route.size()]
```

```groovy autopilot
// Автопилот самолёта Immersive Aircraft под ботом ведущего — правило tick (every 2).
// Настройки и телеметрия — state['plane_ap.<бот>'] (их кладёт рецепт); счёт — в классе @CompileStatic (динамический
// Groovy делал 40 чтений высоты за 2–4 мс, статический — за 0,02 мс).
// Фазы: takeoff → cruise (точки route) → approach → final → rollout → out (бот вышел на полосе) | done (без посадки) | failed.
import groovy.transform.CompileStatic
import immersive_aircraft.entity.EngineVehicle
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap

@CompileStatic
class PlaneAutopilot {
    static double num(Map c, String k) { ((Number) c.get(k)).doubleValue() }
    static double at(List l, int i) { ((Number) l.get(i)).doubleValue() }
    static double wrap(double a) { ((a % 360) + 540) % 360 - 180 }
    static double clamp(double a, double lo, double hi) { Math.max(lo, Math.min(hi, a)) }
    static double heading(double dx, double dz) { Math.toDegrees(Math.atan2(-dx, dz)) }

    /**
     * Рельеф впереди по курсу heading на look блоков (ось и ±12 по бокам): top — самое высокое, blind — было ли
     * незагруженное, wall — высота, которую нужно иметь сейчас, чтобы пройти всё впереди с запасом margin при наборе
     * climb блока на блок пути (выше текущей — не успеть: набор в вираже).
     */
    static void terrain(Map c, ServerLevel lvl, double x, double y, double z, double heading) {
        double fx = -Math.sin(Math.toRadians(heading)), fz = Math.cos(Math.toRadians(heading))
        double look = num(c, 'look'), climb = num(c, 'climb'), margin = num(c, 'margin')
        int top = -64
        double wall = -64
        boolean blind = false
        for (double d = 0; d <= look; d += 8) {
            for (int side = -1; side <= 1; side++) {
                int bx = (int) Math.floor(x + fx * d - fz * side * 12), bz = (int) Math.floor(z + fz * d + fx * side * 12)
                if (lvl.getChunkSource().getChunkNow(bx >> 4, bz >> 4) == null) { blind = true; continue }
                int h = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)
                top = Math.max(top, h)
                if (h >= y - 4) wall = Math.max(wall, h + margin - d * climb)   // только то, что на нашей высоте и выше
            }
        }
        c.put('top', top)
        c.put('wall', wall)
        c.put('blind', blind)
    }

    /** Один шаг: клавиши бота; фаза, событие и телеметрия — в c. */
    static List<String> step(Map c, EngineVehicle v, ServerLevel lvl) {
        double x = v.getX(), y = v.getY(), z = v.getZ(), yaw = v.getYRot(), pitch = v.getXRot()
        double vy = v.getDeltaMovement().y, hs = v.getDeltaMovement().horizontalDistance()
        boolean ground = v.onGround()
        long t = ((Number) c.get('t')).longValue() + ((Number) c.get('every')).longValue()
        c.put('t', t)
        String phase = (String) c.get('phase')
        List route = (List) c.get('route')
        if (t % 20 == 0 || c.get('top') == null) {
            double look = yaw
            if (phase == 'cruise') {
                List wp = (List) route.get(((Number) c.get('i')).intValue())
                look = heading(at(wp, 0) - x, at(wp, 1) - z)
            }
            terrain(c, lvl, x, y, z, look)
        }
        double top = num(c, 'top'), clearance = num(c, 'clearance'), reach = num(c, 'reach')
        List rwy = (List) c.get('runway')
        double targetYaw = yaw, targetAlt = y, throttle = 1.0

        if (phase == 'takeoff' && t < num(c, 'waitTicks')) {
            // ждёт на полосе: газ 0, рули прямо (посадка пассажиров)
            throttle = 0
        } else if (phase == 'takeoff') {
            // разбег по оси полосы; на скорости самолёт почти невесом и отрывается сам — тогда нос вверх
            targetYaw = num(c, 'takeoffYaw')
            targetAlt = num(c, 'y0') + 40
            if (!ground && y > num(c, 'y0') + 6) phase = 'cruise'
        }
        if (phase == 'cruise') {
            int i = ((Number) c.get('i')).intValue()
            List wp = (List) route.get(i)
            if (Math.hypot(at(wp, 0) - x, at(wp, 1) - z) < reach) {
                i++
                c.put('i', i)
                c.put('event', [phase: 'point', point: i, pos: [(int) x, (int) y, (int) z]])
                if (i >= route.size()) phase = rwy != null ? 'approach' : 'done'
                else wp = (List) route.get(i)
            }
            if (phase == 'cruise') {
                targetYaw = heading(at(wp, 0) - x, at(wp, 1) - z)
                targetAlt = Math.max(at(wp, 2), top + clearance)
                if (c.get('blind')) targetAlt = Math.max(targetAlt, num(c, 'blindAlt'))
                // не успеть набрать высоту до препятствия (набор ~climb блока на блок пути) — набор в вираже на месте
                boolean spiral = c.get('spiral') == Boolean.TRUE
                double wall = num(c, 'wall')
                if (!spiral && wall > y) {
                    spiral = true
                    c.put('event', [phase: 'spiral', pos: [(int) x, (int) y, (int) z], need: (int) wall])
                }
                if (spiral && y > wall + 5) spiral = false
                c.put('spiral', spiral)
                if (spiral) targetYaw = yaw + 90
            }
        }
        if (phase == 'approach' || phase == 'final') {
            double rx = at(rwy, 0), rz = at(rwy, 1), ry = at(rwy, 2), ryaw = at(rwy, 3)
            double fx = -Math.sin(Math.toRadians(ryaw)), fz = Math.cos(Math.toRadians(ryaw))
            double finalDist = num(c, 'finalDist'), slope = Math.tan(Math.toRadians(num(c, 'glide'))), touch = num(c, 'touch')
            double along = (x - rx) * fx + (z - rz) * fz     // < 0 — ещё до порога
            double cross = (x - rx) * fz - (z - rz) * fx     // боковое смещение от оси (> 0 — левее по курсу)
            if (phase == 'approach') {
                // точка входа в глиссаду — на оси за порогом против курса посадки
                double ax = rx - fx * finalDist, az = rz - fz * finalDist
                targetYaw = heading(ax - x, az - z)
                targetAlt = Math.max(ry + (finalDist + touch) * slope, top + clearance)
                if (Math.hypot(ax - x, az - z) < reach) phase = 'final'
            } else {
                targetYaw = ryaw + clamp(cross * 1.5, -30, 30)
                targetAlt = ry + Math.max(0, touch - along) * slope
                if (along < 0 && top + 6 > targetAlt) targetAlt = top + 6   // огни и постройки перед порогом
                // скорость на глиссаде — тягой: медленнее finalSpeed самолёт проседает (подъём держит только скорость)
                double sp = hs * 20, want = num(c, 'finalSpeed')
                double eng0 = v.getEngineTarget()
                throttle = sp < want - 1 ? Math.min(1.0d, eng0 + 0.2d) : sp > want + 1 ? Math.max(0.2d, eng0 - 0.2d) : eng0
                if (ground) phase = 'rollout'
                else if (along > touch + 300) {                         // перелёт — на второй круг
                    phase = 'approach'
                    c.put('event', [phase: 'go_around', pos: [(int) x, (int) y, (int) z]])
                }
            }
        }
        if (phase == 'rollout') {
            targetYaw = at(rwy, 3)
            throttle = 0
            if (hs < 0.03) phase = 'stopped'
        }
        c.put('phase', phase)

        List<String> keys = []
        if (phase == 'done' || phase == 'stopped') return keys
        double yawErr = wrap(targetYaw - yaw)
        double band = num(c, 'yawBand')
        if (yawErr > band) keys << 'right' else if (yawErr < -band) keys << 'left'
        if (!ground) {
            // pitch < 0 — нос вверх; back — нос вверх, forward — вниз
            double want = clamp(-(targetAlt - y) * num(c, 'kAlt') + vy * num(c, 'kVy'), -num(c, 'maxUp'), num(c, 'maxDown'))
            double err = want - pitch
            if (err < -num(c, 'pitchBand')) keys << 'back' else if (err > num(c, 'pitchBand')) keys << 'forward'
        }
        // тяга: jump — +0,1 за тик, sneak — −0,1 и тормоз
        double eng = v.getEngineTarget()
        if (eng < throttle - 0.05) keys << 'jump'
        else if (eng > throttle + 0.05 || phase == 'rollout') keys << 'sneak'
        if (phase == 'takeoff' && throttle == 0) keys.clear()
        if (t % 20 == 0) {
            c.put('last', [t: t, phase: phase, i: c.get('i'), pos: [Math.round(x), Math.round(y), Math.round(z)],
                           yaw: Math.round(wrap(yaw)), pitch: Math.round(pitch), vy: Math.round(vy * 100) / 100.0,
                           speed: Math.round(hs * 200) / 10.0, eng: eng, alt: Math.round(targetAlt), keys: keys.join(',')])
        }
        return keys.sort()
    }
}

long started = System.nanoTime()
def N = '${bot}'
def c = state['plane_ap.' + N]
if (c == null || c.phase in ['done', 'out', 'failed']) return
def b = gm.bot(N)
def p = b?.player()
def v = p?.getVehicle()
if (!(v instanceof EngineVehicle) || v.getControllingPassenger() != p) {
    c.phase = 'failed'
    c.why = p == null ? 'бота нет' : 'бот не пилот самолёта (сбит, вышел или пересел)'
    if (b?.online()) b.act([[release: 'all']])
    gm.emit([plane_ap: N, phase: 'failed', why: c.why])
    return
}
def keys = PlaneAutopilot.step(c, v, p.serverLevel())
if (c.trace != null && c.t % 40 == 0) c.trace << [(int) v.getX(), (int) v.getY(), (int) v.getZ()]
if (c.event) { gm.emit([plane_ap: N] + c.event); c.event = null }
if (c.phase == 'stopped') {
    c.phase = 'out'
    b.act([[release: 'all'], [press: 'key.immersive_aircraft.dismount']])
    gm.emit([plane_ap: N, phase: 'out', pos: [(int) v.getX(), (int) v.getY(), (int) v.getZ()], ticks: c.t])
    return
}
if (c.phase == 'done') {
    b.act([[release: 'all']])
    gm.emit([plane_ap: N, phase: 'done', pos: [(int) v.getX(), (int) v.getY(), (int) v.getZ()]])
    return
}
// рули — клавиши на срок: каждый запуск продлевает их на every + 1 тик (шаг бот делает в своём следующем тике);
// правило выключилось или упало — клавиши отпускаются сами, а не держат последний ввод
def dropped = (c.keys ?: []) - keys
def steps = dropped ? [[release: dropped]] : []
if (keys) steps << [hold: keys, ticks: c.every + 1]
if (steps) b.act(steps)
c.keys = keys
double ms = (System.nanoTime() - started) / 1e6   // своё время правила: среднее и худшее
c.ms = c.ms == null ? ms : c.ms * 0.98 + ms * 0.02
c.msMax = Math.max(c.msMax ?: 0d, ms)
```

```steps
[
 {
  "method": "bot.spawn",
  "params": {
   "name": "${bot}",
   "pos": [
    "${x}",
    "${y}",
    "${z}"
   ],
   "yaw": "${yaw}",
   "gamemode": "creative"
  }
 },
 {
  "method": "command",
  "params": {
   "commands": [
    "execute at ${bot} rotated ${yaw} 0 run summon ${plane} ^ ^ ^4 {Rotation:[${yaw}f,0f],Tags:[\"ap_${bot}\"]}"
   ]
  }
 },
 {
  "method": "script",
  "params": {
   "code": "${code:board}"
  }
 },
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
   "name": "plane_ap_${bot}",
   "every": 2,
   "script": "${code:autopilot}"
  }
 }
]
```

```check
def p = gm.player('${bot}')
def v = p?.getVehicle()
def c = state['plane_ap.${bot}']
return [ok: v instanceof immersive_aircraft.entity.EngineVehicle && v.getControllingPassenger() == p && c != null && c.phase != 'failed',
        phase: c?.phase]
```
