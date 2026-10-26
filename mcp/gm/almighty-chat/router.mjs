// Which Almighty feed events reach the GM session, and in what form.
// Claude Code channel (https://code.claude.com/docs/en/channels-reference): an event has content and meta;
// meta values are strings with keys made of letters, digits and "_".

// Owner reply to a permission request: "yes abcde", an id of five a-z letters excluding l.
export const PERMISSION_REPLY = /^\s*(да|нет|yes|no|y|n)\s+([a-km-z]{5})\s*$/i

const YES = new Set(['да', 'yes', 'y'])

/** Normalized word: lowercase, ё → е, repeated letters folded ("ведууущииий" → "ведущий"). */
export function fold (word) {
  return word.toLowerCase().replaceAll('ё', 'е').replace(/(\p{L})\1+/gu, '$1')
}

/** Damerau-Levenshtein distance at most 1: one extra, missing or replaced letter, or two transposed letters. */
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
 * Whether the text names the GM: a normalized word (fold) matches a name from names, or matches typoNames
 * with at most one typo (first letter exact). Typos apply only to long names without neighboring words
 * ("ведущий"); short and Ukrainian names can become another word with one letter
 * ("клод" to "клад", "ведучий" to "везучий").
 */
export function addressed (text, names, typoNames = []) {
  for (const [raw] of text.matchAll(/\p{L}+/gu)) {
    const word = fold(raw)
    if (names.includes(word) || typoNames.some(name => word[0] === name[0] && near(word, name))) return true
  }
  return false
}

export const CONTEXT_HEADER = '[earlier in the game, not forwarded to the game master]'

// Feed rule: rule id and name; if not loaded at startup, spec carries the name.
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
   * names: GM names in public chat; typoNames: names also recognized with a typo; owners: server owners
   * (their "yes <id>" replies answer permission requests); botPlayers: bots treated as players (rehearsal);
   * conversationMs: how long after a player addresses the GM or receives a reply their chat reaches the GM
   * without a name; contextLines and contextMs: how many recent lines not addressed to the GM (public chat,
   * rule errors) accompany the next message; lagMs and lagEveryMs: which stalls to report and how often.
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
    this.talked = new Map() // Player to conversation start time with the GM.
    this.waiting = new Set() // Players whose messages reached the GM and await a reply.
    this.online = new Set() // Online humans and bots listed in botPlayers.
    this.backlog = [] // Lines not addressed to the GM: {key, line, at}.
    this.lastLag = -Infinity
  }

  /** Who is online now: at channel startup and after a server restart. */
  setOnline (players) {
    this.online = new Set(players)
  }

  /**
   * The GM replied in chat: to selects private recipients; absent to means everyone, answering all waiting
   * players. Open a conversation so their messages reach the GM without its name. Return the recipients.
   */
  replied (to) {
    const players = to ?? [...this.waiting]
    for (const p of players) {
      this.talked.set(p, this.now())
      this.waiting.delete(p)
    }
    return players
  }

  /** Feed event to null (unneeded), {verdict} (permission reply), or {content, meta}. */
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
      // A disabled rule (consecutive errors, budget, exhausted limit) reaches the GM immediately; its error accompanies the next message.
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

  /** Line not addressed to the GM: send with the next message; key means a new line replaces the old line with that key. */
  aside (key, line) {
    if (key) this.backlog = this.backlog.filter(l => l.key !== key)
    this.backlog.push({ key, line, at: this.now() })
    if (this.backlog.length > this.contextLines) this.backlog.shift()
    return null
  }

  /** Message to the GM with lines not addressed to it since last time; open means the address starts a conversation. */
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
 * Players awaiting a GM reply, for the "game master is thinking..." line above the hotbar. Events arriving
 * while the session is busy with a turn are queued for the next turn (channel docs, Notification format).
 * Therefore turn end (Stop hook) clears waiting players whose events belonged to this turn;
 * those arriving during the turn await the next one.
 */
export class Turns {
  constructor ({ maxMs = 180_000, now = Date.now } = {}) {
    this.maxMs = maxMs
    this.now = now
    this.busy = false
    this.current = new Map() // Player to waiting start time: their event is in the current turn.
    this.next = new Map() // Arrived during the turn: await the next one.
    this.queued = 0 // Events awaiting the next turn, including events without a player (join, death, lag).
  }

  /** Event sent to the session; player identifies a message to the GM (absent for join, death, lag). */
  pushed (player) {
    if (!this.busy) {
      this.busy = true
      if (player) this.current.set(player, this.now())
    } else {
      this.queued++
      if (player) this.next.set(player, this.now())
    }
  }

  /** The GM replied to these players. */
  answered (players) {
    for (const p of players) {
      this.current.delete(p)
      this.next.delete(p)
    }
  }

  /** The session turn ended. */
  stopped () {
    this.current = this.next
    this.next = new Map()
    this.busy = this.queued > 0
    this.queued = 0
  }

  /** Who is waiting now (no longer than maxMs). */
  waiting () {
    const t = this.now()
    return [...new Set([...this.current, ...this.next].filter(([, at]) => t - at < this.maxMs).map(([p]) => p))]
  }
}
