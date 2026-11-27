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
- **Talk to players:** in chat, as a title or above the hotbar. Any player can write to it privately with
  `/gm <text>`, or address it by name in chat ("Claude" by default).
- **Build:** from a few blocks up to two million, placed a little each tick so the server keeps running.
- **Run the game:** commands, items for players, and rules that react to events of the game and of other mods.
- **Play alongside you:** bot players that walk, mine, build, open chests, ride horses, camels, pigs, striders and
  boats, and fly Immersive Aircraft planes. A bot uses only what a real player's client can send.

![What a bot sees: the eye view of an airport, drawn by the server, with the crosshair in the middle](https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/eye.png)

![The map view: an airport from above, drawn like a vanilla map](https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/map.png)

### You decide what it may do

- **A request is not an order:** when a player asks for something, the game master acts with that player's rights,
  not its own.
- **Confirmation:** a command that would affect other players waits until the player who asked presses [Allow] in
  chat. A request beyond their rights can go to an admin instead.
- **Presets:** a new server starts as `friends`: anyone may talk to the game master, and on its own it acts as a
  member would. `private` and `public` are one line away.
- **Roles:** owner, admin, builder, member and guest. Give one for a while with `/almighty role <player> builder 3h`,
  or let LuckPerms decide through the `almighty.*` permissions.
- **Zones, quotas and claims:** keep spawn free of building, give the game master an arena, limit blocks and items
  per player per hour. With Open Parties and Claims or FTB Chunks it builds for a player only where that player may
  build.
- **Ask in words:** tell the game master "deny building at spawn" and it changes the policy for you. Anything it
  proposes on its own that grants more waits for your confirmation.

### Safe for your server

- **Undo:** every block the game master changes is saved to disk, even if the server crashes mid-build. Any change
  can be undone; blocks that players changed since stay as they are. A player who asked for something gets a
  "Done for you … [Undo]" line in chat.
- **Pause:** `/almighty pause` stops one agent or all of them at once. `/almighty log` shows what they did and what
  was refused.
- **Bots are marked:** `[bot]` before their names in the tab list and chat. Unless you allow it, a bot cannot take the name
  or skin of a player your server knows.
- **No lag:** game master work gets 3 ms per tick, and a quarter of that while the server lags. Commands known to
  crash or freeze a server are refused for every agent.

## Getting started

1. Put the jar for your Minecraft version and loader into the server's `mods` folder and start the server.
2. Tell your AI app: "connect to my Minecraft server at http://127.0.0.1:25650/mcp". `/almighty connect` in the
   server console shows the exact line or link for each app.
3. The app opens a sign-in page with a four-digit code. Type `/almighty approve <code> <name> owner` in the console
   for your own app. Without `owner` it gets less: no scripts, and only the commands its rights allow.

