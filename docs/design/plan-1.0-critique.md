# Critique of "Almighty: from a friends' game master to a universal AI bridge" (report.md)

Hostile review, 27.11.2026. I read only `report.md` and the repository at `main` `8d12545`. `…/` means
`src/main/java/ua/zentix/almighty/`, and `R:n` means report.md line n. I re-checked web facts today against
primary pages and source; URLs are given inline.

The core idea is right: enforce limits in the server, keep the agent loop in the harness, and serve MCP from the
jar. But the security model rests on an assumption the report itself rejects: that the LLM will pass the right
identity. Several "no-integration" shortcuts (claims via events, undo via per-command snapshots, skills via
datapacks) do not work as described. The permission layer is about three times larger than it needs to be.

---

## Blockers

### B1. Request tickets are bearer capabilities handed to the party the design calls untrusted (confused deputy)

- **Claim (R:283, R:316, R:396-404):** "A player's words must never raise what the agent may do for that player."
  The formula is `effective = … ∩ (ticket ? nodes(requester) : agent.autonomous)`, and the agent picks which
  ticket to pass with `for`.
- **Why it fails:** principle 2 says the agent is jailbreakable (R:57). Every ticket appears in the feed the agent
  reads, so a hijacked agent controls `for`. A guest's injected chat can do either of two things:
  - **Omit `for`.** The call then gets `autonomous` rights. The example grants `build@events` and `bots` (R:370),
    which is more than a guest's `build@near-me`. Leaving out the parameter *raises* the guest's power.
  - **Pass a live admin ticket.** Tickets last "10 min, extended while the conversation continues" (R:400). On any
    server where an admin talks to the GM, the agent's effective power is the highest right of anyone who spoke
    recently.
- The report names this abuse and calls it "bounded" (R:402-404). It is not bounded: the GM kit already bundles
  "up to 10 recent lines" of other players' public chat with each forwarded message (`mcp/gm/README.md:24`). So
  guest text rides inside the admin's ticketed request by design.
