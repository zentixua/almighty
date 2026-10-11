#!/usr/bin/env node
// Канал Claude Code «almighty-chat»: игроки Minecraft говорят с сессией ведущего.
// Лента событий мода Almighty (мост HTTP, метод events с долгим опросом) → notifications/claude/channel;
// запросы разрешений сессии (permission relay) → личное сообщение владельцу в игре, его «да <номер>» — ответ.
// Хуки сессии (hooks/hooks.json, тип http) сообщают каналу ответ ведущего в чат и конец хода: так канал знает,
// с кем идёт разговор и кому показывать над хотбаром «ведущий думает…».
// Контракт канала: https://code.claude.com/docs/en/channels-reference, хуки: https://code.claude.com/docs/en/hooks
// Настройки — переменные среды: ALMIGHTY_URL, ALMIGHTY_TOKEN_FILE, GM_NAMES, GM_TYPO_NAMES, GM_OWNERS, GM_BOT_PLAYERS.
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'
import { CONTEXT_HEADER, Router, Turns } from './router.mjs'

const list = (v, dflt) => (v ?? dflt).split(',').map(s => s.trim()).filter(Boolean)
const URL = (process.env.ALMIGHTY_URL ?? 'http://127.0.0.1:25650').replace(/\/$/, '') + '/rpc'
const TOKEN_FILE = process.env.ALMIGHTY_TOKEN_FILE
const owners = list(process.env.GM_OWNERS, '')
const router = new Router({
  names: list(process.env.GM_NAMES, 'ведучий,ведучому,ведучого,ведучим,ведуча,клод,клода,клоду,клодом,клоде,клодик,' +
    'клауд,клауда,клауду,клаудом,клауде,claud'),
  typoNames: list(process.env.GM_TYPO_NAMES, 'ведущий,ведущая,ведущему,ведущего,ведущим,ведущей,ведущую,claude'),
  owners,
  botPlayers: list(process.env.GM_BOT_PLAYERS, '')
})
const turns = new Turns()
// порт хуков — тот же, что в hooks/hooks.json
const HOOK_PORT = 25680
const THINKING = { text: 'ведущий думает…', color: 'gray', italic: true }

// stdout занят протоколом MCP: журнал — в stderr
const log = (...a) => console.error('[almighty-chat]', ...a)

let token = null
async function bridge (method, params = {}, timeoutMs = 70_000) {
  token ??= readFileSync(TOKEN_FILE, 'utf8').trim()
  const r = await fetch(URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ method, params }),
    signal: AbortSignal.timeout(timeoutMs)
  })
  if (r.status === 401) token = null // токен сменили: перечитать
  const data = await r.json().catch(() => ({ ok: false, error: { message: `HTTP ${r.status}` } }))
  if (!data.ok) throw new Error(`${method}: ${data.error?.code ?? r.status} ${data.error?.message ?? ''}`)
  return data.result
}

const mcp = new Server(
  { name: 'almighty-chat', version: '0.2.0' },
  {
    capabilities: { experimental: { 'claude/channel': {}, 'claude/channel/permission': {} } },
    instructions: [
      'События сервера Minecraft приходят как <channel source="almighty-chat" kind="…" player="…">.',
      'kind="gm" — игрок написал ведущему лично (/gm): ответь лично, mcp__almighty__say с to=[player].',
      'kind="chat" — сообщение в общем чате: direct="yes" — ведущего назвали; direct="no" — разговор с ним идёт',
      '(игрок недавно к нему обращался или получил ответ) или игрок в игре один (alone="yes"): может быть и не тебе,',
      'тогда промолчи. Ответ — в общий чат, mcp__almighty__say без to.',
      'Остальной общий чат и ошибки правил Almighty ведущему отдельно не пересылаются: последние строки приходят под',
      `«${CONTEXT_HEADER}» вместе со следующим сообщением ведущему.`,
      'kind="rule" state="off" — правило Almighty выключилось (rule — номер, name — имя, в тексте — причина).',
      'Пока ведущий не ответил, игрок видит над хотбаром «ведущий думает…» (это делает канал).',
      'kind="join", "leave", "death" — игрок вошёл, вышел, погиб (x y z dimension — где; killer — кто убил).',
      'kind="lag" — сервер подвис на ms миллисекунд. kind="server" — мост или сервер перезапустился.',
      'owner="yes" — владелец сервера. Игрок ждёт: сначала короткий ответ в игре, долгое — в фоне.',
      'Запросы разрешений канал шлёт владельцу в игру; он отвечает «да <номер>» или «нет <номер>».'
    ].join('\n')
  }
)

