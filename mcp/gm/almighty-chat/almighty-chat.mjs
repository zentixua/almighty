#!/usr/bin/env node
// Claude Code "almighty-chat" channel: Minecraft players talk to the GM session.
// Almighty mod event feed (HTTP bridge, long-polling events method) to notifications/claude/channel;
// session permission requests (permission relay) to a private in-game owner message; "yes <id>" answers it.
// Session hooks (hooks/hooks.json, http type) report GM chat replies and turn end, so the channel knows
// who is in a conversation and who should see "game master is thinking..." above the hotbar.
// Channel contract: https://code.claude.com/docs/en/channels-reference; hooks: https://code.claude.com/docs/en/hooks
// Settings are environment variables: ALMIGHTY_URL, ALMIGHTY_TOKEN_FILE, GM_LANG, GM_NAMES, GM_TYPO_NAMES, GM_OWNERS,
// GM_BOT_PLAYERS.
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'
import { CONTEXT_HEADER, Router, Turns } from './router.mjs'

// An empty variable is treated as unset: .mcp.json passes unset values as "${X:-}".
const list = (v, dflt) => (v || dflt).split(',').map(s => s.trim()).filter(Boolean)
const URL = (process.env.ALMIGHTY_URL || 'http://127.0.0.1:25650').replace(/\/$/, '') + '/rpc'
const TOKEN_FILE = process.env.ALMIGHTY_TOKEN_FILE
// Player-facing language (GM_LANG: en by default, or ru): default GM chat names, hotbar line,
// and owner permission request. Owner replies are understood in both languages (router.mjs).
const TEXTS = {
  en: {
    names: 'claude,claud',
    typoNames: '',
    thinking: 'the game master is thinking…',
    permission: (tool, preview, id) => `The game master asks for permission: ${tool} ${preview} — answer /gm yes ${id} or /gm no ${id}`
  },
  ru: {
    names: 'ведучий,ведучому,ведучого,ведучим,ведуча,клод,клода,клоду,клодом,клоде,клодик,' +
      'клауд,клауда,клауду,клаудом,клауде,claud',
    typoNames: 'ведущий,ведущая,ведущему,ведущего,ведущим,ведущей,ведущую,claude',
    thinking: 'ведущий думает…',
    permission: (tool, preview, id) => `Ведущий просит разрешения: ${tool} ${preview} — ответь /gm да ${id} или /gm нет ${id}`
  }
}
const LANG = (process.env.GM_LANG || 'en').toLowerCase().slice(0, 2)
const T = Object.hasOwn(TEXTS, LANG) ? TEXTS[LANG] : TEXTS.en
const owners = list(process.env.GM_OWNERS, '')
const router = new Router({
  names: list(process.env.GM_NAMES, T.names),
  typoNames: list(process.env.GM_TYPO_NAMES, T.typoNames),
  owners,
  botPlayers: list(process.env.GM_BOT_PLAYERS, '')
})
const turns = new Turns()
// Hook port matches hooks/hooks.json.
const HOOK_PORT = 25680
const THINKING = { text: T.thinking, color: 'gray', italic: true }

// stdout carries the MCP protocol; logs go to stderr.
const log = (...a) => console.error('[almighty-chat]', ...a)

let token = null
async function bridge (method, params = {}, timeoutMs = 70_000) {
  if (!TOKEN_FILE) throw new Error('ALMIGHTY_TOKEN_FILE is not set (config/almighty/token on the server)')
  token ??= readFileSync(TOKEN_FILE, 'utf8').trim()
  const r = await fetch(URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ method, params }),
    signal: AbortSignal.timeout(timeoutMs)
  })
  if (r.status === 401) token = null // Token changed: read it again.
  const data = await r.json().catch(() => ({ ok: false, error: { message: `HTTP ${r.status}` } }))
  if (!data.ok) throw new Error(`${method}: ${data.error?.code ?? r.status} ${data.error?.message ?? ''}`)
  return data.result
}

