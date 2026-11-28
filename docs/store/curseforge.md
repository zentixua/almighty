# Almighty: AI Game Master

![Almighty: an AI game master for your server](https://raw.githubusercontent.com/zentixua/almighty/main/docs/brand/store/curseforge-header-1600x400.png)

An AI game master for your Minecraft server. Minecraft 1.21.1, 26.1.2 and 26.3, on NeoForge and Fabric.

Almighty connects an AI agent, such as Claude Code or Codex, to your server. The agent becomes the game master: it
sees the world, talks to players, builds, runs events and plays alongside you with its own bot players. You decide
how much of that it may do, for whom and where. Players install nothing.

## Features

### What the game master can do

- **See the world:** a top-down map, a building from any side, or the view through any player's or bot's eyes.
- **Follow the game:** chat, joins, deaths, advancements and player commands reach it as they happen.
- **Talk to players:** in chat, as a title or above the hotbar. Players can write to it privately with
  `/gm <text>`, or address it by name in chat ("Claude" by default). By default, each player who joins is told that an AI
  reads the public chat and acts on messages addressed to it.
- **Build:** from a few blocks up to two million, placed a little each tick so the server keeps running.
- **Run the game:** commands, items for players, and rules that react to events of the game and of other mods.
- **Learn your modpack:** blocks, items, recipes, loot tables, advancements and commands of every installed mod, read
  from the running server.
- **Play alongside you:** bot players that walk, mine, build, open chests, ride horses, camels, pigs, striders and
  boats, and fly Immersive Aircraft planes. A bot uses only what a real player's client can send.

![What a bot sees: the eye view of an airport, drawn by the server, with the crosshair in the middle](https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/eye.png)

![The map view: an airport from above, drawn like a vanilla map](https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/map.png)

### You decide what it may do

- **A request is not an order:** when a player asks for something, the game master acts with that player's rights,
  never more.
- **Presets:** a new server starts as `friends`: players with a verified Minecraft account talk to the game master,
  and on its own it acts as a member would. On a public server, use `public`: everyone talks to it, and on its own it
  acts as a guest would. On `private`, only the owners talk to it, and on its own it acts with its full rights.
- **Confirmation:** something that affects other players and cannot be undone waits until the player who asked presses
  [Allow] in chat, or in a dialog on 26.x; the question is in the player's game language, English or Russian. A
  teleport between the player and a friend asks the friend instead. A request beyond the player's rights, or a call
  beyond the game master's own rights, is refused, unless you list its kind under `ask_admin`; then an admin can
  approve it with one click, and the owners online are told of every such decision.
- **Roles:** owner, admin, builder, member and guest. Give one for a while with `/almighty role <player> builder 3h`
  or by asking ("make Bob a builder until 23:00"), or let LuckPerms decide through the `almighty.*` permissions, on
  NeoForge also for one zone only (`almighty:zone=<zone>`).
- **Zones, quotas and claims:** keep spawn free of building, give the game master an arena, limit blocks and items per
  player per hour (LuckPerms can set a player's own limit). With Open Parties and Claims, FTB Chunks or, on Fabric,
  any protection mod that uses the Common Protection API, it changes blocks for a player, by building or by commands,
  only where that player may build, and on its own only outside claims.
- **Limits:** command blocks, spawners, barriers and similar blocks are never built for a player, and items go only
  to the player who asked.
- **Ask in words:** tell the game master "deny building at spawn" and it changes the policy for you. Anything it
  proposes on its own that grants more waits for your confirmation.

### Safe for your server

- **Undo:** every block the game master changes is saved to disk, even if the server crashes mid-build. Any change can
  be undone, chests with their contents; what players built or stored since stays as it is. A player who asked for
  something gets a "Done for you … [Undo]" line in chat; one click on [Undo] takes it back. Building and undo never
  drop free items.
- **Pause:** `/almighty pause` stops one agent or all of them at once; players who write to the game master meanwhile
  are told it is paused. `/almighty log` shows what they did and what was refused.
- **Bots are marked:** `[bot]` before their names in the tab list and chat. Unless you allow it, a bot cannot take
  the name or skin of a player your server knows.
- **No lag:** game master work gets 3 ms per tick, and a quarter of that while the server lags. Several agents
  share that time equally, and limits on calls a minute and waiting jobs (below the owner also on bot time and
  script time) keep one agent from crowding out the rest. Commands known to crash or freeze a server are refused
  for every agent.

## What it is, plainly

Almighty is remote control of your server by an AI agent that you, the owner, run. It opens a small web endpoint on
the server, by default only on `127.0.0.1`, the server machine itself. An app you choose connects to it and signs in
with a four-digit code that you type on the server, or with a token you made for it with `/almighty agent add`; no app
gets in otherwise, and you can revoke one at any time with `/almighty revoke`. `allow_from` in
`config/almighty/policy.toml` limits the addresses an app may connect from. The endpoint is reachable from the
internet only if you put it there: use your own HTTPS reverse proxy or tunnel, or give it your certificate, and never
open a plain port. The mod refuses plain HTTP anywhere but the server machine itself, unless you allow it for a
private container network with `bridge.allow_plain_http_off_loopback = true`.

By default, only the owner may run Groovy scripts, on every preset (a permissions plugin can grant it to others,
and builders may run scripts the owner approved by name). With scripts on, the owner's agent can run any code
on the server machine with the rights of the user running the server: files, processes, network. Set
`scripts.enabled = false` if you do not need them. The server itself goes online only to read a signing-in app's
public description and to look up a Minecraft skin when the agent asks for a bot with a player's skin.

## Getting started

1. Put the jar for your Minecraft version and loader into the server's `mods` folder and start the server.
2. Tell your AI app: "connect to my Minecraft server at http://127.0.0.1:25650/mcp". For Claude Code that sentence
   is enough. `/almighty connect` shows the exact line or link for each app.
3. The app opens a sign-in page with a four-digit code and the command to type. Type it in game as a level-4
   operator with a verified Minecraft account: `/almighty approve <code>`. On a new server this makes you its owner
   and gives your app your rights; there is no other step. Later apps get less (no scripts, only the commands their
   rights allow) unless you approve them with `/almighty approve <code> <name> owner`.

Players' `/gm` messages then reach your app, with nothing more to set up.

Once connected, the game master gets a check of your server's setup (preset, owners, identity, claims, HTTPS) and
tells you about any problem, with its fix.

Claude Code, Codex (also in the ChatGPT desktop app), Cursor, VS Code, opencode, Gemini CLI, Antigravity and local
models all work. For a server on a VPS or a hosting panel, see
[Hosting](https://github.com/zentixua/almighty/blob/main/docs/guide/hosting.md).

## Requirements and dependencies

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21**

**Minecraft 26.1.2 · NeoForge 26.1.2.130+ or Fabric Loader 0.20.1+ with Fabric API 0.155.3+ · Java 25**

**Minecraft 26.3 · NeoForge 26.3.0.118+ or Fabric Loader 0.20.1+ with Fabric API 0.167.0+ · Java 25**

Almighty runs on the server only; players join with a plain client. No other mods are required. Immersive
Aircraft (1.21.1, 26.1.2), Sable (1.21.1), Open Parties and Claims, FTB Chunks (1.21.1, 26.1.2), protection mods
that use the Common Protection API (Fabric) and LuckPerms are optional; their support loads only when they are
installed.

## FAQ

**NeoForge or Fabric?** Both, from 26.1.2. Minecraft 1.21.1 is NeoForge only.

**Does it include an AI?** No. You bring the AI app, with its own subscription or a local model. The mod is the
bridge between the app and your server.

**Client or server?** Server only. It starts on dedicated servers; for a single-player or LAN world, set
`bridge.integrated = true` in `config/almighty-common.toml`.

**How demanding is it?** Game master work is capped at 3 ms per tick and backs off while the server lags. Each bot
costs about as much as a player.

**How do I protect builds?** Set zones in `config/almighty/policy.toml`, or ask the game master to. With Open
Parties and Claims, FTB Chunks or a protection mod that uses the Common Protection API, claims are respected. Blocks the game master changed can be put back with undo.

**Can I use it in a modpack?** Yes, under the GPL-3.0 license. Mark it as server-side.

**I make mods. Can the game master learn mine?** It already reads your blocks, items, recipes and commands from the
running server. To explain more, ship a note at `data/<namespace>/almighty/notes/<name>.md`; see
[For mod and datapack authors](https://github.com/zentixua/almighty/blob/main/docs/guide/for-mod-authors.md).

## AI disclosure

- **AI-generated code and text:** Almighty's code, documentation and this page are written by an AI (Anthropic's
  Claude, through Claude Code) under the author's direction, review and testing. The author, ZentixUA, designs it,
  decides every behaviour and runs it on a server with friends.
- **AI functionality:** the mod exists so that an AI agent, chosen and run by the server owner, can act on the
  server. The mod itself contains no AI model and sends nothing to any AI service.
- **External system interactions:** the web endpoint described above; the sign-in, which reads the signing-in app's
  public HTTPS description; Mojang profile lookups for bot skins given as an account name; and code on the server
  machine through Groovy scripts, on by default and, unless you grant them, for the owner's agent only.
- **Images:** the icon and banners are pixel art drawn by small programs, with no image generation; the other
  pictures are rendered by the mod itself on a server.

## Links

[Guide](https://github.com/zentixua/almighty/blob/main/docs/guide/README.md) · [Permissions](https://github.com/zentixua/almighty/blob/main/docs/guide/permissions.md) · [FAQ](https://github.com/zentixua/almighty/blob/main/docs/guide/faq.md) · [Issues](https://github.com/zentixua/almighty/issues) · [Source](https://github.com/zentixua/almighty)

## License and credits

Made by ZentixUA. [GPL-3.0-only](https://github.com/zentixua/almighty/blob/main/LICENSE). Includes Groovy 4
(Apache License 2.0); the Fabric build also includes NightConfig (LGPL-3.0).

---

# Almighty: ИИ-ведущий

Всемогущий ИИ для вашего сервера Minecraft. Minecraft 1.21.1, 26.1.2 и 26.3, на NeoForge и Fabric.

Almighty подключает к серверу ИИ-агента, например Claude Code или Codex. Агент становится ведущим: видит мир,
говорит с игроками, строит, устраивает события и играет рядом с вами своими ботами. Что из этого ему можно, для кого
и где, решаете вы. Игрокам ничего ставить не нужно.

## Возможности

### Что умеет ведущий

- **Видит мир:** карта сверху, постройка с любой стороны или вид глазами любого игрока или бота.
- **Следит за игрой:** чат, входы, смерти, достижения и команды игроков приходят к нему сразу.
- **Говорит с игроками:** в чате, заголовком или над хотбаром. Игроки могут написать ему лично командой
  `/gm <текст>` или позвать по имени в чате (по умолчанию «Claude»). По умолчанию каждому вошедшему игроку сообщают, что
  ИИ читает общий чат и действует по обращённым к нему сообщениям.
- **Строит:** от пары блоков до двух миллионов, понемногу каждый тик, так что сервер продолжает работать.
- **Управляет игрой:** команды, предметы для игроков и правила, которые реагируют на события игры и других модов.
- **Изучает вашу сборку:** блоки, предметы, рецепты, таблицы добычи, достижения и команды каждого установленного мода,
  прямо с работающего сервера.
- **Играет рядом с вами:** боты-игроки ходят, копают, строят, открывают сундуки, ездят на лошадях, верблюдах,
  свиньях, страйдерах и в лодках и летают на самолётах Immersive Aircraft. Бот пользуется только тем, что может
  отправить клиент настоящего игрока.

### Что ему можно, решаете вы

- **Просьба — не приказ:** когда игрок о чём-то просит, ведущий действует с правами этого игрока, и не больше.
- **Пресеты:** новый сервер начинает с `friends`: с ведущим говорят игроки с подтверждённым аккаунтом Minecraft, а
  сам по себе он действует как участник. На публичном сервере ставьте `public`: говорить с ним может любой, а сам по
  себе он действует как гость. На `private` с ним говорят только владельцы, а сам по себе он действует со всеми
  своими правами.
- **Подтверждение:** то, что затрагивает других игроков и не отменяется, ждёт, пока попросивший нажмёт [Разрешить] в
  чате или, на 26.x, в диалоге; вопрос задаётся на языке игры игрока, английском или русском. Телепорт между игроком и
  другом спрашивает вместо этого друга. Просьба сверх прав игрока или вызов сверх собственных прав ведущего
  отклоняется, если только вы не впишете его вид в `ask_admin`; тогда его одним нажатием может одобрить админ, а
  владельцам в игре сообщают о каждом таком решении.
- **Роли:** владелец, админ, строитель, участник и гость. Роль на время даёт `/almighty role <игрок> builder 3h` или
  просьба («сделай Bob строителем до 23:00»), или решает LuckPerms через права `almighty.*`, на NeoForge также только
  для одной зоны (`almighty:zone=<зона>`).
- **Зоны, квоты и приваты:** не строить на спавне, отдать ведущему арену, ограничить блоки и предметы на игрока в час
  (LuckPerms может задать игроку его собственный лимит). С Open Parties and Claims, FTB Chunks или, на Fabric, любым
  модом защиты, который использует Common Protection API, он меняет блоки для игрока, стройкой или командами, только
  там, где тот сам может строить, а сам по себе — только вне приватов.
- **Ограничения:** командные блоки, спавнеры, барьеры и подобные блоки для игроков не строятся никогда, а предметы
  получает только тот, кто попросил.
- **Словами:** скажите ведущему «запрети строить на спавне», и он сам поменяет политику. Всё, что он предлагает
  сам и что расширяет права, ждёт вашего подтверждения.

### Безопасно для сервера

- **Откат:** каждый блок, который меняет ведущий, сохраняется на диск, даже если сервер упадёт посреди стройки. Любое
  изменение можно откатить, сундуки — вместе с содержимым; то, что игроки с тех пор построили или положили, остаётся
  как есть. Игрок, который попросил, получает в чате строку «Сделано по вашей просьбе: … [Отменить]»; одно нажатие на
  [Отменить] всё возвращает. Стройка и откат никогда не роняют лишних предметов.
- **Пауза:** `/almighty pause` останавливает одного агента или всех сразу; игрокам, которые пишут ведущему в это
  время, сообщают, что он на паузе. `/almighty log` показывает, что они сделали и в чём им отказали.
- **Боты помечены:** `[bot]` перед именем в списке игроков и в чате. Если вы не разрешили, бот не может взять имя
  или скин игрока, которого знает ваш сервер.
- **Без лагов:** работе ведущего — 3 мс на тик, а пока сервер лагает, четверть от этого. Несколько агентов делят
  это время поровну, а ограничения на вызовы в минуту и ждущие задания (ниже владельца ещё на время ботов и
  скриптов) не дают одному агенту вытеснить остальных. Команды, которые роняют или вешают сервер, запрещены всем
  агентам.

## Что это, простыми словами

Almighty — это управление вашим сервером со стороны ИИ-агента, которого запускаете вы, владелец. Мод открывает на
сервере небольшой веб-вход, по умолчанию только на `127.0.0.1`, то есть на самой машине сервера. Выбранное вами
приложение подключается к нему и входит по четырёхзначному коду, который вы вводите на сервере, или по токену, который
вы сделали для него командой `/almighty agent add`; иначе не войдёт ни одно приложение, а отозвать доступ можно в
любой момент командой `/almighty revoke`. `allow_from` в `config/almighty/policy.toml` ограничивает адреса, с которых
может подключаться приложение. Из интернета вход доступен, только если вы сами его туда вывели: через свой обратный
прокси или туннель с HTTPS или со своим сертификатом, но никогда не открытым портом. Обычный HTTP мод отклоняет везде,
кроме самой машины сервера, если только вы не разрешите его для частной сети контейнера:
`bridge.allow_plain_http_off_loopback = true`.

По умолчанию скрипты Groovy запускает только владелец, на любом пресете (плагин прав может разрешить их другим, а
строители могут запускать скрипты, которые владелец одобрил по имени). При включённых скриптах агент владельца может выполнить
любой код на машине сервера с правами пользователя, от которого запущен сервер: файлы, процессы, сеть. Если они не
нужны, задайте `scripts.enabled = false`. Сам сервер выходит в сеть, только чтобы прочитать публичное описание
входящего приложения и найти скин Minecraft, когда агент просит бота со скином игрока.

## Начало работы

1. Положите jar для своей версии Minecraft и загрузчика в папку `mods` сервера и запустите его.
2. Скажите своему ИИ-приложению: «connect to my Minecraft server at http://127.0.0.1:25650/mcp». Для Claude Code
   этой фразы достаточно. Команда `/almighty connect` покажет точную строку или ссылку для каждого приложения.
3. Приложение откроет страницу входа с четырёхзначным кодом и командой, которую нужно ввести. Введите её в игре как
   оператор уровня 4 с подтверждённым аккаунтом Minecraft: `/almighty approve <код>`. На новом сервере это сделает
   вас его владельцем и даст вашему приложению ваши права; других шагов нет. Следующие приложения получат меньше
   (без скриптов и только те команды, которые позволяют их права), если не одобрить их командой
   `/almighty approve <код> <имя> owner`.

После этого сообщения игроков через `/gm` приходят вашему приложению, больше ничего настраивать не нужно.

После подключения ведущий получает проверку настроек вашего сервера (пресет, владельцы, проверка личности,
приваты, HTTPS) и сообщает вам о каждой проблеме вместе с тем, как её исправить.

Подходят Claude Code, Codex (и в настольном приложении ChatGPT), Cursor, VS Code, opencode, Gemini CLI, Antigravity и
локальные модели. Сервер на VPS или хостинге — см.
[Хостинг](https://github.com/zentixua/almighty/blob/main/docs/guide/hosting.ru.md).

## Требования и зависимости

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21**

**Minecraft 26.1.2 · NeoForge 26.1.2.130+ или Fabric Loader 0.20.1+ с Fabric API 0.155.3+ · Java 25**

**Minecraft 26.3 · NeoForge 26.3.0.118+ или Fabric Loader 0.20.1+ с Fabric API 0.167.0+ · Java 25**

Almighty работает только на сервере; игроки заходят обычным клиентом. Другие моды не нужны. Immersive Aircraft
(1.21.1, 26.1.2), Sable (1.21.1), Open Parties and Claims, FTB Chunks (1.21.1, 26.1.2), моды защиты, которые
используют Common Protection API (Fabric), и LuckPerms необязательны; их поддержка загружается, только если они
установлены.

## Вопросы

**NeoForge или Fabric?** Оба, начиная с 26.1.2. Minecraft 1.21.1 — только NeoForge.

**Внутри есть ИИ?** Нет. ИИ-приложение приносите вы, со своей подпиской или локальной моделью. Мод — мост между
приложением и вашим сервером.

**Клиент или сервер?** Только сервер. Мод запускается на выделенных серверах; для одиночной игры или LAN задайте
`bridge.integrated = true` в `config/almighty-common.toml`.

**Насколько он тяжёлый?** Работа ведущего ограничена 3 мс на тик и уступает, пока сервер лагает. Каждый бот стоит
серверу примерно как игрок.

**Как защитить постройки?** Задайте зоны в `config/almighty/policy.toml` или попросите об этом ведущего. С Open
Parties and Claims, FTB Chunks или модом защиты, который использует Common Protection API, приваты соблюдаются. Блоки, которые поменял ведущий, можно вернуть откатом.

**Можно в сборку?** Да, по лицензии GPL-3.0. Отметьте его как серверный.

**Я делаю моды. Ведущий разберётся в моём?** Он уже читает ваши блоки, предметы, рецепты и команды с работающего
сервера. Чтобы объяснить больше, положите заметку в `data/<namespace>/almighty/notes/<name>.md`; см.
[Авторам модов и датапаков](https://github.com/zentixua/almighty/blob/main/docs/guide/for-mod-authors.ru.md).

## Раскрытие об ИИ

- **Код и текст, написанные ИИ:** код Almighty, документацию и эту страницу пишет ИИ (Claude от Anthropic, через
  Claude Code) под руководством, проверкой и тестами автора. Автор, ZentixUA, придумывает мод, решает, как он себя
  ведёт, и запускает его на сервере с друзьями.
- **ИИ-функции:** мод существует, чтобы ИИ-агент, которого выбирает и запускает владелец сервера, мог действовать на
  сервере. В самом моде нет модели ИИ, и он ничего не отправляет ни в какой ИИ-сервис.
- **Внешние системы:** веб-вход, описанный выше; вход приложения, при котором читается его публичное описание по
  HTTPS; запросы профилей Mojang для скинов ботов, заданных именем аккаунта; и код на машине сервера через скрипты
  Groovy, по умолчанию включённые и, если вы не разрешили иначе, доступные только агенту владельца.
- **Картинки:** иконка и баннеры — пиксель-арт, нарисованный небольшими программами, без генерации изображений;
  остальные картинки нарисовал сам мод на сервере.

## Ссылки

[Руководство](https://github.com/zentixua/almighty/blob/main/docs/guide/README.ru.md) · [Права](https://github.com/zentixua/almighty/blob/main/docs/guide/permissions.ru.md) · [Вопросы и ответы](https://github.com/zentixua/almighty/blob/main/docs/guide/faq.ru.md) · [Ошибки](https://github.com/zentixua/almighty/issues) · [Исходный код](https://github.com/zentixua/almighty)

## Лицензия и благодарности

Автор — ZentixUA. [GPL-3.0-only](https://github.com/zentixua/almighty/blob/main/LICENSE). Включает Groovy 4
(Apache License 2.0); сборка для Fabric также включает NightConfig (LGPL-3.0).
