# Mission almighty-cloudcheck: real-client owner-flow check in the cloud (28.11)

Tested build: the CI jars of `zentixua/almighty` origin/main `11af9be` (build run 46128823307, green on all five
nodes; it includes #105 `6a7005f`): `almighty-1.0.0+1.21.1-neoforge.jar` and `almighty-1.0.0+26.3-fabric.jar`. A local
build was not possible because Maven Central answered 429 (rate limit), so the CI artifacts were used. No code was
changed.

## Rig (cloud session, no laptop)

- Servers on loopback, fresh folders, flat world, `online-mode=false`, `white-list=false`: NeoForge 21.1.264 on Java 21,
  and Fabric 26.3 (loader 0.20.1, Fabric API 0.167.0+26.3) on Java 25 (Mojang's runtime from portablemc). Bridge on the
  default port 25650, one server at a time.
- Vanilla clients via portablemc 5.0.6 on private Xvfb displays, CPU rendering only. 1.21.1 used Mesa llvmpipe GL. 26.3
  logged "Using graphics backend Vulkan, using drivers: 1.4.318 llvmpipe Mesa 25.2.8". Players: Alex (`en_us`) and Boris (`ru_ru`).
  Input with xdotool. Russian text went in through the clipboard (`xclip` + Ctrl+V), because xdotool drops Cyrillic letters.
- Owner desk: a 1920×1080 Xvfb with xterm running the real Claude Code 2.1.332 (interactive, manual permission mode)
  in a clean folder outside any repository (`/tmp/desk/owner-*`), and Chromium (Chrome for Testing 158) for the
  sign-in page (Claude Code opened it itself through `BROWSER`). Recordings: ffmpeg x11grab.

Deviations from a stock server or app (rig, not product):
- `identity = "offline"` in `policy.toml` plus `/almighty reload`, because the dev clients have no Mojang accounts.
- `op Alex` from the console after Alex joined. The first attempt, made before Alex joined, opped the real Mojang
  account named Alex (a different UUID), so `/almighty approve` was not in Alex's command tree. Vanilla behaviour on an
  offline-mode server, not the mod.
- `difficulty peaceful`, `time set` and `tp` from the console to keep players alive and the camera on the build.
- Claude Code's `.claude/settings.local.json` allowed `mcp__almighty` (the same as answering "don't ask again" for each
  tool, which I did once in run 1). Its own two Bash commands (probe and `claude mcp add`) were approved by hand.

## Results

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | Fresh 1.21.1 NeoForge, defaults: one sentence adds the server; sign-in page; Alex (level-4 op) types exactly `/almighty approve CODE`; Alex owner, app owner rights; game-master prompt; Boris (not op) /gm answered with a build and a receipt; [Undo] works; no config edits | **pass** (two fresh servers; run 2 is the clean, recorded one) | Clean run: `connect to my Minecraft server at http://127.0.0.1:25650/mcp` → Claude Code probed `/mcp` (401 says it signs in by browser), ran `claude mcp add --transport http almighty http://127.0.0.1:25650/mcp`, said to sign in with `/mcp`. New session → `/mcp` → Authenticate → page "Claude Code wants to connect…", code 6715, `/almighty approve 6715` (evidence/1-clean-signin.jpg). Alex typed exactly that → "Alex is now an owner of this server (policy.toml). Approved: it connects as agent claude-code. As this server's first app it has your rights (ceiling owner in policy.toml) and players' /gm goes to it." (evidence/1-clean-alex-approved.jpg). Then the guide's game-master prompt. Boris: `/gm построй мне маленький деревянный домик рядом со мной` → answered in about 26 s: "[Almighty] Сделано по вашей просьбе: build #1 [Отменить]", "… build #2 [Отменить]", "[Claude] Готово! Деревянный домик стоит прямо к востоку от тебя…" (evidence/1-clean-boris.jpg). One click on build #1's [Отменить] → "Undoing change 1", house gone, `execute if entity @e[type=item,…]` → Test failed, so no items (evidence/1-clean-after-undo.jpg). Run 1 (first server): the same, Boris's house undone by click (evidence/1-boris-after-undo.jpg) |
| 2 | Same on 26.3 Fabric, approving from the console: /gm answered | **pass** | Fresh Fabric server, a new clean folder, the same sentence; page code 1060 (evidence/2-fabric-signin.jpg); console `almighty approve 1060` → "Approved: it connects as agent claude-code. As this server's first app it has your rights (ceiling owner in policy.toml) and players' /gm goes to it." Game-master prompt; Alex `/gm hi! please build me a small stone tower next to me` → 13 s later "[Almighty] Done for you: build #1 [Undo]" and "[Claude] Hi Alex! Your stone tower is done…" (evidence/2-fabric-alex-answered.jpg). Extra: the [Undo] click → "Undoing change 1", tower (with door and ladder) gone, no item entities (evidence/2-fabric-undo-chat.jpg) |
| 3 | Pause after an "away" notice: both players get the pause notice | **pass** | Claude Code's loop stopped, over 60 s passed. Boris `/gm привет, ты тут?` → "Ведущего сейчас нет; ваше сообщение сохранено." (evidence/3-boris-away.jpg). Console `almighty pause`, then Alex `/gm hello again` → "The game master is paused by the server owner; your message is kept." (evidence/3-alex-paused.jpg). Boris `/gm привет ещё раз` 2 s later → "Владелец сервера приостановил ведущего; ваше сообщение сохранено." (evidence/3-boris-paused.jpg). N2 is fixed |
| 4 | The sign-in page marks the command and code as not translatable | **pass** | Live page (headless Chromium, `ru-RU` locale): `<div class="code notranslate" translate="no">` (the code), `<pre class="notranslate" translate="no">/almighty approve 7024</pre>`, `<code class="notranslate" translate="no">/almighty deny 7024</code>`. The page itself is `lang="en"` (evidence/4-signin-page.html). That test sign-in was refused from the console afterwards |

Clip: `owner-flow-1.21.1-neoforge.mp4` (2:08, 1080p, H.264, no sound). It shows the clean run of check 1 with step
captions: one sentence → Claude Code adds the server → new session, `/mcp`, Authenticate → sign-in page with code 6715 →
Alex types `/almighty approve 6715` → "Alex is now an owner…" → game-master prompt → Boris's Russian /gm → Claude Code
builds → reply and receipts with [Отменить] → the house → one click, house gone. It lacks: the terminal text is small
(the terminal fills the left half of the desk); waits run at 2–5x speed; the [Undo] click shows two red/grey lines
left over from my earlier mis-click (see N8); the browser's "Authentication successful" tab closes too fast to read. No
black frames; freezedetect finds still spans of up to 8 s, which are screens that really do not change.

## Findings (none blocks; nothing fixed, per MISSION.md)

**N7 (minor): the receipt's undo lines are English for a Russian player.** Boris (`ru_ru`) got "[Almighty] Undoing
change 2 (positions changed since are left as they are)" and "Change 2 was not undone: …" in English. The receipts and
the confirm lines are translated (F3), but `/almighty undo`'s replies are literal (`AlmightyCommand.undo`,
`Prompts.line("Undoing change " …)`).

