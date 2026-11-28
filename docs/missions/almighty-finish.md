# Mission almighty-finish: report

Mission: almighty-finish. Goal (Artem, 27.11): the mod fully as agreed, in order, "it lets you do literally
everything". Source of truth: `docs/design/plan-1.0.md`, `plan-1.0-critique.md`, `decisions-1.0.md`, and the earlier
reports (`almighty-core-report.md`, `almighty-agent.md`, `almighty-ports.md` with its done/partial/missing list).

Nothing was released, tagged, uploaded or posted; no live server or world was touched. README*, `docs/guide`
(except the generated `docs/guide/tools.md`, which CI regenerates from `tools.json`), `docs/store`, `scripts/store.py`,
`publish.yml` and `modrinth.sh` were not edited.

## 1. The independent audit

Three fresh subagents that had not written the code got only the plan, the critique, the decisions and the code (no
earlier report) and read it end to end on all five nodes (1.21.1 NeoForge; 26.1.2 and 26.3 on NeoForge and Fabric).
Their full findings, with file:line at the audited commit, are kept beside this report:

- [audit-rights.md](almighty-finish/audit-rights.md): anything that lets a player, an agent below owner or untrusted
  text exceed its rights. 2 blockers, 7 major, 10 minor.
- [audit-stability.md](almighty-finish/audit-stability.md): lag, crashes, data loss, broken undo. 3 blockers,
  10 major, 11 minor.
- [audit-promises.md](almighty-finish/audit-promises.md): broken promises in tool descriptions, notes and
  messages, and the plan's coverage step by step. 17 broken promises; the step and scenario table.

