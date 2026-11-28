# Mission almighty-finish2: report

Mission: almighty-finish2. Goal (Artem, 27.11): close every open and partial item that almighty-finish left before
1.0.0, each with tests, or record why it is wrong for the concept. Then one last fresh-auditor pass over everything
since `docs/design/almighty-core-report.md` (main at #58). Lead notes added two items: owner setup in one step, and
the findings of a real-client check (F1–F11).

Nothing was released, tagged, uploaded or posted, and no live server or world was touched. README*, `docs/store`,
`scripts/store.py`, `publish.yml` and `modrinth.sh` were not edited. In `docs/guide`, only the generated `tools.md`
changed; CI checks it against `tools.json`.

## 1. What closed

Every PR below was merged only after a green "Сборка и тесты" on all five nodes. Each had one review round by a
fresh subagent that was given the diff and the surrounding code, never the author's reasoning. Every blocking
finding was fixed before merge (the round is quoted in each PR's comments).

| Item | State | PR | Evidence (test) |
|---|---|---|---|
| Stability M8: long polls run on the server thread at shutdown | closed | #95 | `BridgeTest.stopAnswersQueuedRequestsWithoutRunningThem` |
| Stability M9: declarative rules' command writes not journaled | closed: one change per rule (up to 1 min), `changes`/`undo {change}` | #95 | GameTest `GmRuleGameTests.ruleCommandWritesAreJournaled` |
| NeoForge offline LuckPerms lookups on the server thread | closed: server thread uses a background cache (`OfflineAnswers`), a known "no" holds, bridge threads ask directly | #95 | `OfflineAnswersTest` (6) |
| `execute as @e … fill` volume below owner | closed: repeated writes refused below owner; rule commands get the volume bound | #95 | `CommandVolumeTest.repeatedWritesBelowOwner` |
| Named mob shown through a selector as "[Almighty]" | closed: non-player selectors, UUID selectors, `nbt` and `keybind` parts refused below owner | #96, #100 | `ForgeryTest` |
| Rights m1, m2, m6 | already closed earlier (#87 typed confirm; #87 clone across dimensions and `execute in`; #82 look-alikes) | — | earlier tests; more look-alikes in #100 |
| Rights m3: approval bound to text, not base | closed: proposals carry the file's sha; a stale approval is 409 | #96 | `PolicyFilesTest.approvalsAreBoundToTheirBaseAndOwnersHearOfRestrictions` |
| Rights m4: no receipt for restricting writes; caller `undo_of` | closed: owners told; `undo_of` only for the newest entry | #96 | same test, `aCallerSuppliedUndoOfDeletesNoOlderEntry` |
| Rights m5: mojang → auto not a grant | closed | #96 | `PolicyTest.identityOrder` |
| Rights m7: NeoForge cancels placed at the acting player | closed: player events have no place (interaction/attack target only); commands still run at the player | #96 | rule GameTests on all nodes |
| Rights m8: sign-in approval by any level-4 op; global pending cap | closed: true owner (or the first owner, #99); 2 per address | #96, #99 | `SignInTest`, `GmConfirmGameTests` |
| Rights m9: bot `/gm` mints tickets | closed | #96 | `GmBotGameTests` chat test |
| Rights m10: ticket is a 10-minute capability | rejected (by design, plan 4.2/4.6): a ticket gives at most its player's rights for 10 minutes, bound to one agent; anything irreversible for others still waits for the player; narrowing to one call breaks multi-step requests | — | — |
| `map {image:false}` returned a picture | closed (map and look) | #96 | `ToolsTest`, GameTest `mapAndLookViews` |
| Plan 10: `file` reach | closed: `notes/<name>.md` (owner writes, any agent reads); world notes were already reached by `notes` | #97 | `PolicyFilesTest.serverNotesAreTheOwners` |
| O17: "make Bob a builder until 23:00" | closed: bridge method `role` (owner/admin requests at once, roles below their own; on the agent's own initiative only clearing or narrowing; anything else waits for the owner) | #97 | `RoleCallsTest`, GameTest `GmLadderGameTests.theOwnerMakesBobABuilder` |
| F7 (plan): policy schema migrations | closed as far as there is anything to migrate: `version` (absent = 1); a newer file refused with the reason, last good kept; schema 1 is the first, so no migration exists yet | #97 | `PolicyTest.schemaVersion` |
| P10: a teleport to a friend asks the friend | closed | #97 | `ConfirmationsTest.teleportPartners`, GameTest `aTeleportToAFriendAsksTheFriend` |
| Plan 9: LuckPerms quota nodes | closed: `almighty.quota.build_blocks|give_items` (NeoForge integer nodes, Fabric options) | #98 | GameTest `GmLadderGameTests.zoneScopedGrantsAndQuotaNodes` |
| Plan 9: `almighty:zone` context | closed on NeoForge (dynamic context on every node; a grant scoped to a zone lets a request run wholly inside it, with `confirm_first` too). Fabric's permissions API has no contexts | #98 | same GameTest |
| Plan 12b / F1: `allow_from` | closed: per-agent addresses/CIDR, on `/rpc`, `/mcp` and token refresh; widening is a grant | #98 | `AllowFromTest`, `BridgeTest.allowFromRefusesOtherAddresses`, `SignInTest` |
| Plan 13: loot tables, advancements, recipe types in the catalog | closed | #98 | GameTest catalog test in `GmGameTests` |
| Lead: owner setup in one step | closed: while no owner exists, the verified level-4 operator who approves a sign-in becomes the owner; the console may name a player | #99 | GameTest `GmConfirmGameTests.aFirstOwnerInOneStep` |
| Final fresh-auditor pass | done: three auditors (rights, stability, promises); fixes in #100 | #100 | see section 2 |
| Lead's real-client findings F1–F11 | closed | #101 | see section 3 |

## 2. The final fresh-auditor pass

Three subagents that had written none of the code read the diff from main at #58 to main with #98 and #99. That is
about 7,800 added lines in `src`. They read it against AGENTS.md and the plan, with no earlier report.

- **Stability.**
  - Blocker (Fabric): a malformed `almighty-common.toml` threw out of the tick and stopped the server. Fixed.
  - Major: the `events` filter could ask LuckPerms about offline players under the feed lock. Fixed.
  - Major: parallel undos of one large change each read the whole file. Fixed.
  - Minor: OwnerApp wrote `policy.toml` without the lock. Fixed.
  - Two minors are left (section 4).
- **Rights.**
  - Major: rules writing event fields streamed every player's position below `observe.players`. Fixed.
  - Major: zones held only at a bot's spawn point. Fixed: a bot below owner stops in a zone denying it.
  - Minor, fixed:
    - any member could decide `build` asks for admins;
    - `cancel` ignored the job's requester;
    - forgery through bot sign text, keybind parts and Cherokee/Coptic letters;
    - free-camera images drew players.
- **Promises.**
  - The privacy notice understated what the AI reads.
  - The friends template wording was wrong.
  - Tool texts were missing: the rule budget cap, `undo_change` for commands, waiting commands, who is heard.

  All fixed.

## 3. The lead's real-client findings (#101)

| Finding | Fix | Test |
|---|---|---|
| F7 `/gm` went to the unused `gm` agent | messages go to the configured agent while it runs, else to the owner's own running app (an agent with an owner ceiling, as `approve … owner` and the first-owner flow make it), never to someone else's agent | `AgentsTest.playersReachTheAgentThatRuns` |
| F6 one sentence did not connect Claude Code | the 401 body and console line say no key is needed and give the commands | `SignInHttpTest`; **real run**: headless Claude Code 2.1.332, the lead's exact sentence, in a clean folder, on a dedicated 1.21.1 server. It ran `claude mcp add` itself (`almighty … Needs authentication`) and told the owner to sign in with `/mcp` and approve in game |
| F1 "Done" for a failed command | outcome follows `ok`; a command above the requester's level is not put to them | confirm GameTests |
| F4 undo of a door dropped a door | an undo or revert that writes both halves of a door, bed or tall plant skips their shape update; builds keep vanilla's | GameTest `undoOfADoorDropsNothing` (reproduced the item before the fix) |
| F8 silence during pause | "paused" notice (en/ru) | GameTest `aPausedAgentsPlayerIsTold` |
| F3 English and duplicate lines | translated, one line each | confirm GameTests |
| F2 receipt under a reply | none for `say` | — |
| F5 mined block reported one off | `broken_at` in the release result | `GmBotGameTests.botMinesAndPlaces` |
| F9 refusing restarted the sign-in | 10-minute wait for the same app instance (client id, redirect, address); an approval clears it | `SignInTest.aRefusedAppWaitsBeforeAskingAgain` |
| F10 [Undo] only typed into chat | runs on click | GameTest `undoOnAReceiptRunsOnClick` |
| F11 log in UTC | the policy's zone (the JVM's by default) | `AuditClockTest` |

## 4. Left open, with reasons

- **Revert pieces lease their chunks all at once.** A change spread over more than about 14 far-apart places is
  refused for the chunk budget rather than undone piece by piece. Nothing is corrupted, and the refusal is
  all-or-nothing. Taking each piece's lease when it starts is a larger change to the job queue.
- **Mojang name lookups in owner-only console commands.** `/almighty log <name>` and `approve … owner <player>` can
  look a name up on the server thread. Only owners and the console reach them; the bridge's `role` already looks
  up off the tick.
- **An agent on its own may clear granted roles.** Restricting applies at once by plan 4.10, and the owners are
  told.
- **Positions in join, leave and death events are readable with `knowledge`.** This is by design for the feed.
- **Look-alike letters.** Coverage is a table, not a proof. Clicks that act as the clicker are refused whatever the
  text says, so the remaining risk is wording only.
- **Plan 5.1 "owners can exclude a mod from the catalog".** The mission did not list it, so it is not done.

## 5. Checks with numbers

- **PRs merged:** #95, #96, #97, #98, #99, #100, #101, each after its own review round (blocking findings fixed
  first: #95 1, #96 1, #97 1, #98 1, #99 0, #100 0, #101 3).
- **CI** on every merged PR: green on all five nodes, including build with `-Xlint:all`, unit tests and GameTest.
- **Local, last runs:**
  - 1.21.1 NeoForge: GameTest 81/81, all unit tests pass;
  - 26.3 NeoForge: GameTest 74/74;
  - 26.3 Fabric: GameTest passing;
  - all five nodes compile.
- **GameTests added this mission:** 1.21.1 went from 72 to 81.
- **Real client:** the F6 run above (Claude Code 2.1.332, dedicated NeoForge 21.1.264 server, loopback).

## 6. Needs Artem's word

- The 1.0.0 release and the store uploads.
- Open Skies picks up the new jar by itself. What changes for it:
  - players' `/gm` reaches the agent that runs, when `gm` is not running;
  - a builder-floor agent's field-writing rules need `observe.players`;
  - bots below owner stop in no-build zones.

## 7. Needs the laptop

- The rest of the real-client loop:
  - an interactive browser sign-in, then `/gm` reaching the signed-in Claude Code on a fresh server (F7 end to
    end);
  - clicking [Undo] on a receipt and the 26.x confirmation dialog in a real client;
  - the door undo on 26.3 in a real client.

## 8. For the store and docs threads

- **New owner flow:**
  - On a fresh server, the owner, as a level-4 operator in game, approves the app with
    `/almighty approve <code> <name> owner`. The first approver becomes the server's owner, so the
    `/almighty owner add` step is no longer needed.
  - From the console: `/almighty approve <code> <name> owner <player>`.
  - `status` names this fix when no owner is set.
- **One sentence connects Claude Code.** A probe without a token is told that no key is needed and the exact
  commands.
- **Players' `/gm`** reaches the owner's own signed-in app when the configured `gm` agent is not running; there is
  no `default_agent` step.
- **New `policy.toml` keys:**
  - `version`;
  - `[agents.<name>] allow_from = ["203.0.113.7", "10.0.0.0/8"]`;
  - LuckPerms: integer meta `almighty.quota.build_blocks` and `give_items` per player;
  - LuckPerms on NeoForge: the context `almighty:zone=<zone>` scopes a grant to a zone.
- **New bridge method** `role {player, role, duration | until}` ("make Bob a builder until 23:00"). `file` also
  reaches `notes/<name>.md`, the owner's server notes.
- **Catalog:** `notes` kinds `recipe_types`, `loot_tables`, `advancements`.
- **Players are told:**
  - The privacy notice now says the AI reads the public chat and acts on messages addressed to it.
  - A teleport to or of a friend asks the friend.
  - While the owner pauses the AI, `/gm` gets a "paused" notice.
  - [Undo] on a receipt runs on click.
  - A request the player could not run anyway is not offered for confirmation.
  - Outcomes are one line, in the player's language.
- **Undo** never leaves a free item: door or bed halves.
- **`/almighty log`** shows the server's local time.
- **Below owner:**
  - fills or clones repeated per entity (`execute as @e …`) are refused;
  - text may show only selected players (no mobs, no stored nbt, no key bindings);
  - rules that write event fields need `observe.players`;
  - bots stop inside zones that deny them.
- **A declarative rule's block writes** can be undone (`changes`, `undo {change}`).
- **Fabric:** a broken `almighty-common.toml` keeps the current settings instead of stopping the server.
- **Guide text that is now stale** (the docs thread edits it; this mission must not):
  - `docs/guide` connect pages: no `default_agent` step is needed;
  - `docs/guide` hosting: sign-in approval needs an owner, or makes the first owner.
