// node --test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CONTEXT_HEADER, Router, Turns, addressed, fold } from './router.mjs'

const NAMES = ['клод', 'клоду', 'ведучий']
const TYPO = ['ведущий', 'ведущему', 'claude']
const make = (now = () => 0) => {
  const r = new Router({ names: NAMES, typoNames: TYPO, owners: ['ZentixUA'], botPlayers: ['Tester'], now })
  r.setOnline(['ZentixUA', 'ENOTzRPG', 'Wally'])
  return r
}
const named = text => addressed(text, [...NAMES, ...TYPO].map(fold), TYPO.map(fold))

test('имя ведущего в любом виде: растянутое, с опечаткой, латиницей и кириллицей', () => {
  for (const t of ['Ведущий, привет', 'ведууущииий', 'ВЕДУЩИЙ!!!', 'ведуший помоги', 'вeдущему', 'Привет, клод',
    'клоооод', 'Claude?', 'cluade', 'ведучий, дай меч', 'ведущму скажи']) assert.ok(named(t), t)
  for (const t of ['проведущий', 'я веду машину', 'они ведут бой', 'везучий ты', 'клад нашёл', 'код красный',
    'сведущий человек']) assert.ok(!named(t), t)
})

test('личное /gm идёт всегда, с местом', () => {
  const out = make().route({ type: 'gm', player: 'ENOTzRPG', text: 'помоги', dimension: 'minecraft:overworld', x: 1, y: 2, z: 3 })
  assert.deepEqual(out, { content: 'помоги', meta: { kind: 'gm', player: 'ENOTzRPG', private: 'yes', dimension: 'minecraft:overworld', x: '1', y: '2', z: '3' } })
})

test('чат — только с именем или в разговоре', () => {
  let t = 0
  const r = make(() => t)
  assert.equal(r.route({ type: 'chat', player: 'Wally', text: 'всем привет' }), null)
  assert.equal(r.route({ type: 'chat', player: 'Wally', text: 'ведущему: дай меч' }).meta.direct, 'yes')
  t = 30_000
  assert.equal(r.route({ type: 'chat', player: 'Wally', text: 'и щит' }).meta.direct, 'no')
  t = 300_000
  assert.equal(r.route({ type: 'chat', player: 'Wally', text: 'ещё' }), null)
})

test('ответ ведущего открывает разговор на 3 минуты; ответ всем — тем, кто ждал', () => {
  let t = 0
  const r = make(() => t)
  r.route({ type: 'gm', player: 'ENOTzRPG', text: 'помоги' })
  t = 170_000
  assert.deepEqual(r.replied(undefined), ['ENOTzRPG'])
  t = 340_000
  assert.equal(r.route({ type: 'chat', player: 'ENOTzRPG', text: 'спасибо' }).meta.direct, 'no')
  assert.equal(r.route({ type: 'chat', player: 'ZentixUA', text: 'го на остров' }), null)
  r.replied(['ZentixUA'])
  assert.equal(r.route({ type: 'chat', player: 'ZentixUA', text: 'а мост?' }).meta.direct, 'no')
})

test('игрок в игре один — всё ему', () => {
  const r = make()
  assert.equal(r.route({ type: 'chat', player: 'ZentixUA', text: 'го' }), null)
  r.route({ type: 'leave', player: 'ENOTzRPG' })
  r.route({ type: 'leave', player: 'Wally' })
  assert.equal(r.route({ type: 'chat', player: 'ZentixUA', text: 'го' }).meta.alone, 'yes')
  r.route({ type: 'join', player: 'ENOTzRPG' })
  assert.equal(r.route({ type: 'chat', player: 'ZentixUA', text: 'го' }), null)
})

test('общий чат мимо ведущего приходит со следующим сообщением ему, один раз', () => {
  const r = make()
  r.route({ type: 'chat', player: 'ENOTzRPG', text: 'привет?' })
  r.route({ type: 'chat', player: 'ZentixUA', text: 'го на остров' })
  const out = r.route({ type: 'chat', player: 'ENOTzRPG', text: 'ведууущииий' })
  assert.equal(out.content, `ведууущииий\n\n${CONTEXT_HEADER}\nENOTzRPG: привет?\nZentixUA: го на остров`)
  assert.equal(r.route({ type: 'gm', player: 'ZentixUA', text: 'дай меч' }).content, 'дай меч')
})