Every fix PR then had its own fresh review round (a subagent given the diff and the surrounding code, never the
author's reasoning). Those reviews found further serious problems in several fixes; each was fixed before merge.

## 2. What was fixed (PRs, all merged after green CI on all five nodes and one review round)

| PR | What | Audit items |
|---|---|---|
| [#77](https://github.com/zentixua/almighty/pull/77) | Plain HTTP off loopback refused unless `bridge.allow_plain_http_off_loopback = true` (lead's TLS decision); the `command` tool no longer claims level 4 | plan 7.2, R17; promises 3, 6 |
| [#78](https://github.com/zentixua/almighty/pull/78) | Per-agent fairness: `calls_per_minute` (1200), an equal tick share between agents' jobs, `bot_minutes` (240 an hour below owner), scripts 50 ms and rule `budget_ms` ≤ 2.5 below owner | S6, F2, F3, F12, F14; stability M4, M6, M7 |
| [#79](https://github.com/zentixua/almighty/pull/79) | Tools reach the paths below owner: declarative rules (`when`/`then`/`ttl_hours`), approved scripts (`script {name}`), `events {mine}`, `build {undo_change}`; Fabric rule examples; true tool and note texts | promises 1, 2, 3, 4, 5, 9, 12 |
| [#80](https://github.com/zentixua/almighty/pull/80) | Policy keys `timezone` and `[safety] privacy_notice` | plan 4.5 |
| [#81](https://github.com/zentixua/almighty/pull/81) | Undo never destroys later work (job undo conflict-aware, filled containers kept), the journal keeps chest contents emptied before a write (`ClearableMixin`), one call's fills bounded (131 072 positions, "use build") | stability B1, B2, B3 |
| [#82](https://github.com/zentixua/almighty/pull/82) | Forged prompts and hidden commands below owner: every escape undone, the name read from the letters a component shows, exact click actions, 26.x dialog clicks and the `dialog` command, interpreted nbt, continued function lines, SNBT macro args | rights B1, B2, M1 |
| [#83](https://github.com/zentixua/almighty/pull/83) | Player-written text on pull reads marked `untrusted` (entity and item names, bot inbox); a free camera and map markers show no players below `observe.players` | plan 4.11/P4; rights M4, M5 (names) |
| [#84](https://github.com/zentixua/almighty/pull/84) | `status` connection check: preset, owners, identity, online mode, permission handler, claim mods, Bedrock, TLS, and problems with plain fixes | plan 9.1 step 5 |
| [#85](https://github.com/zentixua/almighty/pull/85) | Confirmations open a vanilla dialog on 26.x (Allow, Deny, Later) | step 18, B2, R7 |
| [#86](https://github.com/zentixua/almighty/pull/86) | Claims on Fabric through the Common Protection API (area query first, then every column) | step 19, R9 |
| [#87](https://github.com/zentixua/almighty/pull/87) | Rights below owner: self-only targets in every spelling (quoted names and UUIDs, short and Floodgate names, `execute` targets of safe commands), filled rule commands re-checked at each fire, another agent's bot needs `bots.admin`, another agent's area `undo.any`, `/almighty confirm` takes the player's own typing | rights M2, M3, M7, m1 |
| [#88](https://github.com/zentixua/almighty/pull/88) | `ask_admin` for the agent's own calls above its floor (one ask per agent per 10 minutes); owners told every admin decision | plan 4.8 item 5; O12, O13 |
| [#89](https://github.com/zentixua/almighty/pull/89) | Stability: idempotency keeps digests and 256 keys per agent; per-agent caps (32 jobs, 16 prepared areas); revert grouped off the server thread; `/almighty undo` reports failures, no double revert; no 2 s permission wait on Fabric | stability M1, M2, M3, M5, M10 |
| [#90](https://github.com/zentixua/almighty/pull/90) | Commands below owner respect claims; boxes over 4096 chunks refused; relative places after a move need their own call; cross-dimension clone and `function`/`schedule` refused while places are protected; `summon` and `loot` count as edits | rights M6 |
| [#91](https://github.com/zentixua/almighty/pull/91) | Per-agent `profile`/`images` from `policy.toml` (the URL overrides) | promises 7; U5 |
| [#92](https://github.com/zentixua/almighty/pull/92) | Player-facing prompts, receipts and outcomes in Russian too | promises 10 |

## 3. Plan coverage after the fixes

D done, P partial, M missing, A changed on purpose (reason given). Evidence: the test or file that backs it.

| Item | State | Evidence or gap |
|---|---|---|
| 1 Bridge hygiene | D | `BridgeTest`, `AgentsTest`, `AuthGuardTest`; per-agent call rate `CallRateTest` (#78) |
| 2 Deny list, lag guard, budgeted world calls, idempotency | D | `DenyListTest`, `DispatchTest` (+ per-agent keys, #89), `LagGuardTest`, `GmSafetyGameTests`; continued lines and control characters (#82) |
| 3 Feed identity, addressing, player text as data | D | `GmInboxGameTests`; pull reads marked (#83). Sign and book text is never returned by any read |
| 4 Audit, pause | A | Own writer, not log4j2 (AGENTS.md gives the reason: an appender changes the server's global logging) |
| 5 Gate | D | `GateTest`, `GmGateGameTests`; forgery (#82), observe.players on views (#83), others' bots and areas (#87) |
| 6 Origins and confirmations | D | `ConfirmationsTest`, `GmConfirmGameTests`; dialogs on 26.x (#85); agent-origin ask_admin and owner notices (#88). MCP elicitation: M (no recorded client advertises it) |
| 7 Content limits | D | `GmLimitsGameTests` |
| 8 Change journal, conflict-aware undo | D | `GmJournalGameTests`, `jobUndoLeavesLaterWork`, `changeUndoRestoresChestContents` (#81) |
| 9 Presets, zones, ladder, quotas, claims | P | Done: `LadderTest`, `GmLadderGameTests`, calls and bot minutes (#78), claims for commands (#90). M: LuckPerms integer nodes `almighty.quota.*` and the `almighty:zone` context |
| 10 Config by asking (`file`) | P | `policy.toml` and scripts only; the owner's `config/almighty/notes/` and roles are not reachable by `file` (O17 "make Bob a builder" needs LuckPerms or `/almighty role`) |
| 11 Declarative rules, approved scripts | D | Server side `DeclarativeTest`, `GmRuleGameTests`; reachable through the tools (#79); fire-time re-checks (#87, #90) |
| 12 MCP, profiles, images, TLS | D | `McpProtocolTest`, `McpEndpointTest` (per-agent profile, #91), `TlsTest` (refusal, #77). Self-signed certificates: A (the JDK has no public certificate builder; owners bring a PEM or a proxy) |
| 12b Sign-in, `/almighty connect` | P | `SignInTest`, `SignInHttpTest`, `OwnerAppTest`. M: `allow_from` per agent (F1) |
| 13 Catalog, notes | P | `CatalogTest`, `NotesTest`. M: loot tables, advancements and recipe types in the catalog; per-mod exclusion |
| 14 Plugin | D | `scripts/export_skills.py --check` in CI |
| 17–19 Ports, Fabric | D | CI: build, unit tests and GameTest on all five nodes for every PR; Common Protection API (#86) |
| 9.1 Connection check | D | `ConnectionCheckTest`, live in `GmGateGameTests` (#84) |
| 4.5 Policy keys | D | `timezone`, `privacy_notice` (#80), per-agent `profile`/`images` (#91) |
| 4.11 Server protection | D | fair share, call rate, script cap, fill volume (#78, #81); per-agent caps (#89) |

Scenarios still open: P10 (a teleport to a friend does not ask the friend), F1 (`allow_from`), F7 (policy schema
migrations: a `version` key is accepted, no migration exists yet), O17 without LuckPerms.

Audit items left open, ranked (not blocking release in my judgment; each is narrow):
1. Stability M9: writes made by declarative rules' commands are not in the change journal (no change id, no undo).
2. Stability M8: at shutdown, queued long polls run on the server thread (bites only with more than 16 concurrent
   long calls).
3. NeoForge offline permission lookups may load a LuckPerms user synchronously (not provable from code; Fabric's
   2 s wait is fixed in #89).
4. A selector or score component can still show a player-chosen entity name containing "[Almighty]" (a named mob);
   all other forgery paths found in two review rounds are closed (#82). Clicks that act as the clicker are closed.
5. Rights m2–m10 from the rights audit (policy-approval bound to text, not base; MOJANG→AUTO identity not counted as
   a grant; sign-in pending logins shared by everyone; ticket valid for any call for 10 minutes by design; …): see
   `audit-rights.md`.
6. A command's `execute as @e run fill …` repeats per entity unseen by the per-call volume cap (documented in
   `CommandVolume`).
7. `map {image:false}` still returns a picture (the `images` setting is per connection); found by the trial agent.

## 4. Checks with numbers

- **CI on every PR:** "Сборка и тесты" green on all five nodes before each merge (build with `-Xlint:all`, unit
  tests, GameTest).
- **Local runs (Java 25 toolchain in the container):** 1.21.1 NeoForge GameTests grew from 64 to 71, all passing on
  each branch before push; 26.3 Fabric 63 of 63 with the Common Protection API test.
- **Trial servers with a real headless Claude Code 2.1.332** (`claude -p`, MCP over HTTP with the agent token,
  loopback), dedicated servers built from `main` at `b9cccb0`:
  - 1.21.1 NeoForge (NeoForge 21.1.264 installer, `--installServer`), fresh install (preset friends): `status` with
    the connection check (preset friends, role ladder, problem "no owners" with its fix), `map`, `say`, a one-block
    build allowed at the member floor.
  - The same server on preset private: `script` returned 42 and **Groovy 4.0.33** (nested jar on a dedicated server);
    a 27-position glass box built (26 changed) and undone (26 back, no conflicts); a command `fill` of 9 blocks,
    found with `changes` and reverted with `undo_change` (9 back, no conflicts); a declarative rule on `tick` added,
    listed, fired, removed; `events` returned job and emit events.
  - 26.3 Fabric (Fabric loader 0.20.1, Fabric API 0.167.0+26.3, Java 25), preset private: `status` (26.3, 20.0 tps,
    connection check), `script` → 42 and **Groovy 4.0.33** (the nested-jar check on a dedicated 26.x server),
    build and undo of the glass box (26 and 26), `rule types BlockBreak` found `PlayerBlockBreakEvents.AFTER/BEFORE/
    CANCELED`, a declarative rule on `PlayerBlockBreakEvents.BEFORE` added, listed, removed; `say`. A `fill` outside
    loaded chunks failed with "That position is not loaded" (no players online, so no loaded spawn area on 26.x); the
    agent then correctly declined to undo the wrong change.

## 5. Needs Artem's word

- **The release** of 1.0.0 (`build` on `main` with `release=true`), and store uploads (Modrinth, CurseForge, AI
  disclosures, store secrets).
- **Open Skies:** the update picks up the new jar by itself. Two behaviour changes reach it then:
  - its `bridge.host` is loopback (open-skies-friends `docs/team/servers.md`), so the TLS refusal (#77) does not
    apply;
  - on `private` only owners listed in `policy.toml` reach the GM (#68, earlier); `status` now names this as a
    problem when no owners are set. Moving Open Skies to `friends` with its owners listed is Artem's call.
- Deletion candidates from the earlier report remain (left in place).

## 6. Needs the laptop

- In-game checks with a real client: clicking the 26.x confirmation dialog (Allow, Deny, Later; a dialog's
  `run_command` reaches `/almighty confirm` as the player's own action per the review of #85, not seen live), the
  privacy notice and Russian prompts on join, bots and builds on 26.x and Fabric, clips for the stores.

## 7. For the store and docs threads

- `docs/guide/hosting.md:49-50` and `hosting.ru.md:49` say a non-loopback bind without TLS starts with a warning; it
  is now refused unless `bridge.allow_plain_http_off_loopback = true` (#77).
- New `policy.toml` keys: `timezone`, `[safety] privacy_notice` (joining players are told an AI reads messages
  addressed to it), `[quotas] calls_per_minute` (1200, per agent), `[quotas] bot_minutes` (240 an hour below owner),
  `[agents.<name>] profile` and `images` now work (URL overrides).
- Below owner: scripts run at most 50 ms; rules' `budget_ms` at most 2.5; one call may fill/clone at most 131 072
  positions ("use build"); at most 32 queued jobs and 16 prepared areas per agent.
- Undo never removes a block a player put there since, nor empties a chest someone filled since; chests emptied by a
  build or `/setblock` come back full on undo.
- Below owner, no chat or command text may carry clicks that run or suggest commands, dialogs, or anything spelled
  like "[Almighty]"; safe clicks (open a link, copy, change page) stay.
- `status` shows a connection check with plain fixes (no owners, offline mode, plain HTTP …).
- 26.x: confirmations also open a dialog (Allow, Deny, Later).
- Fabric: claims of any mod using the Common Protection API are respected (GOML, Flan, …).
- Commands below owner respect claims; another agent's bot or area is off limits below admin.
- The agent may ask an admin for something above its own floor when the owner lists it in `ask_admin`; owners are
  told every admin decision.
- Player prompts, receipts and outcomes are in Russian for Russian clients.
- Tools: `rule` takes declarative `when`/`then`/`ttl_hours`, `script` takes `name`, `events` takes `mine`, `build`
  takes `undo_change`; the generated `docs/guide/tools.md` is current.