// Запрос разрешения (хук ответил «ask») — каждому владельцу в игре лично (кого нет в игре, тому мост откажет);
// ответ придёт его сообщением «да <номер>»
mcp.setNotificationHandler(z.object({
  method: z.literal('notifications/claude/channel/permission_request'),
  params: z.object({ request_id: z.string(), tool_name: z.string(), description: z.string(), input_preview: z.string() })
}), async ({ params }) => {
  const preview = params.input_preview.length > 300 ? params.input_preview.slice(0, 300) + '…' : params.input_preview
  const text = `Ведущий просит разрешения: ${params.tool_name.replace(/^mcp__\w+?__/, '')} ${preview} ` +
    `— ответь /gm да ${params.request_id} или /gm нет ${params.request_id}`
  await Promise.all(owners.map(owner => bridge('say', { text, to: [owner], style: 'chat' })
    .catch(e => log(`запрос разрешения ${owner}:`, e.message))))
})

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function push (content, meta) {
  await mcp.notification({ method: 'notifications/claude/channel', params: { content, meta } })
  const asks = meta.kind === 'gm' || meta.kind === 'chat'
  turns.pushed(asks ? meta.player : null)
  if (asks) think(meta.player)
}

// «ведущий думает…» над хотбаром: строка гаснет через 3 с, поэтому — каждые 2 с, пока игрок ждёт ответа
function think (player) {
  if (!router.online.has(player)) return
  bridge('say', { component: THINKING, to: [player], style: 'actionbar' }, 5_000).catch(e => log('думает:', e.message))
}
setInterval(() => turns.waiting().forEach(think), 2_000)

// кто в игре: люди и боты, которых слушать как игроков
async function syncOnline () {
  const [players, bots] = await Promise.all([bridge('players'), bridge('bots')])
  const own = new Set(bots.map(b => b.name).filter(name => !router.botPlayers.has(name)))
  router.setOnline(players.map(p => p.name).filter(name => !own.has(name)))
}

// хуки сессии: ответ ведущего в чат (PostToolUse на say) и конец его хода (Stop)
function onHook (h) {
  if (h.hook_event_name === 'Stop') {
    turns.stopped()
  } else if (h.hook_event_name === 'PostToolUse' && !h.agent_id) {
    const { style, to } = h.tool_input ?? {}
    const players = Array.isArray(to) ? to : undefined
    // ответ в чат открывает разговор; своя строка над хотбаром заменяет «думает…»
    switch (String(style ?? 'chat').toLowerCase()) {
      case 'chat': turns.answered(router.replied(players)); break
      case 'actionbar': turns.answered(players ?? turns.waiting())
    }
  }
}

createServer((req, res) => {
  let body = ''
  req.setEncoding('utf8')
  req.on('data', chunk => { body += chunk })
  req.on('end', () => {
    try {
      onHook(JSON.parse(body))
    } catch (e) {
      log('хук:', e.message)
    }
    res.end()
  })
}).on('error', e => log(`хуки на 127.0.0.1:${HOOK_PORT} не слушаются:`, e.message)).listen(HOOK_PORT, '127.0.0.1')

async function follow () {
  let after = 0
  let boot = null
  let first = true
  let down = false
  let pause = 2_000
  for (;;) {
    let page
    try {
      page = await bridge('events', { after, wait: first ? 0 : 25, limit: first ? 1000 : 500 }, 40_000)
    } catch (e) {
      if (!down) {
        log('мост недоступен:', e.message)
        await push('Мост Almighty недоступен: сервер выключен или перезапускается', { kind: 'server', state: 'down' })
        down = true
      }
      await sleep(pause)
      pause = Math.min(pause * 2, 30_000)
      continue
    }
    if (down) {
      await push('Мост Almighty снова на связи', { kind: 'server', state: 'up' })
      down = false
      pause = 2_000
    }
    if (boot !== null && page.boot !== boot) await push('Сервер перезапущен', { kind: 'server', state: 'restarted' })
    if (page.boot !== boot) await syncOnline().catch(e => log('кто в игре:', e.message))
    boot = page.boot
    after = page.next
    if (first) { // старое не ново: события — с минуты запуска канала
      if (page.events.length < 1000) first = false
      continue
    }
    for (const e of page.events) {
      const out = router.route(e)
      if (!out) continue
      if (out.verdict) await mcp.notification({ method: 'notifications/claude/channel/permission', params: out.verdict })
      else await push(out.content, out.meta)
    }
  }
}

await mcp.connect(new StdioServerTransport())
log(`мост ${URL}, владельцы: ${owners.join(', ') || 'нет'}`)
follow().catch(e => { log('лента упала:', e); process.exit(1) })