test('боты — только названные и в игроков не считаются', () => {
  const r = make()
  assert.equal(r.route({ type: 'gm', player: 'Jet_Watch', bot: true, text: 'x' }), null)
  assert.equal(r.route({ type: 'join', player: 'Jet_Watch', bot: true }), null)
  assert.ok(!r.online.has('Jet_Watch'))
  assert.equal(r.route({ type: 'gm', player: 'Tester', bot: true, text: 'x' }).meta.kind, 'gm')
})

test('«да <номер>» владельца — ответ на запрос, не сообщение', () => {
  const r = make()
  assert.deepEqual(r.route({ type: 'gm', player: 'ZentixUA', text: 'да abcde' }), { verdict: { request_id: 'abcde', behavior: 'allow' } })
  assert.deepEqual(r.route({ type: 'chat', player: 'ZentixUA', text: 'Нет ABCDE' }), { verdict: { request_id: 'abcde', behavior: 'deny' } })
  assert.equal(r.route({ type: 'gm', player: 'ClyuDan', text: 'да abcde' }).meta.kind, 'gm')
})

test('лаг — от порога и не чаще раза в минуту', () => {
  let t = 0
  const r = make(() => t)
  assert.equal(r.route({ type: 'lag', ms: 500 }), null)
  assert.equal(r.route({ type: 'lag', ms: 9000 }).meta.ms, '9000')
  t = 30_000
  assert.equal(r.route({ type: 'lag', ms: 9000 }), null)
})

test('вход, выход, смерть', () => {
  const r = make()
  assert.equal(r.route({ type: 'join', player: 'ZentixUA', x: 0, y: 0, z: 0, dimension: 'd' }).meta.owner, 'yes')
  assert.equal(r.route({ type: 'death', player: 'ENOTzRPG', message: 'ENOTzRPG упал', killer: 'Zombie' }).meta.killer, 'Zombie')
  assert.equal(r.route({ type: 'emit', data: {} }), null)
})

test('«ведущий думает…»: до ответа или конца хода; пришедшие во время хода ждут следующего', () => {
  let t = 0
  const turns = new Turns({ maxMs: 180_000, now: () => t })
  turns.pushed('ZentixUA')
  turns.pushed('ENOTzRPG')
  turns.pushed(null)
  assert.deepEqual(turns.waiting(), ['ZentixUA', 'ENOTzRPG'])
  turns.answered(['ZentixUA'])
  assert.deepEqual(turns.waiting(), ['ENOTzRPG'])
  turns.stopped()
  assert.deepEqual(turns.waiting(), ['ENOTzRPG'])
  turns.stopped()
  assert.deepEqual(turns.waiting(), [])
  turns.pushed('Wally')
  t = 200_000
  assert.deepEqual(turns.waiting(), [])
})

test('правило выключилось — ведущему сразу; ошибки правила — последней строкой со следующим сообщением', () => {
  const r = make()
  assert.deepEqual(r.route({ type: 'rule.off', rule: 7, name: 'drone_ap', reason: '10 errors in a row' }),
    { content: 'Rule drone_ap turned off: 10 errors in a row', meta: { kind: 'rule', state: 'off', rule: '7', name: 'drone_ap' } })
  assert.equal(r.route({ type: 'rule.error', rule: 7, name: 'drone_ap', error: 'GroovyCastException', line: 3 }), null)
  assert.equal(r.route({ type: 'rule.error', rule: 7, name: 'drone_ap', error: 'NPE' }), null)
  assert.equal(r.route({ type: 'gm', player: 'ZentixUA', text: 'дрон?' }).content, `дрон?\n\n${CONTEXT_HEADER}\nrule drone_ap: NPE`)
})
