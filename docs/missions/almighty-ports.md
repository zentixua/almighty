# Mission almighty-ports: report

Plan steps 17–19 of `docs/design/plan-1.0.md` (MISSION.md numbers them 14–20):
- a common core plus loader modules;
- Minecraft 26.1.2 and the latest release (26.3);
- Fabric;
- release readiness of the jars;
- a final sweep: the code checked item by item against `plan-1.0.md` and `decisions-1.0.md`.

Nothing was released, tagged, uploaded or posted publicly; those steps need Artem's word.

Scope (lead, 27.11): README, docs/guide, docs/store, `scripts/store.py`, `publish.yml` and `modrinth.sh` belong to other
threads. This thread stopped editing them after #64; facts they need are listed at the end.

## What was built

| PR | What |
|---|---|
| [#60](https://github.com/zentixua/almighty/pull/60) | Platform seam: the core is vanilla plus plain Java; `neoforge/` holds the loader; `LoaderBoundaryTest` keeps it that way. No behaviour change. |
| [#61](https://github.com/zentixua/almighty/pull/61) | Stonecutter 0.10 build (Gradle 9.9, Java 25): one source, one node per game version and loader; CI matrix over `versions/*`. |
| [#62](https://github.com/zentixua/almighty/pull/62) | Minecraft 26.1.2 on NeoForge, GameTests ported (`gametest/v26`). |
| [#63](https://github.com/zentixua/almighty/pull/63) | Minecraft 26.3 on NeoForge; Fabric on 26.1.2 and 26.3. On Fabric, rules can listen to 117 callbacks, each with a neutral answer (a callback whose answer changes the game is refused). |
| [#64](https://github.com/zentixua/almighty/pull/64) | 1.0.0, release notes (EN/RU), release of every node's jar, store pages, per-file publish matrix; `needs_level` hint. |
| [#65](https://github.com/zentixua/almighty/pull/65) | Jar metadata: server-only display test on NeoForge, Fabric API at least the build's; release-pace test. |
| [#67](https://github.com/zentixua/almighty/pull/67) | Sweep fixes, release-blocking: `/almighty` reachable for players (confirm/deny/whoami/inbox/undo); owner apps and owner commands only by a true owner (who typed it, not who it runs as); `for` on every MCP tool; note provenance; console deny of admin requests; docs-audit items (private commands out of rules, per-agent pause stops rules, `log <player>`, last owner kept). |
| [#68](https://github.com/zentixua/almighty/pull/68) | Security: an agent reads a player's text only if that player holds every right it has unasked (its trust floor); private hears only owners; `/gm` below the floor answered by the server; feed, bot whispers and bot inbox filtered per agent. |
| [#73](https://github.com/zentixua/almighty/pull/73) | Journal revert in exact reverse write order across pieces (the third 26.x flake's cause), bounded to 64 pieces; tick-rule test budget. Proof: 20 of 20 full GameTest runs on 26.3 (both loaders). |
| [#69](https://github.com/zentixua/almighty/pull/69) | Flaky GameTests fixed at their cause: the bot respawned at a random spot outside its test (now a spawn point on its floor); `Journal.load` never recovered change files while no index had been saved yet (a product bug: a crash during a world's first changes lost them; now recovered, `JournalRecoveryTest`). |

Nodes: 1.21.1-neoforge (Java 21), 26.1.2-neoforge, 26.1.2-fabric, 26.3-neoforge, 26.3-fabric (Java 25). Jars
`almighty-1.0.0+<game>-<loader>.jar`.

## Checks

- **CI on every PR:** build (`-Xlint:all`), unit tests and GameTest on all five nodes. On 1.21.1 there are 64 GameTests after #73.
- **End-to-end on a loopback trial server**, with Claude Code 2.1.332 headless:
  - 26.1.2 NeoForge: `status`, `map`, `say`.
  - 26.3 Fabric: `status`, `event.types`, `say`.
  - Server and audit logs confirm both. Codex CLI was not available here.
- **Final audit:** four fresh sessions checked `main` at 5848513 item by item against plan-1.0 (§0–§15, all 79 scenarios) and decisions-1.0. The tables are summarised below; the release-blocking items are fixed in #67 and #68.
- **Flaky tests:** 20 consecutive full GameTest runs on 26.3-fabric and 20 on 26.3-neoforge, on #69's final commit.
  - `botDiesAndRespawns` and `crashMidBuildLeavesAnUndoableJournal` failed 0 times in 40 runs. Before the journal fix, the journal test failed 3 times in 14.
  - Other tests failed once each:
    - `selfRewritingBlocksRevertExactly`: also seen once on CI; under diagnosis (see "Open").
    - `tickRulesKeepPaceAndLimit`: the rule's wall-clock budget turned it off under the parallel load; the follow-up widens that test's budget.

## Plan check: what is done, partial, missing

Sections and steps that are **done** (with tests):
- the bridge and its hygiene;
- the deny list;
- feed identity and addressing;
- audit and pause;
- the gate (nodes, floors, ceilings, tickets);
- confirmations and receipts;
- content limits;
- the change journal and conflict-aware undo;
- presets, zones, the role ladder, quotas and claims (OPAC, FTB Chunks);
- policy edits by asking;
- declarative rules and approved scripts;
- MCP in the mod (both eras), keyless sign-in, TLS with the owner's certificate;
- knowledge (catalog, notes, world notes);
- the plugin (Claude Code and Codex marketplaces);
- the restructure, the ports and Fabric;
- decisions D1–D6 as recorded.

Fixed in this sweep (PRs #65, #67, #68, #69):

| Item | Was | Now |
|---|---|---|
| 4.8 confirmations typed by players | unreachable: `/almighty` root needed level 4 | #67 |
| 4.2/4.4 only an owner grants owner rights | any level-4 op could approve an owner app | #67 |
| 4.2 private: only owners address the agent; floor = least-trusted reachable player | anyone reached an owner-floor agent | #68 |
| 4.13 one optional `for` everywhere | only `call` took it | #67 |
| 5.4 note provenance (requester, confirmed) | never set | #67 |
| 4.6 private messages never in the feed | a scriptless command rule could copy them | #67 |
| AGENTS.md: pausing one agent stops its rules; `log <player>` | pause-all only; agent filter only | #67 |
| `/almighty owner remove` keeps the last owner | it could remove it | #67 |
| 4.1 a test for each promise: release pace 64 chunks per tick | untested | #65 |

**Third flake, found and fixed (#73):** `selfRewritingBlocksRevertExactly` on 26.x was a product bug in undo. A change was split into fixed 128×128-block regions, and the regions were undone in first-seen order. A redstone block and its wire on either side of a region line (block at x = 128k + 127) were then undone in the wrong order, which gave false conflicts and left blocks in place.
- **Fix:** pieces are now runs of consecutive writes, undone strictly from the last back. Above 64 runs, pieces are regions again, to keep the count bounded.
- **Tests:** `selfRewritingBlocksRevertAcrossPieces` fails without the fix and passes with it; `alternatingWritesRevertInFewPieces` covers the bound.

**Partial or missing**, not in this thread's scope or needing a design decision. They are listed for the independent audit and later work, ranked by impact:

1. **TLS off loopback** only warns, and there is no self-signed certificate option (plan 7.2, step 12, B4, R17). The change was made on purpose (AGENTS.md: container setups bind private addresses), but the plan and decision record were not amended, and the test "a non-loopback bind without TLS is refused" does not exist. This needs Artem's decision: amend the plan, or restore the refusal.
2. **Dialogs for confirmations on 26.x** (step 18, B2, R7) are missing; confirmations are chat buttons plus a typed `/almighty confirm <id>`. Neither is MCP elicitation (R7) implemented.
3. **Claims on Fabric through the Common Protection API** (step 19, R9) are missing; Fabric checks OPAC and FTB Chunks only.
4. **Per-agent fairness:**
   - there is no fair share of the tick budget between agents, no `calls` quota and no per-agent rate limit (S6, F2, F14);
   - the script time limit is 10 s for every agent, not capped below the owner (4.11);
   - there is no bot-minutes quota (F12).
5. **The agent's own elevation through `ask_admin`** and its per-agent limit (4.8 item 5): only policy-file proposals go to an admin.
6. **Player text wrapping** (4.11, P4): feed events mark `untrusted` fields, but sign and book text and entity and item names come back unmarked through `blocks`, `entities` and `see`.
7. **Connection check in `status`** (9.1): only the preset is reported, not identity mode, LuckPerms, claims, TLS or Bedrock.
8. **Policy keys:** the plan promises `privacy_notice`, `timezone` and per-agent `profile`/`images`. The first two are missing; `profile` and `images` are accepted but have no effect (the URL parameters work).
9. **LuckPerms integration:** integer quota nodes `almighty.quota.*` and the `almighty:zone` context are missing.
10. **Catalog gaps:** no loot tables, advancements, MCP resources or per-mod exclusion.
11. **Owners are not told when an admin decides** (4.8).
12. **Recorded sign-in logins** per client (12b): only the MCP handshakes are recorded; sign-in tests are synthetic.
13. **Plugin install by asking** for Gemini, VS Code and Cursor agent plugins (§9.1) is missing; the chat channel still reads `GM_OWNERS`.
14. **Audit uses a plain writer**, not log4j2 (R11). The reason is in AGENTS.md; the plan was not amended.
15. **Groovy stays 4.0.33 on Java 25** (plan 8.2), not Groovy 5 as MISSION.md worded it. It runs on all nodes, and scripts and rules pass GameTest on 26.x. The nested-jar check on a dedicated 26.x server was not done here (it needs the laptop).

## Needs Artem's word

- **The release:** running `build` on `main` with `release=true` creates `v1.0.0` and the GitHub release.
- **Store work:**
  - Modrinth and CurseForge uploads;
  - the Modrinth AI disclosures, set by hand;
  - the CurseForge project, which the lead creates;
  - the store secrets and environment protection, which Artem enters.
- **Open Skies:** after #68, `private` hears only the owners listed in `policy.toml`.
  - For friends to keep talking to the GM, Open Skies moves to `friends` with its owners listed; each request then runs with that player's rights.
  - The `update` workflow there must use the new jar name `almighty-<v>+1.21.1-neoforge.jar`.
  - The Open Skies reset.
- **TLS:** decide item 1 above.
- **Deletion candidates, left in place:**
  - `docs/brand/exploration`;
  - the old `docs/media/icon*.png`;
  - the Russian-named old release notes.

## Needs the laptop

- In-game check of bots and builds on 26.x and Fabric, and clips for the stores.
- The Groovy nested-jar check on a dedicated 26.x server (`script` through `mcp/almighty.py call`).

## For the store and docs threads

Facts changed by this thread, for their pages:
- **Release files:**
  - jars are `almighty-1.0.0+<game>-<loader>.jar`;
  - nodes are 1.21.1 NeoForge, 26.1.2 NeoForge and Fabric, 26.3 NeoForge and Fabric;
  - Fabric needs Fabric API at least 0.155.3 on 26.1.2 and 0.167.0 on 26.3.
- **Who can talk to the GM** (#68): on `private` only owners do; others get "The game master here takes requests only from players trusted with what it can do." On `friends` an unverified player is not heard. In general, a player is heard only if they hold the rights the agent has when it acts unasked.
- **Tools** (#67): every tool takes `for` (the ticket of the player's message); with it the agent acts with that player's rights.
- **Commands** (#67):
  - `/almighty log <agent|player>`;
  - pausing one agent also stops its rules;
  - `/almighty owner remove` refuses to remove the last owner;
  - `approve … owner` needs a true owner;
  - players can type `/almighty confirm|deny|whoami|inbox`.
- **Stale tool text:** the `command` tool's description in `tools.json` still says "permission level 4". The generated `docs/guide/tools.md` must be regenerated together with it.
