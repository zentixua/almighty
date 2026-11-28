# Audit C: broken promises in agent/player-facing text, and plan-1.0 coverage

Base: `origin/main` d6dc3e0. Note: during the audit the shared working tree was switched by someone else to branch
`claude/finish-tls` (c3b1d60, 2 commits ahead). That branch changes only 7 files (TLS refusal, `command` tool text,
AGENTS.md, docs/guide/tools.md, GmConfig, GmServer, TlsTest). Where it fixes a finding below, this says so.
Paths are under `src/main/java/ua/zentix/almighty/` unless they start with `src/` or a top-level folder.

## Part 1. Broken promises, ranked by severity

### High: the agent is told something false about what it can do

1. **The `rule` tool cannot make declarative rules.** `mcp/Tools.java:247` `a.allow(...)` has no `when`, `then`
   or `ttl_hours`, so `rule {action:"add", when, then}` fails with "does not take when". The schema
   (`tools.json` rule, ~l.196-230) and its description (l.203) give only script rules. But a script rule needs the
   `engine` node (owner only). So below owner, which is the default on the D1 `friends` preset, the only way to
   make a rule (plan 4.12, node `almighty.rules`, builder) is hidden behind `call`. The `scripts_and_rules` note
   does not mention `when`/`then` either.
   Fix: add `when`, `then` and `ttl_hours` to the schema, to `Tools.rule` `takes.add`, and to the description and
   the note, with one example.
2. **The `script` tool cannot run approved scripts.** `mcp/Tools.java:167-168` allows only `code` and requires it.
   `script {name, args}` (node `scripts.run`, builder; `Api.java:398-401`) can only be reached through `call`, and
   the description and note do not mention it.
   Fix: accept `name` as the alternative to `code` (exactly one of them), and describe it.
3. **The `command` tool says "Runs as the game master (permission level 4)"** (`tools.json:83` on main). In fact
   it runs at the floor's level (member on friends = level 0) or as the requester (`GmServer.java:499-514`). It also
   says "The source is the world spawn point or pos", which is false with `for`: the player's own source is used and
   `pos`/`dimension` are ignored (`act/CommandRunner.java:255-258`). **Fixed on branch `claude/finish-tls`**
   (rights part). The pos/source sentence is still there: also say that with `for` the command runs where the
   player is.
4. **The `events` tool cannot pass `mine`.** `mcp/Tools.java:173` allows only `after`, `wait` and `limit`. With
   several agents, plan 4.6 ("each agent reads only its own addressed events") and AGENTS.md (`events {mine:
   true}`) cannot be done through MCP, so every agent reads, and pays for, the others' routed messages.
   Fix: allow `mine` in the tool, or default it to true for a caller that is an agent.

### Medium

5. **Rule event names on Fabric (26.1.2-fabric, 26.3-fabric).** The `rule` tool description (`tools.json:203`)
   and `notes/scripts_and_rules.md:29` say "a NeoForge event class ... BlockEvent.BreakEvent,
   LivingDamageEvent.Pre, CommandEvent". On Fabric, rules attach only to Fabric API callbacks
   (`fabric/FabricEvents.java:41,159`), so every example fails there.
   Fix: make the text loader-neutral ("an event class or callback; find one with rule types"), give one example per
   loader, or substitute the examples at `tools/list` time through `Platform`.
