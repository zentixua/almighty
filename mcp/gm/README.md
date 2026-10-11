# Ведущий на Claude Code

Ведущий сервера — одна сессия Claude Code со всеми её инструментами: рычаги мира — MCP-адаптер `../almighty.py`,
остальное — то, что здесь. Своё у сервера (CLAUDE.md, навыки, рецепты, настройки сессии) — в его рабочей папке.

| Что | Файл | Как подключается |
|---|---|---|
| Канал чата | `almighty-chat/` — плагин Claude Code в маркетплейсе `almighty` (этот каталог) | `--channels plugin:almighty-chat@almighty` |
| Хук от падений | `crash_guard.py` | PreToolUse на `mcp__almighty__.*` в `.claude/settings.json` сессии |
| Рецепты | `recipes.py` — формат и запуск | командой из навыка сервера |

## Канал чата
Канал ([channels](https://code.claude.com/docs/en/channels-reference)) берёт ленту событий моста и будит сессию,
когда ведущему пишут: личное `/gm`, имя ведущего в общем чате (с растянутыми буквами и опечаткой в длинных именах),
дальше — разговор без имени, пока он идёт. Запрос разрешения сессии приходит владельцу в игру, его `да <номер>` —
ответ. Хуки плагина (`hooks/hooks.json`, тип http) сообщают каналу ответ ведущего в чат и конец хода: над хотбаром
игрока — «ведущий думает…», пока ход идёт. Настройки — переменные среды сессии: `ALMIGHTY_URL`,
`ALMIGHTY_TOKEN_FILE`, `GM_OWNERS`, `GM_NAMES`, `GM_TYPO_NAMES`, `GM_BOT_PLAYERS` (боты, которых слушать как игроков,
— для проверок).

## Хук от падений
`crash_guard.py` ([hooks](https://code.claude.com/docs/en/hooks)) отклоняет с причиной только то, что уже роняло
сервер (список и откуда он — в файле), и спрашивает владельца на op, deop, whitelist, ban и kick. Субагенты игрокам не
пишут: их `say`, команды чата и реплики ботов хук не пускает. Сломался сам хук — отказ, а не пропуск.

## Рецепты
Рецепт — проверенный в игре способ: шаги — вызовы моста, проверка — Groovy в мире. Формат — в `recipes.py`; шаги идут
через тот же хук, слов игрокам и дел владельца в рецептах нет. Мост — как у адаптера (`ALMIGHTY_URL`,
`ALMIGHTY_TOKEN_FILE`): тестовый сервер — его адрес и токен.
```sh
uv run --script mcp/gm/recipes.py list ~/gm/recipes
uv run --script mcp/gm/recipes.py run ~/gm/recipes ship_stop '{"ship": "Grand"}'
```

## Поставить
```sh
(cd mcp/gm/almighty-chat && npm ci)
claude plugin marketplace add /путь/к/almighty/mcp/gm && claude plugin install almighty-chat@almighty
```
Канал не из официального списка: его разрешает управляемая политика (`allowedChannelPlugins` с маркетплейсом
`almighty` и плагином `almighty-chat`) или флаг `--dangerously-load-development-channels`. Хук — в
`.claude/settings.json` рабочей папки:
```json
{"hooks": {"PreToolUse": [{"matcher": "mcp__almighty__.*", "hooks": [
  {"type": "command", "command": "python3 /путь/к/almighty/mcp/gm/crash_guard.py", "timeout": 30}]}]}}
```
Запуск: `claude --mcp-config mcp.json --channels plugin:almighty-chat@almighty` (в `mcp.json` — адаптер
`almighty.py mcp`).

## Проверки
`python3 -m unittest discover -s mcp/gm/tests` — хук и рецепты (в CI на каждый PR); `node --test` в `almighty-chat/` —
кому в чате адресовано сообщение и когда ход ведущего кончился.