**N8 (minor): a second click on the same [Undo] says "Undoing" and then "already undone by change 0".** Chat lines
shifted after my first click, so my second click hit the same link. The player saw "Undoing change 2 …" and then "Change
2 was not undone: Change 2 was already undone by change 0". The refusal is correct. But "Undoing" prints before the
result is known, and "change 0" is meaningless: a receipt undo runs outside an agent call, so no journal change is open
and `GmServer.revert` records `by = 0`.

**N9 (minor, physics): an undo can drop a block that an undone block held up.** In run 1, Claude Code first built the
house with a lantern standing on the floor (build #1), then changed it to `lantern[hanging=true]` (build #2). Undoing only
build #1 by click kept that position, since it had changed after build #1. The ceiling it hung from was removed, so it
popped off as one lantern item. Doors and whole builds drop nothing (checked on both servers). This is the conflict rule
working as documented; only the vanilla support update after it drops the item.

Seen but not a finding: Claude Code read a garbled /gm (dropped Cyrillic letters from my typing) correctly and asked
Boris to confirm before building. Two of its calls about Boris were refused by his rights, and it worked from the
position in his message instead.

## Housekeeping

- GPU: none (cloud). CPU: about 35 minutes of wall time for all runs; two soft clients and a server fit in 4 cores and
  15 GB.
- Claude Code: the local-scope `almighty` entries of the three trial folders are removed, and no Almighty OAuth
  credentials remain (`mcpOAuth` empty).
- All servers, clients, displays and recordings are stopped. Everything outside this folder lived in the container
  only.