Claude Code, Codex (also in the ChatGPT desktop app), Cursor, VS Code, opencode, Gemini CLI, Antigravity and local
models all work. For a server on a VPS or a hosting panel, see
[Hosting](https://github.com/zentixua/almighty/blob/main/docs/guide/hosting.md).

## Requirements and dependencies

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21**

**Minecraft 26.1.2 · NeoForge 26.1.2.130+ or Fabric Loader 0.20.1+ with Fabric API · Java 25**

**Minecraft 26.3 · NeoForge 26.3.0.118+ or Fabric Loader 0.20.1+ with Fabric API · Java 25**

Almighty runs on the server only; players join with a plain client. No other mods are required. Immersive
Aircraft (1.21.1, 26.1.2), Sable (1.21.1), Open Parties and Claims, FTB Chunks (1.21.1, 26.1.2) and LuckPerms are
optional; their support loads only when they are installed.

## FAQ

**NeoForge or Fabric?** Both, from 26.1.2. Minecraft 1.21.1 is NeoForge only.

**Does it include an AI?** No. You bring the AI app, with its own subscription or a local model. The mod is the
bridge between the app and your server.

**Client or server?** Server only. It starts on dedicated servers; for a single-player or LAN world, set
`bridge.integrated = true` in `config/almighty-common.toml`.

**Is it safe?** An app you approve as `owner` has an operator's power, and with scripts on it can run code on the
server machine. The bridge listens only on `127.0.0.1` by default, and every app must be approved with a code typed
on the server. Read [Security](https://github.com/zentixua/almighty/blob/main/docs/guide/security.md) before you let
anyone else reach it.

**How demanding is it?** Game master work is capped at 3 ms per tick and backs off while the server lags. Each bot
costs about as much as a player.

**How do I protect builds?** Set zones in `config/almighty/policy.toml`, or ask the game master to. With Open
Parties and Claims or FTB Chunks, claims are respected. Blocks the game master changed can be put back with undo.

**Can I use it in a modpack?** Yes, under the GPL-3.0 license. Mark it as server-side.

**I make mods. Can the game master learn mine?** It already reads your blocks, items, recipes and commands from the
running server. To explain more, ship a note at `data/<namespace>/almighty/notes/<name>.md`; see
[For mod and datapack authors](https://github.com/zentixua/almighty/blob/main/docs/guide/for-mod-authors.md).

## Made with AI

Almighty is designed by ZentixUA and written with Claude Code: the code, the guides and this page are written by an
AI agent and reviewed by the author. The pixel art is drawn by code. The mod exists so AI agents can run Minecraft
servers, and it runs on the author's own server, where an AI game master plays with friends.

## Links

[Guide](https://github.com/zentixua/almighty/blob/main/docs/guide/README.md) · [Issues](https://github.com/zentixua/almighty/issues) · [Source](https://github.com/zentixua/almighty)

## License and credits

Made by ZentixUA. [GPL-3.0-only](https://github.com/zentixua/almighty/blob/main/LICENSE). Includes Groovy 4
(Apache License 2.0); the Fabric build also includes NightConfig (LGPL-3.0).

---

# Almighty: ИИ-ведущий

ИИ-ведущий для вашего сервера Minecraft. Minecraft 1.21.1, 26.1.2 и 26.3, на NeoForge и Fabric.

Almighty подключает к серверу ИИ-агента, например Claude Code или Codex. Агент становится ведущим: видит мир,
говорит с игроками, строит, устраивает события и играет рядом с вами своими ботами. Что из этого ему можно, для кого
и где, решаете вы. Игрокам ничего ставить не нужно.

## Возможности

### Что умеет ведущий

- **Видит мир:** карта сверху, постройка с любой стороны или вид глазами любого игрока или бота.
- **Следит за игрой:** чат, входы, смерти, достижения и команды игроков приходят к нему сразу.
- **Говорит с игроками:** в чате, заголовком или над хотбаром. Любой игрок может написать ему лично командой
  `/gm <текст>` или позвать по имени в чате (по умолчанию «Claude»).
- **Строит:** от пары блоков до двух миллионов, понемногу каждый тик, так что сервер продолжает работать.
- **Управляет игрой:** команды, предметы для игроков и правила, которые реагируют на события игры и других модов.
- **Играет рядом с вами:** боты-игроки ходят, копают, строят, открывают сундуки, ездят на лошадях, верблюдах,
  свиньях, страйдерах и в лодках и летают на самолётах Immersive Aircraft. Бот пользуется только тем, что может
  отправить клиент настоящего игрока.

### Что ему можно, решаете вы

- **Просьба — не приказ:** когда игрок о чём-то просит, ведущий действует с правами этого игрока, а не со своими.
- **Подтверждение:** команда, которая затронет других игроков, ждёт, пока попросивший нажмёт [Allow] в чате.
  Просьбу сверх прав игрока может решить админ.
- **Пресеты:** новый сервер начинает с `friends`: говорить с ведущим может любой, а сам по себе он действует как
  участник. `private` и `public` — одна строка в настройках.
- **Роли:** владелец, админ, строитель, участник и гость. Роль на время даёт `/almighty role <игрок> builder 3h`,
  или решает LuckPerms через права `almighty.*`.
- **Зоны, квоты и приваты:** не строить на спавне, отдать ведущему арену, ограничить блоки и предметы на игрока в
  час. С Open Parties and Claims или FTB Chunks он строит для игрока только там, где тот сам может строить.
- **Словами:** скажите ведущему «запрети строить на спавне», и он сам поменяет политику. Всё, что он предлагает
  сам и что расширяет права, ждёт вашего подтверждения.

### Безопасно для сервера

- **Откат:** каждый блок, который меняет ведущий, сохраняется на диск, даже если сервер упадёт посреди стройки.
  Любое изменение можно откатить; блоки, которые с тех пор поменяли игроки, остаются как есть. Игрок, который
  попросил, получает в чате строку «Done for you … [Undo]».
- **Пауза:** `/almighty pause` останавливает одного агента или всех сразу. `/almighty log` показывает, что они
  сделали и в чём им отказали.
- **Боты помечены:** `[bot]` перед именем в списке игроков и в чате. Если вы не разрешили, бот не может взять имя
  или скин игрока, которого знает ваш сервер.
- **Без лагов:** работе ведущего — 3 мс на тик, а пока сервер лагает, четверть от этого. Команды, которые роняют
  или вешают сервер, запрещены всем агентам.

## Начало работы

1. Положите jar для своей версии Minecraft и загрузчика в папку `mods` сервера и запустите его.
2. Скажите своему ИИ-приложению: «connect to my Minecraft server at http://127.0.0.1:25650/mcp». Команда
   `/almighty connect` в консоли сервера покажет точную строку или ссылку для каждого приложения.
3. Приложение откроет страницу входа с четырёхзначным кодом. Для своего приложения введите в консоли
   `/almighty approve <код> <имя> owner`. Без `owner` оно получит меньше: без скриптов и только те команды, которые
   позволяют его права.

Подходят Claude Code, Codex (и в настольном приложении ChatGPT), Cursor, VS Code, opencode, Gemini CLI, Antigravity и
локальные модели. Сервер на VPS или хостинге — см.
[Хостинг](https://github.com/zentixua/almighty/blob/main/docs/guide/hosting.ru.md).

## Требования и зависимости

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21**

**Minecraft 26.1.2 · NeoForge 26.1.2.130+ или Fabric Loader 0.20.1+ с Fabric API · Java 25**

**Minecraft 26.3 · NeoForge 26.3.0.118+ или Fabric Loader 0.20.1+ с Fabric API · Java 25**

Almighty работает только на сервере; игроки заходят обычным клиентом. Другие моды не нужны. Immersive Aircraft
(1.21.1, 26.1.2), Sable (1.21.1), Open Parties and Claims, FTB Chunks (1.21.1, 26.1.2) и LuckPerms необязательны; их
поддержка загружается, только если они установлены.

## Вопросы

**NeoForge или Fabric?** Оба, начиная с 26.1.2. Minecraft 1.21.1 — только NeoForge.

**Внутри есть ИИ?** Нет. ИИ-приложение приносите вы, со своей подпиской или локальной моделью. Мод — мост между
приложением и вашим сервером.

**Клиент или сервер?** Только сервер. Мод запускается на выделенных серверах; для одиночной игры или LAN задайте
`bridge.integrated = true` в `config/almighty-common.toml`.

**Это безопасно?** Приложение, одобренное как `owner`, обладает властью оператора, а при включённых скриптах может
выполнять код на машине сервера. По умолчанию мост слушает только `127.0.0.1`, и каждое приложение нужно одобрить
кодом, введённым на сервере. Прочитайте
[Безопасность](https://github.com/zentixua/almighty/blob/main/docs/guide/security.ru.md), прежде чем пускать к нему
кого-то ещё.

**Насколько он тяжёлый?** Работа ведущего ограничена 3 мс на тик и уступает, пока сервер лагает. Каждый бот стоит
серверу примерно как игрок.

**Как защитить постройки?** Задайте зоны в `config/almighty/policy.toml` или попросите об этом ведущего. С Open
Parties and Claims или FTB Chunks приваты соблюдаются. Блоки, которые поменял ведущий, можно вернуть откатом.

**Можно в сборку?** Да, по лицензии GPL-3.0. Отметьте его как серверный.

**Я делаю моды. Ведущий разберётся в моём?** Он уже читает ваши блоки, предметы, рецепты и команды с работающего
сервера. Чтобы объяснить больше, положите заметку в `data/<namespace>/almighty/notes/<name>.md`; см.
[Авторам модов и датапаков](https://github.com/zentixua/almighty/blob/main/docs/guide/for-mod-authors.ru.md).

## Сделано с ИИ

Almighty придумал ZentixUA, а написан он с Claude Code: код, руководства и эту страницу пишет ИИ-агент, автор их
проверяет. Пиксель-арт нарисован кодом. Мод существует, чтобы ИИ-агенты могли вести серверы Minecraft, и работает на
сервере автора, где ИИ-ведущий играет с друзьями.

## Ссылки

[Руководство](https://github.com/zentixua/almighty/blob/main/docs/guide/README.ru.md) · [Ошибки](https://github.com/zentixua/almighty/issues) · [Исходный код](https://github.com/zentixua/almighty)

## Лицензия и благодарности

Автор — ZentixUA. [GPL-3.0-only](https://github.com/zentixua/almighty/blob/main/LICENSE). Включает Groovy 4
(Apache License 2.0); сборка для Fabric также включает NightConfig (LGPL-3.0).