const mcp = new Server(
  { name: 'almighty-chat', version: '0.3.0' },
  {
    capabilities: { experimental: { 'claude/channel': {}, 'claude/channel/permission': {} } },
    instructions: [
      'Minecraft server events arrive as <channel source="almighty-chat" kind="…" player="…">.',
      'kind="gm": a player wrote to the game master privately (/gm); answer privately with mcp__almighty__say and to=[player].',
      'kind="chat": a message in the public chat. direct="yes": the game master was named; direct="no": a conversation',
      'with the game master is going on (the player addressed it or got an answer recently), or the player is alone in the',
      'game (alone="yes"); the message may not be for you, and then stay silent. Answer in the public chat with',
      'mcp__almighty__say without to.',
      'The rest of the public chat and Almighty rule errors are not forwarded on their own: the latest lines arrive under',
      `"${CONTEXT_HEADER}" together with the next message to the game master.`,
      'kind="rule" state="off": an Almighty rule was turned off (rule is its number, name its name, the text gives the reason).',
      'Until you answer, the player sees a "thinking" line above the hotbar (the channel shows it).',
      'kind="join", "leave", "death": a player joined, left, died (x y z dimension: where; killer: who killed them).',
      'kind="lag": the server stalled for ms milliseconds. kind="server": the bridge or the server restarted.',
      'owner="yes": the server owner. The player is waiting: give a short answer in the game first, do long work in the background.',
      'The channel sends permission requests to the owner in the game; the owner answers "yes <id>" or "no <id>" ("да"/"нет" also work).'
    ].join('\n')
  }
)

// Permission request (hook returned "ask") sent privately in-game to every owner (the bridge rejects offline recipients);
// the reply arrives as their "yes <id>" message.
mcp.setNotificationHandler(z.object({
  method: z.literal('notifications/claude/channel/permission_request'),
  params: z.object({ request_id: z.string(), tool_name: z.string(), description: z.string(), input_preview: z.string() })
}), async ({ params }) => {
  const preview = params.input_preview.length > 300 ? params.input_preview.slice(0, 300) + '…' : params.input_preview
  const text = T.permission(params.tool_name.replace(/^mcp__\w+?__/, ''), preview, params.request_id)
  await Promise.all(owners.map(owner => bridge('say', { text, to: [owner], style: 'chat' })
    .catch(e => log(`permission request to ${owner}:`, e.message))))
})

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function push (content, meta) {
  await mcp.notification({ method: 'notifications/claude/channel', params: { content, meta } })
  const asks = meta.kind === 'gm' || meta.kind === 'chat'
  turns.pushed(asks ? meta.player : null)
  if (asks) think(meta.player)
}

// The "game master is thinking..." hotbar line expires after 3 s; refresh every 2 s while a player waits.
function think (player) {
  if (!router.online.has(player)) return
  bridge('say', { component: THINKING, to: [player], style: 'actionbar' }, 5_000).catch(e => log('thinking line:', e.message))
}
setInterval(() => turns.waiting().forEach(think), 2_000)

// Online humans and bots treated as players.
async function syncOnline () {
  const [players, bots] = await Promise.all([bridge('players'), bridge('bots')])
  const own = new Set(bots.map(b => b.name).filter(name => !router.botPlayers.has(name)))
  router.setOnline(players.map(p => p.name).filter(name => !own.has(name)))
}

// Session hooks: GM chat reply (PostToolUse on say) and turn end (Stop).
function onHook (h) {
  if (h.hook_event_name === 'Stop') {
    turns.stopped()
  } else if (h.hook_event_name === 'PostToolUse' && !h.agent_id) {
    const { style, to } = h.tool_input ?? {}
    const players = Array.isArray(to) ? to : undefined
    // A chat reply opens a conversation; a custom hotbar line replaces "thinking...".
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
      log('hook:', e.message)
    }
    res.end()
  })
}).on('error', e => log(`cannot listen for hooks on 127.0.0.1:${HOOK_PORT}:`, e.message)).listen(HOOK_PORT, '127.0.0.1')

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
        log('bridge unreachable:', e.message)
        await push('Almighty bridge unreachable: the server is off or restarting', { kind: 'server', state: 'down' })
        down = true
      }
      await sleep(pause)
      pause = Math.min(pause * 2, 30_000)
      continue
    }
    if (down) {
      await push('Almighty bridge is reachable again', { kind: 'server', state: 'up' })
      down = false
      pause = 2_000
    }
    if (boot !== null && page.boot !== boot) await push('Server restarted', { kind: 'server', state: 'restarted' })
    if (page.boot !== boot) await syncOnline().catch(e => log('online players:', e.message))
    boot = page.boot
    after = page.next
    if (first) { // Old events are not new: start with the minute the channel launched.
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
log(`bridge ${URL}, owners: ${owners.join(', ') || 'none'}`)
follow().catch(e => { log('feed failed:', e); process.exit(1) })
