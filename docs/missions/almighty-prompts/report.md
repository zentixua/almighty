# Mission almighty-prompts: report

Player-facing confirmations, 26.x dialogs, receipts and outcome lines now read as sentences in English and Russian.
The server's own facts (what runs and its target) always stand beside the agent's optional summary. The exact call
is on hover, behind `[Details]` / `/almighty show <nonce>`, and written out for Bedrock players and in the admin
inbox. Lead notes added: the undo replies N7–N9, direct admin questions, and the `/gm` echo.

## Done criteria and evidence

| Criterion | Evidence |
| --- | --- |
| Prompts, dialog, receipts and outcome lines read as human sentences, en + ru (existing lang files) | `Prompts`, `en_us.json`/`ru_ru.json`; GameTest `aQuestionShowsTheServersFactsBesideTheSummary` (en and ru, read as a client without the mod sees it) |
| Exact call on hover and in `/almighty log` | hover on the facts, and on `[Details]`; `/almighty log` shows the audit line with the full parameters (unchanged); GameTest checks the hover |
| Typed command for clients without hover (Bedrock/Geyser) | `/almighty show <nonce>` (`AlmightyCommand.show`: the confirmer, a deciding admin or the console); Bedrock players get the exact call written out; GameTest checks both |
| Safety rule: a summary never hides or misdescribes what runs; the decider always sees the facts | `Facts` comes only from the parsed call; `FactsTest.theSummaryNeverChangesWhatTheApproverIsToldRuns`; review fixes B1/B2: `aRunInsideQuotesOrPaddingNeverHidesTheCommand`, `theRiskyCommandIsAlwaysNamed` |
| Summary length-limited, sanitized, no clicks, nothing spelled like `[Almighty]` or the mod's labels, marked as the agent's words, optional | `Summary` (80 characters; no `§` codes, controls, bidi, brackets, quotes; dropped when it spells the mod's name or a label in either language, look-alikes folded); `SummaryTest`; GameTest: the summary has no click and a forged one is dropped, so the facts read alone |
| #82 forgery protections kept | `Forgery` unchanged and reused (fold and decode); every existing gate test passes |
| Tool descriptions tell agents to pass a short summary | the `summary` parameter on every tool (`Tools.SUMMARY`, `ToolsTest.everyToolPassesTheSummary`), the MCP instructions line, and the `command` tool description |
| Lead N7: undo replies are translated | `almighty.undo.*`; GameTest `undoRepliesInThePlayersLanguageAndOnce` |
| Lead N8: a second [Undo] says once, plainly, that it is already undone | same GameTest; no "by change 0" (`GmServer.revert`) |
| Lead N9: a lantern under an undone ceiling drops no item | `BuildJob.writing` + `mixin/LevelMixin`; GameTest `aHangingLanternDropsNothingOnUndo` (fails with the hook disabled, passes with it) |
| Lead: ask_admin reaches admins online as a direct question, rate-limited and deduplicated, the rest spills to the digest/inbox | `Decisions.direct`; GameTest `adminRequestsAreAskedDirectlyWithinLimits` (3 direct, a merged repeat not asked again, the 4th spills) |
| Lead: `/gm` echo "To Almighty: …" / "Ведущему: …" on every node | `GmCommand.echo`, the one source both loaders register; `GmMessagesTest` |
| Unit + GameTest on all five nodes | CI "Сборка и тесты" green on every node (see the PR checks); locally on 1.21.1: unit tests and GameTest 87/87 |
| One fresh review round | subagent review of the full diff: 2 blockers found and fixed. A second, extra review of the lead-note delta: no blockers; minor fixes applied |

## Design choices (with reasons)

- **Facts are mod-generated, from the parsed call** (`policy/Facts`): a verb per known command, its target from
  argument positions (selectors in words, filters shown as typed), and coordinates for block commands. Under `execute`,
  the chain's `as`/`at`/`on` targets are always shown, never clipped. An unknown or mod command is named as typed. The
  commands that make a call wait for a person come first and are never folded into "and N more". Reason: the decider
  must see what really runs, whatever the agent says.
- **The summary is quoted and marked "(its own words)"**, and cannot hold quote characters, so it cannot close its
  own quote. Reason: it must be visibly separate from the server's words.
- **Receipts show the summary alone**, with the facts and the exact call on hover; without a summary, the facts.
  Reason: the lead's spec ("Done for you: <summary> [Undo]"), and a receipt reports what already ran rather than
  deciding anything.
- **The `tools.json` description was left as it was** (the `summary` parameter is added in code, like `for`).
  Reason: `docs/guide/tools.md` is generated from that file, and this mission may not touch `docs/guide`.
- **Direct admin questions: 3 per admin every 10 minutes**. Per requester, the existing limit (one admin ask per 10
  minutes) holds. Merged repeats are never re-asked, and the rest goes to the digest. Reason: the no-approval-spam
  guarantee.
- **A summary that spells the mod's own words is dropped, not refused.** Reason: the call still works, and the facts
  alone read as a sentence.

## Final wording (for the teaser and docs threads)

Confirmation question (chat):
- en: `[Almighty] Cloudien wants to “snow over your base” (its own words) — what runs: change blocks at 0 64 0 … 20 70 20. It cannot be undone. [Allow] [Deny] [Details] (no clicks: type /almighty confirm <n> to allow, /almighty deny <n> to deny, /almighty show <n> for the exact call)`
- ru: `[Almighty] Cloudien хочет «замести базу снегом» (слова ведущего) — что выполнится: изменить блоки в 0 64 0 … 20 70 20. Это нельзя отменить. [Разрешить] [Отклонить] [Подробно] (без кликов: /almighty confirm <n> — разрешить, /almighty deny <n> — отклонить, /almighty show <n> — точный вызов)`
- without a summary: `[Almighty] Cloudien wants to kill — target: every player. It cannot be undone. [Allow] [Deny] [Details] …` / `[Almighty] Cloudien хочет убить — цель: все игроки. Это нельзя отменить. …`
- asked for someone else (a teleport partner): `Cloudien wants to (asked by Alex) teleport Alex to Bea…` / `Cloudien хочет (просит Alex) телепортировать Alex к Bea…`
- hover on the yellow facts: `Exact call: /fill …` / `Точный вызов: /fill …`; hover on the summary: `These are Cloudien's own words. What really runs is the yellow part after them.` / `Это слова ведущего Cloudien. Что выполнится на самом деле — жёлтая часть после них.`

26.x dialog: title `[Almighty] Cloudien asks you` / `[Almighty] Cloudien спрашивает вас`; body `Cloudien wants to:` /
`Cloudien хочет:`, then the summary and facts, then `Exact call: …` / `Точный вызов: …`, then `This cannot be undone.` /
`Это нельзя отменить.`; buttons `Allow` / `Deny` / `Later` (`Разрешить` / `Отклонить` / `Позже`).

Admin question (direct, and in `/almighty inbox`):
- en: `[Almighty] Cloudien wants to “make it day” (its own words) — what runs: change the time of day (for Alex, with your rights) [Allow] [Deny] [Details] … Exact call: /time set 1000`
- ru: `[Almighty] Cloudien хочет «сделать день» (слова ведущего) — что выполнится: изменить время суток (для Alex, с вашими правами) [Разрешить] [Отклонить] [Подробно] … Точный вызов: /time set 1000`
- digest: `Requests waiting for an admin: 2 — /almighty inbox` / `Ждут решения админа: 2 — /almighty inbox`

Receipt:
- en: `[Almighty] Done for you: “snow over your base” [Undo]` (without a summary: `Done for you: change blocks at … [Undo]`)
- ru: `[Almighty] Сделано по вашей просьбе: «замести базу снегом» [Отменить]`

Outcome lines: `Allowed: …` / `Разрешено: …`; `Not allowed: …` / `Отклонено: …`; `Did not work: …` / `Не получилось: …`;
`Nobody answered in time, so this did not run: …` / `Никто не ответил вовремя, поэтому это не выполнено: …`.

Undo (a receipt's [Undo]): `Undoing change 2. Blocks changed since then stay as they are.` / `Отменяю изменение 2. Блоки,
изменённые после него, остаются как есть.`; a second click: `Change 2 is already undone.` / `Изменение 2 уже отменено.`
(while it runs: `Change 2 is being undone right now.` / `Изменение 2 отменяется прямо сейчас.`).

`/gm` echo: `To Almighty: <text>` / `Ведущему: <text>`.

## Left as is (minor, recorded)

- Blocks that pop off in `neighborChanged` (rails, redstone wire, repeaters) still drop on undo. Only shape-update
  breaks are covered (lanterns, torches, doors). Hooking `dropResources` would be broader and riskier across versions.
- Words taken from the call (selector filters, quoted names) appear inside the yellow facts as typed. They have no
  clicks, and the exact call is on hover.
- The facts list the commands that need a person first, not in run order.

## Needs Artem's word / the laptop

Nothing. This is code only, with no release step. A real-client look at the new lines (a clip) would need the laptop.
That is optional and not a done criterion.