6. **On main, plain HTTP off loopback only logs a warning.** `GmServer.java` on main has a warning but no refusal.
   That breaks plan 7.2 ("a non-loopback bind is refused without TLS") and the 4.13 test "a non-loopback bind
   without TLS is refused". **Fixed on branch `claude/finish-tls`** (`bridge/Tls.plainRefusal`, `TlsTest
   .plainHttpOffLoopbackIsRefused`, the opt-in `bridge.allow_plain_http_off_loopback`). Merge it.
7. **The `[agents.<name>] profile` and `images` keys are accepted silently and do nothing.**
   `policy/Policy.java:195` whitelists them, but `AgentRules` (`Policy.java:99`) keeps only ceiling and floor.
   An owner who writes `images = false` gets no error and no effect. Profile and images are per URL only
   (`mcp/McpEndpoint.java:170-174`).
   Fix: refuse the keys with "use ?profile=compact&images=false in the agent's URL", or apply them per agent in
   `McpEndpoint.settings`.
8. **The `ask_admin` template comment promises more than the code does.** The comment (`Policy.java` template
   `[safety]`) says "the only kinds of request that may reach an admin when the player lacks the right". It works
   only with a ticket (`policy/Gate.java:219`, `GmServer.java:553`). The agent's own elevation requests (plan 4.8
   rule 5, O12 "moderation agent kicks spammers: a kick asks an online admin") are never routed. They are refused
   with 403.
   Fix: either implement an agent-origin admin ask with a per-agent limit, or say "a player's request" in the
   template and the docs.
9. **The notes and tool texts say script changes cannot be undone, which is now false.**
   `notes/scripts_and_rules.md:25` says "There is no undo for script changes", and the `script` tool says "no undo"
   (`tools.json:173`). The journal records script writes and `undo {change}` reverts them (GmJournalGameTests).
   The `build` tool also offers only `undo: <job>`; `undo {change}` (the id that receipts show) is reachable only
   through `call`.
   Fix: say "block writes are recorded: undo {change} via call (ids in changes)", and let `build {undo}` or `job`
   take a change id.
10. **Player-facing server text is English only.** Only 4 keys are translated (`assets/almighty/lang/*.json`;
    en/ru parity is fine, 4 = 4, placeholders match). Confirmation prompts, receipts, the digest, inbox lines and
    "Request expired" / "Done:" / "Not allowed:" (`policy/Prompts.java:44-86`, `policy/Decisions.java:68-98`),
    "/gm speaks only…" (`feed/GmCommand.java:37`) and the "→ Claude: text" acknowledgement are hard-coded
    English. AGENTS.md "Languages" puts the mod's interface in en_us/ru_ru, and plan P6 targets players' languages.
    Fix: move these to lang keys through `GmMessages.get` (translatableWithFallback, picked by client language).
11. **The `config` catalog kind on Fabric lists only Almighty's own settings** (`fabric/FabricPlatform.java:71-76`).
    `learning_mods.md` and the `notes` tool promise "`config` (its settings)", meaning the mod's.
    Fix: say "NeoForge only; on Fabric, Almighty's own" in the note and the tool.

### Low

12. **`Tools.FOR` (`mcp/Tools.java:66`) says "Without it the call runs with your own, lower rights."** On
    `private`, the floor equals the ceiling (owner), so a ticket usually *narrows*.
    Fix: "Without it the call runs with your own trust-floor rights (status shows them)."
13. **The `say` and `bot` texts leave out the gate.** `say` "to - everyone by default" needs `speak.all` (member)
    and is refused on `public`. The `bot` text says a real name or skin needs only "disguise", but any `skin`,
    creative/spectator or `payload` needs `bots.admin` (`policy/Node.java:20`).
    Fix: add one clause each.
14. **AGENTS.md says `/almighty confirm|deny` accept only the player's own typing.** `command/AlmightyCommand.java:262`
    uses `source.getEntity()`, not `Mc.typedBy`. It is not exploitable without the nonce, but it does not match the
    stated rule, and no test covers it.
    Fix: use `Mc.typedBy(source)`, as `/gm` does.
15. **`/almighty approve` with nothing waiting** says "Start one from your agent: /almighty connect"
    (`mcp/signin/SignInCommand.java:59`). `/almighty connect` is a server command, not something the agent
    starts. Fix the wording.
16. **"Owners online are told" is not true for owners.** `Mcp.notifyOwners` (`mcp/Mcp.java:96-108`) tells every
    op-level-4 player, not the `policy.toml` owners. It never shows the code, so this is fine for safety, but it is
    misnamed.
17. **`job` has `readOnlyHint: false` for plain reads.** Plan 7.2 lists `job` without cancel as read-only. This is
    cosmetic: Codex/ChatGPT approval prompts will ask.

Checked and consistent (no finding):
- Numbers in the text match the code constants: map 1024 (`MapView.MAX_SIDE`), blocks 64/32768 (`BlockText`),
  eye 224x126 default and 480x270 maximum, fov 70 (`Api.see`, `EyeView`), area ttl 3600, events wait 25, wait 50
  (`Api.MAX_WAIT`), script 10000 ms, rule budget 5 ms, 20x budget, 10 errors, 200 emits/s, 7-day TTL
  (`rules/Rules.java:51-57,107`), bot names 3-16, quotas 50 000/128, gm_messages 20, away 30 s, 5 refusals →
  10-minute cooldown, tickets 10 min, ask limits 3/20 and 1 per 10 min and 20/h, auth 5/min with 5 s→5 min.
- The 512-character core of the MCP instructions: `McpEndpoint.CORE` is 446 characters (tested), and the total
  is capped at 3000.
- Click-event stripping handles both `clickEvent` and `click_event` (1.21.5+) (`act/Chat.java:113`).
- The `notes` "unconfirmed unless the owner asked" claim matches `Api.java:100-103`.

## Part 2. Plan coverage

Legend: D = done, P = partial, M = missing, A = deliberately changed (documented). Only P, M and A are listed in
full; the step table gives every step.

### §12 steps

| # | Step | State | Evidence / gap |
|---|---|---|---|
| 1 | Bridge hygiene | D | `bridge/Bridge.java:57-60` (4/8/16, queue 64); `feed/Feed.java:27` ReentrantLock; AuthGuard; BridgeTest, AgentsTest, AuthGuardTest |
| 2 | Deny list, lag guard, budgeted world calls, idempotency | D | DenyListTest, DispatchTest, LagGuardTest, GmSafetyGameTests |
| 3 | Feed identity, addressing, routing, tickets, away | P | Done: Inbox, AddressingTest, GmInboxGameTests. **Player-text wrapping only in feed events** (`untrusted` field list, `feed/Inbox.java:241`). Pull reads are unwrapped: item hover names (`world/Observe.java:118`), entity custom names (`Observe.java:211`, `bot/Eye.java:176`). Sign and book text are not read at all. The planned `{"player_text","by"}` shape does not exist |
| 4 | Audit, log, pause | A | Own writer instead of log4j2 (reason in AGENTS.md); AuditTest, GmAuditGameTests |
| 5 | Gate | D | GateTest, PolicyTest, GmGateGameTests |
| 6 | Origins and confirmations | P | Done: Confirmations/Decisions/Prompts, ConfirmationsTest, GmConfirmGameTests. **Missing:** agent-origin ask_admin (`Gate.java:219` needs a ticket); owners not told when an admin decides (`Decisions.decide` tells only the requester); **26.x dialogs** (no dialog code anywhere); MCP elicitation (none); "owner client" gets only an owner ceiling, not owner origin (`mcp/signin/OwnerApp.java`): its floor still follows the preset, so its granting policy edits go to the digest |
| 7 | Content limits | D | GmLimitsGameTests, `policy/ContentLimits` |
| 8 | Change journal | D | GmJournalGameTests, JournalRecoveryTest |
| 9 | Presets, zones, ladder, quotas, LuckPerms test handler, claims | P | Done: LadderTest, GmLadderGameTests, OPAC and FTB modules. **Quotas only `build_blocks`, `give_items`** (`Policy.java` Limits): no `calls` quota, no bot-minutes quota, no `PermissionNode<Integer>` `almighty.quota.*` (LuckPerms meta) and no `almighty:zone` context (`policy/Nodes.java`, `neoforge/NeoForgePlatform.java:54`) |
| 10 | Config model (`file`) | P | Done: `policy/ConfigFiles`, PolicyFilesTest. `file` reaches only `policy.toml` and `scripts/` (`ConfigFiles.java:18`), not the owner's `config/almighty/notes/` (plan 4.10 says four places). **Roles cannot be set by asking** (O17, S2 "make Bob a builder"): `roles.json` is outside `file`, `/almighty` is on the deny list (`DenyList.java:34`), and no method sets roles. This works only with LuckPerms (`/lp`) |
| 11 | Declarative rules, approved scripts | P | Server side done (DeclarativeTest, GmRuleGameTests.declarativeRulesFollowTheirCreator). **MCP tools do not expose `when`/`then`/`ttl_hours` or `script {name}`** (findings 1 and 2) |
| 12 | MCP endpoint, profiles, images, integrated, TLS | P | Done: McpProtocolTest, McpEndpointTest, ToolsTest. Profile and images are **per URL, not per agent** (`[agents]` keys ignored, finding 7). TLS refusal off loopback is **on the branch only** (finding 6). Self-signed generation dropped (A, AGENTS.md) |
| 12b | OAuth sign-in, `/almighty connect` | P | Done: SignInTest, SignInHttpTest, OwnerAppTest, `mcp/Connect.java`. **Missing:** `allow_from` per agent; headless tokens "via the same approval (`call agent.token`)" (only `/almighty agent add` on the console) |
| 13 | Catalog, frontmatter, world notes | P | Done: CatalogTest, NotesTest. **Missing from the catalog:** loot tables, advancements, recipe types (`knowledge/Catalog.java:51-52` KINDS); per-mod exclusion by the owner; the `almighty://catalog` resources mirror (optional) |
| 14 | Plugin | D | `plugin/almighty/skills/*`; `scripts/export_skills.py` check. `crash_guard.py` and `recipes.py` are still in `mcp/gm` (now optional) |
| 15 | Docs | D | `docs/guide/*` en+ru, `connect/*` per product, hosting.md (Aternos unsupported) |
| 16 | Releases [A] | n/a | `mod_version=1.0.0`, `docs/releases/1.0.0.md` |
| 17 / 17b | Stonecutter; brand | D / placeholder | `versions/*` |
| 18 | 26.1.2 and 26.3 ports | P | Builds and GameTests on all nodes; **dialogs for confirmations on 26.x missing** |
| 19 | Fabric | P | Loader done; permissions through lucko's API (`fabric/FabricPlatform`). **Claims through the Common Protection API are missing**: Fabric nodes use OPAC/FTB modules only (`compat/Compat.java:30-36`; no `eu.pb4` dependency); FTB is not on 26.3. Rule texts are still NeoForge-only (finding 5) |

### Principles §1.1

| # | State | Note |
|---|---|---|
| 1, 2, 4, 6, 8 | D | (4: log4j2 deliberately replaced, A) |
| 3 Hijacked agent / player text is data | P | The floor holds. **Wrapping of player-authored strings in pull reads is missing** (step 3) |
| 5 LCD first, elicitation later | D (elicitation M, optional) | |
| 7 Never break the server | P | Budgets, lag guard, rules and deny list done. **No per-agent fair share**: world calls are one FIFO (`GmServer.java:90`) and jobs run in submission order (`work/Jobs.java:52-63`). **No per-agent script cap**: `timeout_ms` is up to 10 s for every caller (`Api.java:403`), including approved scripts run by builders (plan 4.11: 50 ms unless owner). No per-agent call rate limit (F2, F14) |
| 9 Honest and reversible | P | The requester prompt says "cannot be undone"; the admin inbox line does not. No `privacy_notice` to joining players |

### §4.5 policy keys

`timezone`: **M**. `Policy.java:151` top-level keys have no timezone; Audit uses `ZoneId.systemDefault()`
(`audit/Audit.java:186`). `[safety] privacy_notice`: **M**. `Policy.java` safety accepts only
`ask_admin`/`confirm_first`, and no join notice exists. `audit_days` and `gm_messages` live in the mod config
(`GmConfig` audit.days, feed.gm_messages), which is fine (A). `[agents] profile`/`images`: **P** (accepted, ignored).

### §9.1 connection check in `status`

**P.** `status` returns ticks, players, levels and `gm` work (`world/Observe.java:43-88`) plus `rights` with
`preset`, ceiling, floor and allowed (`policy/Gate.java:309-318`). Not reported: identity mode, whether online-mode
or a proxy is detected, the permission plugin (LuckPerms/handler), claim mods found, TLS or public URL, Floodgate or
Bedrock support, and the "plain fixes". Fix: add a `setup` object to `status` from `Policy.identity`, `Nodes`
handler presence, `Compat.claims()` names, `bridge.tls()`, `bridge.public_url`, and `isModLoaded("floodgate")`.

### §6.2 scenarios, partial or missing only

| # | State | Gap |
|---|---|---|
| O12 moderation agent asks an admin | M | No agent-origin ask_admin; "status warns" for a raised floor: none |
| O13 both owners told | P | Only the requester is told (`Decisions.java:78`) |
| O15 integrated "owner preset" | P | Integrated uses the normal fresh/existing rule (friends on a fresh world) |
| O17 / S2 "make Bob a builder" by asking | M without LuckPerms | No agent path to roles |
| P4 injection via signs, books, names | P | Feed wrapped; pull reads not |
| P7 per-player `calls` quota | A | `gm_messages` instead (fine) |
| P10 teleport to a friend asks the friend | M | `Confirmations` asks only the requester or an admin; no target confirmation |
| U5 per-agent compact profile | P | Per URL only |
| F1 `allow_from` | M | |
| F2 per-agent calls quota, fair job share | M | |
| F3 script time capped per agent | M | 10 s for all |
| F7 schema migrations | P | `version` key accepted in policy; no migrations |
| F12 bot-minutes quota | M | Only `bots.max` (global 8); timed keys and pause stop bots (D) |
| F14 per-agent rate limit | M | Only the global queue (64) → 503 |
| §4.8 26.x dialogs, MCP elicitation | M | |
| §4.7 Fabric claims via Common Protection API | M | |
| §4.3 LuckPerms `almighty.quota.*` integer nodes, `almighty:zone` context | M | |
| §5.1 catalog loot tables, advancements | M | |

All other scenarios (O1-O11, O14, A1-A6, P1-P3, P5, P6, P8, P9, P11, P12, D1-D6, U1-U4, U6-U10, M1-M3, C1, C2,
F4-F6, F8-F11, F13, F15-F21, O16, O18, P13) are covered by code and the tests named in AGENTS.md "Checks". D3's
`almighty.command.<root>` is superseded by running as the requester (A).