- **Fix: tickets attribute and lower, never raise.**
  - Use `for` only for quotas and audit, and for *narrowing* (`effective ∩ nodes(requester)`).
  - Set the autonomous floor of any agent that reads untrusted text to the guest level.
  - Allow anything above that floor only through a human confirming a server-rendered description of the exact
    call. For an admin's own request, that admin confirms it with one click.
  - Run a separate token or agent with a high ceiling that never reads public text (the owner's console agent).
  - This also removes ticket claiming, the freshness and extension logic, and "A's ticket for B" as a category.

### B2. Agent-sent text components can forge confirmations and make admins run commands

- **Claim (R:420-427):** a clickable `[Allow] [Deny]` (`ClickEvent` → `/almighty confirm c_7`) is safe because
  "the click is checked against the clicker's UUID and nodes".
- **Why it fails:**
  - `say {component}` accepts any JSON text component, click events included (`…/act/Chat.java:77-80`), and a
    `\n` inside the component starts a fresh chat line after the `[Claude]` prefix.
  - The agent gets the `c_7` id in the 202 (R:422). An injected agent can therefore send an admin
    `[Claim reward]` → `run_command /almighty confirm c_7`, or a perfect copy of the server's prompt.
  - Worse, on 1.21.1 a `run_command` click runs *any* command as the clicking admin with no prompt. Mojang added
    the confirmation only in 1.21.6: "prompted for confirmation … if the command … requires elevated permissions"
    (https://minecraft.wiki/w/Java_Edition_1.21.6). So a guest-level agent can get `/op <attacker>` run through an
    op's click.
  - Bedrock players via Geyser cannot click chat at all: "Clickable links in chat" is listed under Unfixable
    (https://geysermc.org/wiki/geyser/current-limitations/).
- **Fix:**
  - Strip `run_command` and `suggest_command` click events from agent components unless the agent's ceiling is
    owner.
  - Confirm with a nonce the agent never sees. The server sends the admin "`/almighty confirm 4821`" in a style
    agents cannot produce (prefix reserved, components from agents carrying that prefix rejected).
  - Render the request text from the parsed call, never from agent-supplied wording.
  - Accept the typed command too (Bedrock, console).
  - On 26.x lines, use vanilla server Dialogs (1.21.6+) rather than chat.

### B3. Block and item NBT carries commands: `build`, `give` and creative bots bypass the command gate

- **Claim (R:334-339, R:345):** guests and builders may get `build@near-me` and `bots`, while commands are gated by
  level and root.
- **Why it fails:**
  - `build` parses blocks with NBT allowed (`BlockStateParser.parseForBlock(blocks, s, true)`, `…/Api.java:342`).
  - A guest-ticketed build can place a command block with a `Command` tag. It can also place a sign whose text
    carries `run_command`, which runs at **permission level 2** for whoever clicks it ("Commands run with
    permission level 2 (like command blocks)", https://minecraft.wiki/w/Sign; gated by `allow_op_features` only
    since 26.3). Spawners, structure blocks and jigsaws can be placed the same way.
  - `give` and `item` with components (`written_book_content` with click events, spawn eggs with arbitrary
    `EntityTag`) do the same.
  - A creative bot (`bots.creative`, `…/bot/Bots.java:94-97`) can create any item stack through the
    creative-slot packet.
  - `bot` `payload` (`…/bot/Steps.java:151`) sends arbitrary mod packets as a player. Mods with weakly validated
    C2S packets are a known exploit class, and the node table has no `bots.payload`.
- **Fix:**
  - For non-owner principals, allow block states only in `build` (reject NBT). Otherwise allowlist NBT keys per
    block-entity type and reject `Command`, click events and spawner data.
  - Put creative bots, `payload` and item-component commands under `admin`.
  - Add one GameTest per hole ("a guest build cannot place a command block or a sign with a click event").

### B4. "Jar + one line" holds only when the agent runs on the server host; remote and panel setups get plaintext RCE

- **Claim (R:7, R:131, R:813-815):** owner setup is the jar plus one harness line. Remote agents use an SSH tunnel;
  panels use the tunnel or a host-only address.
- **Why it fails:**
  - Commercial panel hosts give customers SFTP and extra *public* ports, not shell or host networking. BisectHosting
    ("Create Additional Port", dedicated IP addon, https://help.bisecthosting.com/hc/en-us/articles/40088734057115)
    and Shockbyte ("Add Additional Port",
    https://shockbyte.com/help/knowledgebase/articles/how-to-add-an-additional-port-to-your-server) both document
    ports; neither documents SSH.
  - So the realistic hosted setup is `bridge.host=0.0.0.0` on a public port: a bearer token over **plain HTTP**
    to an endpoint that grants op 4 and JVM code execution. The design has no TLS at all.
  - Token leaks on the way:
    - `almighty connect` prints the snippet "with the token filled in" to the console (R:808). Console command
      output goes to `logs/latest.log` and the panel's console history, which every panel subuser with console
      access sees. This contradicts R:805 ("never the token itself in shared logs").
    - `claude mcp add … --header "Authorization: Bearer …"` puts the token in shell history and `~/.claude.json`.
- **Fix:**
  - Refuse a non-loopback bind without TLS unless `bridge.insecure_remote=true`. Support a PEM cert and key in the
    config (owner-provided, e.g. from their reverse proxy or a tunnel).
  - Document one blessed remote path (Tailscale/cloudflared on a VPS; panels: the host's reverse proxy).
  - Print snippets that reference the token without containing it: Claude Code expands `${VAR}` in `headers` and
    supports `headersHelper` (https://code.claude.com/docs/en/mcp), and Codex has `bearer_token_env_var`.
  - Print token values only to a player-less console *and* never through the logger (write a one-time file
    `connect-<harness>.txt`, 600).
  - State plainly: on shared hosting the bridge is an internet-facing RCE endpoint.

---

## Serious

### S1. Firing break/place events "as the requester" for claims causes side effects and breaks invariants

- **Claim (R:31, R:414-418):** fire `BreakEvent` / `EntityPlaceEvent` per changed position as the requester;
  claim mods veto with no integration.
- **Why it fails:**
  1. Other listeners *act* on those events. FallingTree fells the whole tree on `BlockEvent.BreakEvent`
     (https://github.com/RakSrinaNa/FallingTree/blob/minecraft/1.21.1/neoforge/src/main/java/fr/rakambda/fallingtree/neoforge/event/BlockBreakListener.java).
     Quest mods, XP, stats and loggers react too. A 200k-block build fires 200k gameplay events with arbitrary
     mod work, so the tick budget cannot bound it. That work can include synchronous chunk loads (a tree across a
     chunk border), against "never load chunks synchronously".
  2. It needs a live `ServerPlayer`, but tickets outlive logout (R:400).
  3. OPAC exempts fake players by a UUID/name list (`staticFakePlayers`,
     https://github.com/thexaero/open-parties-and-claims `ServerConfig.java`). Semantics vary per mod.
  4. It applies only to *ticketed* builds. Autonomous builds, or a hijacked agent that omits `for` (B1), ignore
     claims entirely.
- **Fix:** do not fire gameplay events.
  - Use zones as the protection boundary (already in the design).
  - Add two read-only compat modules (OPAC, FTB Chunks) that *query* claim ownership. This is exactly what
    `compat/` exists for, and it costs two small classes instead of a semantic minefield.
  - Apply the claim check to every non-owner build, ticketed or not.

### S2. Command gating by Brigadier root and selector parsing is a deny list in disguise

- **Claim (R:334-335, R:345):** detect `people.*` by "parsed roots + resolved selectors"; `command.level0…4` plus
  `command.<root>`.
- **Why it fails:**
  - Selectors such as `execute as @a at @s run …` resolve only at execution.
  - `function` lines with `$(…)` macros cannot be parsed before substitution (`…/act/CommandRunner.java:164-165`).
  - `function ns:x`, `schedule function` and datapack functions run code the gate never saw.
  - Hundreds of mod commands act on players under their own roots (`/ftbranks`, `/effect`, `/attribute`,
    `/damage`, `/item replace entity`, `/data modify entity`, …). An enumerated `people.*` list is permanently
    incomplete.
- **Fix:** for ticketed calls, run commands **as the requester** (their `ServerPlayer` source and their own
  permission level). Vanilla `requires`, and LuckPerms where a mod maps commands to nodes, then decide exactly as
  if the player had typed the command. Keep root parsing only for the built-in crash deny list (an accident guard,
  not security). Above the requester's level, use B1's confirmation path.

### S3. The undo of `/fill`, `/clone`, `/place` and `/give` via pre-command snapshots is unbuildable as described, and undo overwrites later player work

- **Claim (R:435-439):** box snapshots before `fill/clone/setblock/place`, inventory snapshots before
  `give/clear/item`, persisted across restarts.
- **Why it fails:**
  - Boxes are known only at execution (`execute positioned … run fill ~ ~ ~`, `@a` fan-out).
  - `place structure/jigsaw/feature` has no box before it runs and can touch unloaded chunks.
  - Snapshots of 32k-block fills taken synchronously inside the command are unbudgeted.
  - Undo is blind: `BuildJob.undo` restores the snapshot with no check that positions still hold what the job
    wrote (`…/building/BuildJob.java:95-113`). Restoring days later (persisted, `/almighty undo` for admins)
    silently deletes what players built since.
- **Fix:**
  - Either drop command undo and point to world backups, or record actual writes. Use one mixin on
    `LevelChunk.setBlockState`, active only while a GM call runs on the server thread (ThreadLocal change id).
    This also covers functions and *scripts*, which the report says cannot be undone.
  - In either case, restore only positions whose current state equals the recorded *after* state, and report
    conflicts.
  - Ledger does exactly this kind of write logging on Fabric (`FillCommandMixin`,
    https://github.com/QuiltServerTools/Ledger). The NeoForge loggers (GriefLogger etc.) log only player events.

### S4. Virtual threads remove the only backpressure on unbudgeted server-thread work

- **Claim (R:607, R:670-672):** a virtual-thread executor removes the 6-thread cap "at no cost"; fair share per
  agent inside the 3 ms budget.
- **Why it fails:**
  1. Only jobs are budgeted. `onMain` calls (`command` up to 100 commands, `function` up to 10,000 lines,
     `entities`, `blocks`, `player`) run unbudgeted in `server.execute` (`…/GmServer.java:153-155`).
     `script` blocks the server thread for up to **10 s** (`…/Api.java:63`). Today the fixed pool of 6
     (`…/bridge/Bridge.java:56`) caps how much of that N agents can queue at once. Unbounded threads let
     several agents starve ticks.
  2. On Java 21 (MC 1.21.1), `Feed.read` is `synchronized` + `Object.wait` (`…/feed/Feed.java:52-58`), which pins
     the carrier thread. The pinning fix (JEP 491) arrived in JDK 24, so on Java 21 each long poll still holds an
     OS thread.
- **Fix:**
  - Keep a bounded pool for world calls. Give long polls their own pool or virtual threads with a
    `ReentrantLock`/`Condition` feed.
  - Add a per-tick budget for `onMain` work: queue the calls and run them while `work.ms_per_tick` remains, as
    jobs do.
  - Cap `script.timeout_ms` per agent (e.g. 50 ms unless owner).
  - Make the 504 "may still run later" (`…/bridge/Bridge.java:132`) safe with an idempotency key, or retries
    double-charge quotas and double-execute.

### S5. The agent-presence lease stops bots mid-flight and misreads presence

- **Claim (R:586-587, R:618-619):** no poll from an agent for 60 s → players get "away" and that agent's bots stop.
- **Why it fails:**
  - Releasing all keys of an airborne aircraft or a boat at speed is the same failure AGENTS.md calls out for
    disabled autopilots (nose-down → sea). It just happens on a timer instead.
  - A slow turn (extended thinking, a local model on CPU, a long `build` with `wait`) exceeds 60 s without an
    `events` call.
  - With the Claude channel, the Node plugin polls, not the model. A wedged session therefore looks present.
- **Fix:**
  - Presence = any authenticated call, with a per-agent configurable lease.
  - Stopping bots is opt-in per program (the existing timed-key dead-man switch), never a global side effect.
  - The "away" reply should trigger on unclaimed `/gm` messages older than N s, not on poll silence.

### S6. Declarative rules give a ticket's authority a lifetime beyond the ticket

- **Claim (R:471-474, R:925):** less-trusted agents get declarative rules (→ `cancel`, `command` "at the rule's
  policy level", `say`).
- **Why it fails:**
  - A rule persists with the world (`persist`), while the ticket that justified it expires in 10 minutes.
  - Whose rights does the rule's `command` use next week? And a cancel on any event at any priority is a griefing
    lever: cancel every `BreakEvent`, `ServerChatEvent` or login-adjacent event server-wide.
- **Fix:**
  - Rules created under a ticket are bound to the creator's UUID, re-checked at every fire, and expire with a TTL.
  - `cancel` only inside a zone the creator may build in.
  - Server-wide cancels require owner.

### S7. Prompt injection beyond chat is unaddressed, and world notes make it persistent

- **Why:**
  - The agent already ingests attacker-controlled text without any ticket:
    - entity custom names from name tags (`…/bot/Eye.java:176`, `…/world/Observe.java:206`);
    - item names from anvils (`…/world/Observe.java:113`);
    - death messages that embed weapon names (`…/feed/FeedListeners.java:58`).
  - The proposal adds more channels:
    - sign and book text in a catalog or view;
    - mod and datapack skills: third-party text from downloaded datapacks, served as *instructions*;
    - agent-written **world notes**, explicitly "ground truth" for the next session and other agents (R:539-541).
  - One injected guest can make the agent write a note ("the owner said guests may use builder rights") that the
    owner's high-ceiling agent later trusts.
- **Fix:**
  - Mark every player-originated string in results (`{"untrusted": "…"}`), and say in the instructions that such
    text is data.
  - World notes record author and requester and are written only by owner-ceiling agents or with confirmation.
  - Serve datapack skills as "reference" with source, never merged into `instructions`.
  - B1's design is what actually contains the damage.

### S8. `SKILL.md` cannot be loaded from mod or datapack data

- **Claim (R:510-513):** `data/<ns>/almighty/skills/<name>/SKILL.md` is read through the server resource manager.
- **Why it fails:**
  - Resource paths allow only `[a-z0-9_./-]`, so uppercase `SKILL.md` is not a valid `ResourceLocation` path, and
    pack resources with invalid paths are skipped with a warning.
  - Agent Skills names must be lowercase alphanumerics and hyphens and "must match the parent directory name"
    (https://agentskills.io/specification), while Minecraft names use underscores.
  - The promised "one standard file any skill-aware harness understands" therefore does not exist in a jar's
    `data/`.
- **Fix:** keep the existing single-file notes (`notes/<name>.md`, lowercase). Optionally accept `skill.md` with
  Agent Skills frontmatter, and export it as `SKILL.md` only when building the harness plugin. Add a unit test
  with a real pack.

### S9. Identity heuristics are wrong for proxies and Bedrock

- **Claim (R:406-408):** `online-mode=false` without proxy forwarding → identity "unverified", guests only.
- **Why it fails:**
  - Forwarding on NeoForge comes from third-party mods (Proxy Compatible Forge, NeoVelocity, …,
    https://github.com/adde0109/Proxy-Compatible-Forge), and no vanilla or NeoForge API reports it. The check is
    either impossible or a `ModList` guess.
  - Every proxied network runs `online-mode=false`, so all of them land on "guests only", or owners turn the
    check off.
  - Bedrock players arrive with Floodgate UUIDs `00000000-0000-0000-xxxx-…` and a `.` name prefix
    (https://github.com/GeyserMC/Floodgate). Geyser/Floodgate NeoForge builds for 1.21.1 stopped in Nov 2024
    (Modrinth API), so on 1.21.1 Bedrock arrives only through a proxy.
  - Bedrock players also cannot click (B2).
- **Fix:**
  - Make it an explicit owner setting (`identity = "mojang" | "proxy" | "offline"`), shown in `status`, and do not
    infer it.
  - Recognise Floodgate UUIDs as their own verified namespace.
  - Use typed confirmations.

### S10. The permission layer is over-built: three policy systems where one plus a file would do

- **Why:** the design ships, at once:
  - presets;
  - `policy.toml` roles with inheritance;
  - monotonic delegation with `/almighty role`;
  - zones, both relative (`near-me`) and absolute;
  - `when` time windows in server time (a VPS in UTC, players in Warsaw);
  - quota windows persisted per world;
  - LuckPerms nodes, meta and the custom `almighty:zone` context;
  - agent ceilings and autonomous sets;
  - policy proposals with a natural-language diff renderer, history and revert;
  - plus 8+ `/almighty` subcommands.
- Two sources of truth when LuckPerms is installed (players in LP; zones, quotas, windows and agents in TOML) is
  the classic misconfiguration trap.
- "Owner-side minimal" holds for the harness. On the server side the owner gains `policy.toml`, `agents/*.token`,
  `scripts/*.groovy`, `quotas.json`, `changes/`, `policy.history/` and audit logs.
- **Fix:**
  - Without LuckPerms: `owners`, three presets, per-agent ceiling and zones. Nothing else.
  - Multi-role servers use LuckPerms (free, standard, has an editor, temp grants and a log).
  - Drop `when`, in-house roles, delegation and the policy-proposal machinery. An agent that wants a policy
    change asks in chat, and the owner edits the file or the LP editor.
  - This cuts step 8 roughly in half, and the riskiest parts with it.

### S11. Multi-agent routing by "first claim wins" is a race, not a design

- **Claim (R:401, R:612-613):** a ticket is claimed by the first agent that uses it.
- **Why it fails:**
  - A narrator agent's read-only `status {rights}` claims the ticket and blocks the builder.
  - Every agent reads and pays tokens for every event.
  - "3 requests ahead" (R:611) contradicts "requests queue in the agent".
  - The owner pays per LLM token, yet the quota counts tool calls; nothing limits how many `/gm` messages a guest
    can push into the paid model.
- **Fix:**
  - Route at mint time: policy maps message kind or addressee to one agent (`/gm @builder …`).
  - Filter `events` per agent.
  - Add a `gm_messages` per-player rate limit before the event is minted.

### S12. Dual-era hand-written MCP is the wrong first step

- **Claim (R:655-657):** serve legacy and 2026-07-28 on one endpoint, "a few hundred lines (estimate)".
- **Evidence:**
  - Dual-era support is a **MAY** (https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning).
  - Codex defaults to legacy (`#[default] Legacy`,
    https://github.com/openai/codex/blob/main/codex-rs/rmcp-client/src/protocol_mode.rs).
  - A Claude Code channel on 2026-07-28 is not registered (https://code.claude.com/docs/en/mcp).
  - The modern era adds `server/discover`, per-request `_meta`, and `Mcp-Method` on all requests plus `Mcp-Name` on
    `tools/call`, `resources/read` and `prompts/get` (the report lists only tools/call). It also adds `-32020`
    HeaderMismatch errors and modern error bodies that steer client fallback.
  - With the port of `_need/_only/_points` validation and 14 schemas, the work is well beyond a few hundred lines.
- **Fix:** ship legacy stateless Streamable HTTP only (initialize, tools/list, tools/call, ping, JSON responses).
  Every listed client speaks it. Add the modern era when a target client drops legacy, behind the same tool
  registry. Keep the Java SDK out, as argued (v2.2.0 modern server rejects `initialize` and needs a Servlet
  container, verified).

---

## Minor

1. **Catalog on the server thread (R:495-501).**
   - Tens of thousands of recipes plus the full Brigadier usage tree (`getAllUsage` explodes on redirects) cause a
     tick spike.
   - Build the catalog off-thread from the frozen registries and the post-`/reload` `RecipeManager` reference,
     and never expand the whole command tree.
   - "Config values of mods' configs" must exclude secrets. Discord Integration keeps `botToken` and SDLink keeps
     `botToken` in TOML (their own config systems today), so any widening to "files in `config/`" leaks
     credentials to the model provider.
2. **Groovy 5 is not forced by Java 25 (R:763, R:903).** Groovy 4.0.27 added the JDK 25 constant and ASM 9.8;
   4.0.33 reads JDK 26/27 class files (https://groovy-lang.org/releasenotes/groovy-4.0.html). Test 4.0.33 on 26.x
   before taking Groovy 5's semantic changes into user scripts.
3. **Internal contradiction on 512 characters.** R:84 says Codex reads the first 512 characters of instructions;
   R:681 says ChatGPT. One of them is wrong.
4. **Elicitation "fails in non-interactive runs" (R:94, R:427) is over-cited.** The Claude Code page says only
   that MCP *tasks* are cancelled in `-p`/SDK. The conclusion (server-side confirmation first) stands anyway.
5. **"Origin: native clients send none — unverified" (R:669) is resolvable.** The spec requires 403 only when
   Origin is "present and invalid"; absence is allowed. Accept absent, reject present-and-unlisted.
6. **"8 → 2 moving parts" (R:16, R:120-131) is padded.**
   - Counting "hooks port" and "token path" as parts inflates today's number.
   - The basic path today is jar + uv adapter (2-3).
   - The full Claude GM experience after the change is still jar + plugin + Node channel.
   - The honest win is "no uv/git on the basic path"; a PyPI `uvx almighty-mcp` would have given most of it
     cheaply.
7. **Four delivery paths for the same know-how:** tool texts, `notes`, the Skills extension, and two plugin
   formats. Keep `notes` + short instructions (work everywhere); add the plugin and the extension only on demand.
8. **Audit rotation is a reinvented wheel.** Use a dedicated log4j2 logger with a `RollingFileAppender` and a
   JSON layout (already on the server classpath) instead of hand-rolled rotation and size caps.
9. **Time windows and quotas use "server time zone".** Name the zone explicitly in the file, or drop `when`
   (S10).
10. **`/almighty pause` without token revocation.** Add `/almighty revoke <agent>` (rotate that token file); a
    leaked token is the more likely incident.
11. **The Fabric port loses the central lever.** "Any event of any mod" becomes "callbacks found by scanning with
    a Proxy". That is a different product with different guarantees, and should not appear under the same promise
    on the store page.
12. **`policy` behind `call` (R:478).** Hiding an owner-confirmed mutation behind the generic escape hatch makes
    it invisible to `readOnlyHint`/`destructiveHint` approvals in harnesses. Annotate `call` as destructive (done)
    and say so.

---

## Right; do not change

- **Enforcement in the server at one choke point, nothing in harness hooks.** Move the crash deny list from
  `crash_guard.py` into the bridge. Deny by default via a unit test on undeclared methods.
- **No sandbox pretence.** The Security Manager is gone (JEP 486), and SecureASTCustomizer "is not a sandbox".
  `engine` stays owner-only; `scripts.enabled=false` on public.
- **Server-rendered human confirmation as the primary path**, not elicitation (with B2's fixes).
- **Owners by UUID in a file**, never from the permission plugin.
- **MCP served by the jar over HTTP with a static bearer**, one Java tool registry, no Java SDK, the Python
  script reduced to a CLI.
- **The 14 tools stay; new power arrives as parameters and knowledge.** Instructions stay under the 4,096 cap
  (verified) with notes by progressive disclosure.
- **Per-agent tokens, `/almighty pause`, JSONL audit** with requester and decision.
- **Preset `private` reproduces today's behaviour**, and Open Skies is pinned to it.
- **Ticketed commands run as the requester** rather than at level 4 (taken further in S2).
- **Text alongside every image, and `images=false`** for models without vision.
- **Architectury avoided; Paper "not now"; Modrinth's AI rule** (§6.2, verified verbatim, last modified
  13.08.2026) raised as an Artem decision, not glossed over.
