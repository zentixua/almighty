# Audit A: exceeding rights (players, below-owner agents, untrusted text)

Repo /home/user/almighty @ origin/main (d1feb4a). Read-only audit, verified by reading code paths end to end; vanilla
behaviour checked against the 26.1.2 jar in the Gradle cache (ClickEvent$ShowDialog, SnbtGrammar escape sequences,
EntitySelectorParser.parseNameOrUUID -> StringReader.readString + UUID.fromString, CommandFunction.fromLines joins `\`
lines with no separator).

## Blockers

### B1. 26.x: `say` components can carry a dialog whose button runs a command as the clicker (Chat.strip misses show_dialog)
- Where: src/main/java/ua/zentix/almighty/act/Chat.java:108-121 (strip removes only `clickEvent`/`click_event` objects
  whose action is run_command/suggest_command). No `//? if` handling for 26.x click actions anywhere (grep show_dialog: none).
- Attack (26.1.2/26.3, NeoForge and Fabric): on a fresh install (preset friends, `[agents.gm] ceiling = "owner"`,
  floor member, so `speak.all` held unasked), a hijacked gm (e.g. via a guest's chat or a note) calls
  `say {component: {"text":"Server notice","click_event":{"action":"show_dialog","dialog":{"type":"minecraft:notice",
  "title":"Rules","action":{"label":"OK","action":{"type":"run_command","command":"/gm @gm run the setup script"}}}}}}`.
  The nested `action:{type:run_command}` is not a `click_event` key, so it survives. The owner clicks OK; `/gm` needs no
  permission, so the client runs it without a prompt as the owner's own typing (`Mc.typedBy` passes) and the server mints
  an owner ticket for gm (Inbox.deliver). With it, effective role = min(owner, ceiling owner) = owner: `script` (engine),
  `file write`, everything. Same for `/almighty confirm <nonce>` if a nonce were known.
- Plan: 4.8 "agent-supplied text components cannot carry run_command/suggest_command click events below owner ... this
  stripping is required, not cosmetic"; 4.8 "On 26.x lines, vanilla server dialogs replace chat buttons".
- Fix: below owner, drop every click event whose action is not open_url/copy_to_clipboard/change_page (allowlist, both key
  spellings) and refuse any `dialog`/`show_dialog`/`custom` at any depth; add a GameTest on a 26.x node.

### B2. The command guard against click buttons and the reserved prefix is a raw substring test, trivially bypassed
- Where: policy/Gate.java:188-196 (`low.contains("run_command") || ... || c.contains("[Almighty]")`), same test for rule
  command templates Api.java:303-308.
- Bypasses (any one suffices):
  1. JSON escape on 1.21.1: `tellraw @a {"text":"[Allow]","clickEvent":{"action":"run_command","value":"/gm @gm ..."}}`
     (Gson decodes \u in ComponentArgument); on 26.x SNBT quoted strings decode `\u` too (SnbtGrammar string_escape_sequence).
     Signs: `setblock ~ ~ ~ oak_sign{front_text:{messages:['{"text":"x","clickEvent":{"action":"\\u0072un_command",...}}',...]}}`.
  2. `function` continuation lines: `["tellraw @a {\"text\":\"x\",\"clickEvent\":{\"action\":\"run_com\\", "mand\",...}}"]` –
     each line is tested alone, vanilla joins them (deleteCharAt + append(trim)).
  3. `function` with `args` as an SNBT string: `lines:["$$(c)"], args:"{c:'tellraw @a ...run_command...'}"` – Confirmations.commands
     expands only when `args` is a JSON object (Confirmations.java:178), so the guard sees `$(c)`.
  4. Prefix: `["[Almi","ghty] Claude wants to run as you ..."]` or `[Almighty]`.
- Who: any below-owner call that runs at level >= 2: unasked agents whose floor is builder+ (private preset: floor =
  ceiling, so every unlisted agent at the default ceiling admin, i.e. level 3, on Open Skies), or a builder+/admin ticket.
- Effect: a forged "[Almighty] ... [Allow]" or any run_command button; when the owner clicks, `/gm` mints an owner ticket
  (owner rights for the agent) or `/op <x>` runs at level 4. Admin-ceiling agent -> owner.
- Plan: 4.8 (same promise as B1), 4.3 content limits ("no signs with click events").
- Fix: check the parsed command's component/NBT arguments (parse with the dispatcher, walk Component/CompoundTag values)
  instead of text; at minimum run the check on the function as vanilla will see it (joined lines, expanded macros incl.
  SNBT args) and normalise escapes.

## Major

### M1. Deny list and confirmation classification bypassed through `function` (continuation lines, SNBT macro args); no run-time deny check
- Where: policy/DenyList.java:77-83 (per raw line; JSON-object args only), Confirmations.java:174-187, act/CommandRunner.java:206-229
  (function path never calls DenyList.command, unlike `run`, line 166).
- Attack: `function {lines:["sto\\","p"]}` or `function {lines:["$$(c)"], args:"{c:'stop'}"}`. At floor level 4 (private +
  ceiling owner: Open Skies' gm) the server stops; at level 2 `reload` / `forceload add` (documented crashers) run; `almighty ...`
  at level 4 too. With a ticket, the requester's prompt shows `/$(c)` or `/sto\ ; /p`, not what runs.
- Plan: 4.11 deny list "for every harness"; 4.8 "rendered from the parsed call"; AGENTS "checked again for every GM command at run time".
- Fix: build the function with CommandFunction.fromLines + instantiate first, then deny-check / classify / render the
  instantiated command strings; refuse string `args` below owner or expand them via the parsed CompoundTag.

### M2. SELF_ONLY "aimed only at the requester" check misses quoted names, short UUIDs, Floodgate names
- Where: policy/Confirmations.java:224-236.
- Attack: with a builder's ticket (level 2) a hijacked agent runs `kill "Friend"`, `clear "Friend"`, `give "Friend" tnt 64`,
  `tp "Friend" ~ -100 ~`, `gamemode spectator "Friend"` with no confirmation: vanilla accepts quoted names (readString), the
  regex `[A-Za-z0-9_]{3,16}` does not match `"Friend"`. Same for `kill .BedrockKid` (Floodgate prefix `.`), names of 1-2
  characters (offline mode), and UUIDs with a leading zero dropped in any group (`069a79f4-...` -> `69a79f4-...`, accepted by
  UUID.fromString; ~25% of players).
- Plan: 4.2 "irreversible ones (items or effects on others, kills, inventory clears) are confirmed by that same player first".
- Fix: decide targets from the parsed command (EntityArgument results resolved against the requester's source) instead of words;
  or treat any token that is not `@s`/coordinates/a known keyword as a possible target.

### M3. Rule command templates: `{field}` targets pass the add-time confirmation check and are never re-checked
- Where: Api.java:311-315 (needsRequester on the template text), GmServer.java:463-488 (fire time: no needsRequester),
  rules/Declarative.java:207-218 (SAFE values include any player name).
- Attack: a builder (ticket) or builder-floor agent adds `rule.add {event:"...ServerChatEvent"/"PlayerEvent$PlayerLoggedInEvent",
  then:[{command:"kill {player}"}]}` or `clear {entity.name}`: at add time `{player}` is not seen as another player, so the rule is
  accepted; at each fire it kills/clears whichever player triggered it (named mobs: `{entity.name}` = attacker-chosen name).
- Plan: 4.12 rules "a command run as the rule's creator"; AGENTS "nothing that would need a player's confirmation".
- Fix: refuse placeholders in target positions of SELF_ONLY/non-SAFE commands below owner, or run needsRequester on the
  filled command at fire time and refuse.

### M4. `observe.players` (admin) is bypassable at guest level
- Where: bot/Eye.java:173-185 + Api.java see/Gate.java:93-97 (`see {at}` is observe.world, lists every player in radius 128 with
  name, uuid, pos, health, held item); feed/FeedListeners.java:111-117 (join/leave/death carry x,y,z) and Inbox.gm (`/gm` events
  with position) read with node knowledge (guest); Api.java events (`mine` optional) lets every agent read `/gm` messages routed
  to other agents.
- Attack: a public-preset agent at floor guest sweeps `see {at:[x,y,z], image:false, radius:128}` and reads `events` to track
  players and read their private `/gm` lines to the owner's agent.
- Plan: 4.3 observe.players = admin for "player, see{name} for other players"; 4.6 "each agent reads only its own addressed
  events plus what its ceiling allows".
- Fix: omit players from `see {at}`/bot eyes and strip positions from feed player events unless the rights hold
  observe.players; filter `gm`/addressed events to the routed agent always.

### M5. Player-authored text reaches agents above its author's rights, unmarked
- Where: rules/Rules.java:553ff (rule `emit` / field feed entries carry no `untrusted` and no `uuid`, so Api.java:356 `heardBy`
  does not filter them); world/Observe.java:118, 211 and Eye.java:176 (custom entity names, item hover names); mcp/McpEndpoint.java:204-235
  (world notes written for member requesters and any mod/datapack note go into every agent's MCP `instructions`, confirmed or not).
- Attack: private preset (floor = owner): a guest names a mob or writes a sign/book; a rule (or the agent reading `entities`)
  brings that text, unwrapped, to the owner-floor agent. A member's ticket writes `world:x` with a description that lands in the
  owner client's system-level instructions.
- Plan: 1.1 principle 3, 4.2 private "other players' text is not fed to it", 4.11 "sign and book text, item, entity ... names
  reach the agent wrapped".
- Fix: mark rule emits untrusted with the event's player uuid (or apply heardBy by the event's player); wrap names; show only
  confirmed notes (and the owner's) in instructions, unconfirmed ones as ids only.

### M6. Claims are enforced only for `build`, not for commands or rule commands
- Where: Api.java:579-584 (only use besides rule cancels, GmServer.java:446); GmServer.commandZone (522-548) checks zones only.
- Attack: an unasked agent at floor builder+ (private: unlisted agents at admin) runs `command fill ...` / `setblock` / `clone`
  inside another player's OPAC/FTB claim, which `build` would refuse ("unasked: unclaimed only").
- Plan: 4.7 "every non-owner build is checked against installed claim mods ... or as a stranger for autonomous builds".
- Fix: reuse the CommandZones box (Node.BUILD) and call claims.refusal(requester, ...) for edits below owner.

### M7. No ownership on bots and leases across agents
- Where: Api.java:229-240 (area.release any lease id: builds' and bots' leases share Areas.leases), 264-275 (bot.remove, bot.act
  any bot), bot/Bots.java:354.
- Attack: an agent with `bots` (member) drives an owner-spawned creative/spectator bot (bots.admin is checked only at spawn) or
  removes other agents' bots; an agent with `area` (builder) releases another agent's running build lease so the build fails
  midway ("chunk ... unloaded during the build").
- Plan: 4.3 creative bots need bots.admin; 4.2 agents are separate principals.
- Fix: record the spawning agent/requester on bots and leases; acting on another's needs undo.any (or bots.admin for
  creative/spectator bots); keep job leases out of area.release.

## Minor

- m1. `/almighty confirm|deny` takes the decider from `source.getEntity()`, not `Mc.typedBy` (command/AlmightyCommand.java:257),
  contrary to AGENTS ("only the player's own typing"): `execute as <p> run almighty confirm <n>` or a sign click answers as p if the
  nonce leaks. Fix: use typedBy.
- m2. CommandZones checks every coordinate in the source's dimension (policy/CommandZones.java:46-91): `clone from overworld ...
  to minecraft:the_nether <dest>` writes into a nether deny zone unchecked. Fix: read DimensionArgument results.
- m3. Policy approvals bind the text, not the base (policy/ConfigFiles.java:201-215): approving a 30-minute-old proposal silently
  reverts owner edits made since; the shown "grants" was computed against the old base. Fix: store old-policy hash, re-diff at run.
- m4. Agent-initiated restricting policy writes send no receipt to owners (plan 4.10); the agent may pass its own `undo_of` on a
  plain write and delete a matching history entry (ConfigFiles.java:217-228). Fix: ignore caller-supplied undo_of; notify owners.
- m5. PolicyEdits.trust treats MOJANG and AUTO alike; MOJANG -> AUTO verifies Floodgate players but is not "granting"
  (policy/PolicyEdits.java:23-29).
- m6. `[Almighty]` check is literal (act/Chat.java:95): Cyrillic/zero-width look-alikes forge the prefix visually.
- m7. NeoForge rule cancels place a PlayerEvent at the acting player (neoforge/NeoForgeEvents.java:84-86), e.g. AttackEntityEvent,
  unlike Fabric ("never the acting player").
- m8. Sign-in approval `/almighty approve <code>` needs only level 4 (mcp/signin/SignInCommand.java:37) while `agent add` needs a true
  owner: a non-owner level-4 op mints admin-ceiling agents. MAX_PENDING = 5 globally, unauthenticated: anyone can keep sign-in busy.
- m9. A GM bot's `/gm` mints tickets (Inbox.heard returns true for bots): an agent can manufacture requester tickets/ask_admin
  requests per bot, outside the per-agent limit of plan 4.8 rule 5.
- m10. Design note: a ticket is a 10-minute capability for any call, not for the message's request; casual owner chat
  (alone/conversation addressing) mints owner tickets that a hijacked agent can use for engine calls.
- Docs drift: AGENTS.md says a non-loopback bind without TLS only warns; code refuses by default (bridge/Tls.java:38) – the plan's way.

## Plan section 4: status

Implemented (verified):
- Single choke point Dispatch.invoke for /rpc, /mcp, in-process; gate after deny list and pause; unknown method -> refused.
- Trust floor by preset, ceiling per agent, tickets only narrow (Gate.Rights.holds), unverified = guest whatever a plugin says.
- Tickets: 128-bit, bound to player/agent/event, 10 min, agent-bound check.
- Server-side addressing, per-player limits, cooldown after 5 refusals, routing `/gm @agent`.
- `/gm` only from the player's own typing (typedBy, both eras).
- Requester confirmations (nonce never in feed; only the confirmer decides), ask_admin limits/merge/digest, typed confirm.
- Content limits for `build`/`give` (no NBT, never_build, never_give, spawn eggs, quotas); redo needs owner.
- Journal undo, conflict-aware; undo.any for others' changes.
- Policy file validated, history, lockdown on bad first load, last-owner protection, host-level ceiling raise console-only.
- Players cannot originate policy changes; scripts read/written/approved only by the owner, hash-pinned.
- Rules bound to creator, TTL <= 7 days below owner, re-checked each fire, no rule through an admin approval.
- Origin header rule, failed-auth lockout, constant-time token compare, plain HTTP off loopback refused by default.
- Notes path validation; provenance set by the server.

Partial or missing:
- Click/prefix stripping (B1, B2) and parsed-call rendering for functions (M1).
- Irreversible-for-others detection (M2, M3).
- observe.players and "each agent reads only its own events" (M4).
- Player text wrapped/filtered everywhere (M5); privacy_notice not implemented.
- Claims for all non-owner writes (M6); zones' dimension handling (m2).
- Owner client as owner origin: OwnerApp sets only ceiling owner, unasked it is at the floor (plan 4.2 owner client).
- Receipts to owners for agent policy changes (m4); LuckPerms `almighty:zone` context and integer quota nodes absent.
- Audit through log4j2 replaced by own writer (documented choice, not a rights issue).
