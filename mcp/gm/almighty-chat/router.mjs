// Что из ленты событий Almighty идёт в сессию ведущего и в каком виде.
// Канал Claude Code (https://code.claude.com/docs/en/channels-reference): событие — content и meta,
// meta — строки с ключами из букв, цифр и «_».

// ответ владельца на запрос разрешения: «да abcde» — номер из пяти букв a–z без l
export const PERMISSION_REPLY = /^\s*(да|нет|yes|no|y|n)\s+([a-km-z]{5})\s*$/i

const YES = new Set(['да', 'yes', 'y'])

/** Слово в общем виде: строчные, ё → е, растянутая буква — одна («ведууущииий» → «ведущий»). */
export function fold (word) {
  return word.toLowerCase().replaceAll('ё', 'е').replace(/(\p{L})\1+/gu, '$1')
}

/** Расстояние Дамерау — Левенштейна не больше 1: одна буква лишняя, пропущена, заменена или две переставлены. */
export function near (a, b) {
  if (a === b) return true
  if (Math.abs(a.length - b.length) > 1) return false
  let i = 0
  while (i < a.length && i < b.length && a[i] === b[i]) i++
  if (a.length > b.length) return a.slice(i + 1) === b.slice(i)
  if (a.length < b.length) return a.slice(i) === b.slice(i + 1)
  return a.slice(i + 1) === b.slice(i + 1) || (a[i] === b[i + 1] && a[i + 1] === b[i] && a.slice(i + 2) === b.slice(i + 2))
}

/**
 * Назван ли ведущий в тексте: слово в общем виде (fold) совпало с именем из names или с точностью до одной опечатки —
 * с именем из typoNames (первая буква — точно). Опечатки — только у длинных имён, у которых соседних слов нет
 * («ведущий»); у коротких и украинских одна буква даёт другое слово («клод» — «клад», «ведучий» — «везучий»).
 */
export function addressed (text, names, typoNames = []) {
  for (const [raw] of text.matchAll(/\p{L}+/gu)) {
    const word = fold(raw)
    if (names.includes(word) || typoNames.some(name => word[0] === name[0] && near(word, name))) return true
  }
  return false
}

export const CONTEXT_HEADER = '[earlier in the game, not forwarded to the game master]'

// правило ленты: номер rule и имя name; несохранившееся при загрузке — spec с именем
function ruleName (e) {
  return e.name ?? e.spec?.name ?? (e.rule !== undefined ? `#${e.rule}` : '?')
}

function ruleMeta (e) {
  return { ...(e.rule !== undefined ? { rule: String(e.rule) } : {}), ...(e.name ? { name: String(e.name) } : {}) }
}

function where (e) {
  return 'x' in e ? { dimension: String(e.dimension), x: String(e.x), y: String(e.y), z: String(e.z) } : {}
}

export class Router {
  /**
   * names — как зовут ведущего в общем чате, typoNames — имена, которые узнаются и с опечаткой; owners — владельцы
   * (их «да <номер>» — ответ на запрос разрешения); botPlayers — боты, которых слушать как игроков (репетиция);
   * conversationMs — сколько после обращения игрока к ведущему или ответа ведущего игроку его сообщения в чате идут
   * без имени; contextLines и contextMs — сколько последних строк мимо ведущего (общий чат, ошибки правил) приложить к
   * следующему сообщению ему; lagMs и lagEveryMs — какой лаг сообщать и как часто.
   */
  constructor ({ names, typoNames = [], owners, botPlayers = [], conversationMs = 180_000, contextLines = 10,
    contextMs = 600_000, lagMs = 2_000, lagEveryMs = 60_000, now = Date.now }) {
    this.typoNames = typoNames.map(fold)
    this.names = [...names.map(fold), ...this.typoNames]
    this.owners = new Set(owners)
    this.botPlayers = new Set(botPlayers)
    this.conversationMs = conversationMs
    this.contextLines = contextLines
    this.contextMs = contextMs
    this.lagMs = lagMs
    this.lagEveryMs = lagEveryMs
    this.now = now
    this.talked = new Map() // игрок → когда открылся разговор с ведущим
    this.waiting = new Set() // чьи сообщения ушли ведущему и ждут его ответа
    this.online = new Set() // кто в игре: люди и боты из botPlayers
    this.backlog = [] // что шло мимо ведущего: {key, line, at}
    this.lastLag = -Infinity
  }

  /** Кто в игре сейчас: при запуске канала и после перезапуска сервера. */
  setOnline (players) {
    this.online = new Set(players)
  }

  /**
   * Ведущий ответил в чат: to — кому лично, без to — всем, и тогда это ответ всем, кто ждал. С ними открывается
   * разговор: их сообщения идут ведущему без имени. Возвращает, кому ответ.
   */
  replied (to) {
    const players = to ?? [...this.waiting]
    for (const p of players) {
      this.talked.set(p, this.now())
      this.waiting.delete(p)
    }
    return players
  }

