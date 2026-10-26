// node --test
// Cyrillic messages, names and permission replies below are language-routing fixtures.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CONTEXT_HEADER, Router, Turns, addressed, fold } from './router.mjs'

const NAMES = ['клод', 'клоду', 'ведучий']
const TYPO = ['ведущий', 'ведущему', 'claude']
const make = (now = () => 0) => {
  const r = new Router({ names: NAMES, typoNames: TYPO, owners: ['Steve'], botPlayers: ['Tester'], now })
  r.setOnline(['Steve', 'Alex', 'Sam'])
  return r
}
const named = text => addressed(text, [...NAMES, ...TYPO].map(fold), TYPO.map(fold))

test('GM name in any form: stretched, misspelled, Latin and Cyrillic', () => {
  for (const t of ['Ведущий, привет', 'ведууущииий', 'ВЕДУЩИЙ!!!', 'ведуший помоги', 'вeдущему', 'Привет, клод',
    'клоооод', 'Claude?', 'cluade', 'ведучий, дай меч', 'ведущму скажи']) assert.ok(named(t), t)
  for (const t of ['проведущий', 'я веду машину', 'они ведут бой', 'везучий ты', 'клад нашёл', 'код красный',
    'сведущий человек']) assert.ok(!named(t), t)
})

test('private /gm always arrives with location', () => {
  const out = make().route({ type: 'gm', player: 'Alex', text: 'помоги', dimension: 'minecraft:overworld', x: 1, y: 2, z: 3 })
  assert.deepEqual(out, { content: 'помоги', meta: { kind: 'gm', player: 'Alex', private: 'yes', dimension: 'minecraft:overworld', x: '1', y: '2', z: '3' } })
})

test('chat only with a name or within a conversation', () => {
  let t = 0
  const r = make(() => t)
  assert.equal(r.route({ type: 'chat', player: 'Sam', text: 'всем привет' }), null)
  assert.equal(r.route({ type: 'chat', player: 'Sam', text: 'ведущему: дай меч' }).meta.direct, 'yes')
  t = 30_000
  assert.equal(r.route({ type: 'chat', player: 'Sam', text: 'и щит' }).meta.direct, 'no')
  t = 300_000
  assert.equal(r.route({ type: 'chat', player: 'Sam', text: 'ещё' }), null)
})

test('GM reply opens a conversation for 3 minutes; public replies answer waiting players', () => {
  let t = 0
  const r = make(() => t)
  r.route({ type: 'gm', player: 'Alex', text: 'помоги' })
  t = 170_000
  assert.deepEqual(r.replied(undefined), ['Alex'])
  t = 340_000
  assert.equal(r.route({ type: 'chat', player: 'Alex', text: 'спасибо' }).meta.direct, 'no')
  assert.equal(r.route({ type: 'chat', player: 'Steve', text: 'го на остров' }), null)
  r.replied(['Steve'])
  assert.equal(r.route({ type: 'chat', player: 'Steve', text: 'а мост?' }).meta.direct, 'no')
})

test('a lone player receives all attention', () => {
  const r = make()
  assert.equal(r.route({ type: 'chat', player: 'Steve', text: 'го' }), null)
  r.route({ type: 'leave', player: 'Alex' })
  r.route({ type: 'leave', player: 'Sam' })
  assert.equal(r.route({ type: 'chat', player: 'Steve', text: 'го' }).meta.alone, 'yes')
  r.route({ type: 'join', player: 'Alex' })
  assert.equal(r.route({ type: 'chat', player: 'Steve', text: 'го' }), null)
})

test('public chat not addressed to the GM accompanies its next message once', () => {
  const r = make()
  r.route({ type: 'chat', player: 'Alex', text: 'привет?' })
  r.route({ type: 'chat', player: 'Steve', text: 'го на остров' })
  const out = r.route({ type: 'chat', player: 'Alex', text: 'ведууущииий' })
  assert.equal(out.content, `ведууущииий\n\n${CONTEXT_HEADER}\nAlex: привет?\nSteve: го на остров`)
  assert.equal(r.route({ type: 'gm', player: 'Steve', text: 'дай меч' }).content, 'дай меч')
})

test('only named bots are heard and never counted as players', () => {
  const r = make()
  assert.equal(r.route({ type: 'gm', player: 'TestBot', bot: true, text: 'x' }), null)
  assert.equal(r.route({ type: 'join', player: 'TestBot', bot: true }), null)
  assert.ok(!r.online.has('TestBot'))
  assert.equal(r.route({ type: 'gm', player: 'Tester', bot: true, text: 'x' }).meta.kind, 'gm')
})

test('owner "yes <id>" is a permission reply, not a message', () => {
  const r = make()
  assert.deepEqual(r.route({ type: 'gm', player: 'Steve', text: 'да abcde' }), { verdict: { request_id: 'abcde', behavior: 'allow' } })
  assert.deepEqual(r.route({ type: 'chat', player: 'Steve', text: 'Нет ABCDE' }), { verdict: { request_id: 'abcde', behavior: 'deny' } })
  assert.equal(r.route({ type: 'gm', player: 'Player', text: 'да abcde' }).meta.kind, 'gm')
})

test('lag is reported above the threshold and at most once per minute', () => {
  let t = 0
  const r = make(() => t)
  assert.equal(r.route({ type: 'lag', ms: 500 }), null)
  assert.equal(r.route({ type: 'lag', ms: 9000 }).meta.ms, '9000')
  t = 30_000
  assert.equal(r.route({ type: 'lag', ms: 9000 }), null)
})

test('join, leave, death', () => {
  const r = make()
  assert.equal(r.route({ type: 'join', player: 'Steve', x: 0, y: 0, z: 0, dimension: 'd' }).meta.owner, 'yes')
  assert.equal(r.route({ type: 'death', player: 'Alex', message: 'Alex упал', killer: 'Zombie' }).meta.killer, 'Zombie')
  assert.equal(r.route({ type: 'emit', data: {} }), null)
})

test('"game master is thinking..." until reply or turn end; arrivals during a turn await the next', () => {
  let t = 0
  const turns = new Turns({ maxMs: 180_000, now: () => t })
  turns.pushed('Steve')
  turns.pushed('Alex')
  turns.pushed(null)
  assert.deepEqual(turns.waiting(), ['Steve', 'Alex'])
  turns.answered(['Steve'])
  assert.deepEqual(turns.waiting(), ['Alex'])
  turns.stopped()
  assert.deepEqual(turns.waiting(), ['Alex'])
  turns.stopped()
  assert.deepEqual(turns.waiting(), [])
  turns.pushed('Sam')
  t = 200_000
  assert.deepEqual(turns.waiting(), [])
})

test('disabled rules reach the GM immediately; rule errors accompany the next message as the last line', () => {
  const r = make()
  assert.deepEqual(r.route({ type: 'rule.off', rule: 7, name: 'drone_ap', reason: '10 errors in a row' }),
    { content: 'Rule drone_ap turned off: 10 errors in a row', meta: { kind: 'rule', state: 'off', rule: '7', name: 'drone_ap' } })
  assert.equal(r.route({ type: 'rule.error', rule: 7, name: 'drone_ap', error: 'GroovyCastException', line: 3 }), null)
  assert.equal(r.route({ type: 'rule.error', rule: 7, name: 'drone_ap', error: 'NPE' }), null)
  assert.equal(r.route({ type: 'gm', player: 'Steve', text: 'дрон?' }).content, `дрон?\n\n${CONTEXT_HEADER}\nrule drone_ap: NPE`)
})