  /** Событие ленты → null (не нужно), {verdict} (ответ на запрос разрешения) или {content, meta}. */
  route (e) {
    const player = e.player
    if (player !== undefined && e.bot && !this.botPlayers.has(player)) return null
    const owner = this.owners.has(player) ? { owner: 'yes' } : {}
    switch (e.type) {
      case 'gm':
      case 'chat': {
        const text = String(e.text ?? '').trim()
        if (!player || !text) return null
        const verdict = this.owners.has(player) && PERMISSION_REPLY.exec(text)
        if (verdict) {
          return { verdict: { request_id: verdict[2].toLowerCase(), behavior: YES.has(verdict[1].toLowerCase()) ? 'allow' : 'deny' } }
        }
        if (e.type === 'gm') return this.ask(player, text, { kind: 'gm', player, private: 'yes', ...owner, ...where(e) }, true)
        const direct = addressed(text, this.names, this.typoNames)
        const talking = this.now() - (this.talked.get(player) ?? -Infinity) < this.conversationMs
        const alone = [...this.online].every(p => p === player)
        if (!direct && !talking && !alone) return this.aside(null, `${player}: ${text}`)
        return this.ask(player, text, {
          kind: 'chat', player, direct: direct ? 'yes' : 'no', ...(alone ? { alone: 'yes' } : {}), ...owner
        }, direct)
      }
      case 'join':
      case 'leave':
        if (!player) return null
        if (e.type === 'join') this.online.add(player)
        else this.forget(player)
        return { content: `${player} ${e.type === 'join' ? 'joined the server' : 'left the server'}`, meta: { kind: e.type, player, ...owner, ...where(e) } }
      case 'death': {
        if (!player) return null
        const killer = e.killer ? { killer: String(e.killer) } : {}
        return { content: String(e.message ?? `${player} died`), meta: { kind: 'death', player, ...owner, ...killer, ...where(e) } }
      }
      // правило выключилось (ошибки подряд, бюджет, исчерпан limit) — ведущему сразу; его ошибка — со следующим сообщением
      case 'rule.off':
        return { content: `Rule ${ruleName(e)} turned off: ${e.reason ?? ''}`, meta: { kind: 'rule', state: 'off', ...ruleMeta(e) } }
      case 'rule.error':
        return this.aside(`rule ${ruleName(e)}`, `rule ${ruleName(e)}: ${e.error ?? 'error'}${e.line ? ` (line ${e.line})` : ''}`)
      case 'lag': {
        const ms = Number(e.ms)
        if (!(ms >= this.lagMs) || this.now() - this.lastLag < this.lagEveryMs) return null
        this.lastLag = this.now()
        return { content: `Server stalled for ${ms} ms`, meta: { kind: 'lag', ms: String(ms) } }
      }
      default:
        return null
    }
  }

  /** Строка мимо ведущего: придёт со следующим сообщением ему; key — новая строка того же ключа заменяет старую. */
  aside (key, line) {
    if (key) this.backlog = this.backlog.filter(l => l.key !== key)
    this.backlog.push({ key, line, at: this.now() })
    if (this.backlog.length > this.contextLines) this.backlog.shift()
    return null
  }

  /** Сообщение ведущему — с тем, что шло мимо него с прошлого раза; open — обращение открывает разговор. */
  ask (player, text, meta, open) {
    if (open) this.talked.set(player, this.now())
    this.waiting.add(player)
    const recent = this.backlog.filter(l => this.now() - l.at < this.contextMs)
    this.backlog = []
    const context = recent.length ? `\n\n${CONTEXT_HEADER}\n${recent.map(l => l.line).join('\n')}` : ''
    return { content: text + context, meta }
  }

  forget (player) {
    this.online.delete(player)
    this.talked.delete(player)
    this.waiting.delete(player)
  }
}

/**
 * Кто ждёт ответа ведущего — для строки «ведущий думает…» над хотбаром. События, пришедшие, пока сессия занята
 * ходом, копятся и приходят ей следующим ходом (доки каналов, Notification format). Поэтому конец хода (хук Stop)
 * снимает ожидание с тех, чьи события были в этом ходе, а пришедшие во время хода ждут следующего.
 */
export class Turns {
  constructor ({ maxMs = 180_000, now = Date.now } = {}) {
    this.maxMs = maxMs
    this.now = now
    this.busy = false
    this.current = new Map() // игрок → с какого времени ждёт: его событие в идущем ходе
    this.next = new Map() // пришли во время хода: ждут следующего
    this.queued = 0 // сколько событий ждут следующего хода, и без игрока тоже (вход, смерть, лаг)
  }

  /** Событие ушло в сессию; player — чьё сообщение ведущему (у входа, смерти, лага — нет). */
  pushed (player) {
    if (!this.busy) {
      this.busy = true
      if (player) this.current.set(player, this.now())
    } else {
      this.queued++
      if (player) this.next.set(player, this.now())
    }
  }

  /** Ведущий ответил этим игрокам. */
  answered (players) {
    for (const p of players) {
      this.current.delete(p)
      this.next.delete(p)
    }
  }

  /** Ход сессии кончился. */
  stopped () {
    this.current = this.next
    this.next = new Map()
    this.busy = this.queued > 0
    this.queued = 0
  }

  /** Кто ждёт ответа сейчас (не дольше maxMs). */
  waiting () {
    const t = this.now()
    return [...new Set([...this.current, ...this.next].filter(([, at]) => t - at < this.maxMs).map(([p]) => p))]
  }
}
