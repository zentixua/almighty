# Скрипты, CI и запуск без браузера

[English](headless.md) · Русский

Запуски без браузера (`claude -p`, Agent SDK, `codex exec`, CI, ваши собственные скрипты) пользуются токеном агента:
это секрет, который сервер создаёт для одного агента и хранит в файле, который может прочитать только владелец
сервера.

1. В консоли сервера: `/almighty agent add ci` → «Agent ci added; its token is in
   `config/almighty/agents/ci.token`». Сам токен никогда не выводится.
2. Положите токен туда, откуда его прочитает запуск, в переменную среды (никогда не в файл настроек и не в историю
   оболочки): `export ALMIGHTY_TOKEN="$(cat /path/to/server/config/almighty/agents/ci.token)"`.
3. Направьте клиента на сервер с заголовком из этой переменной:

Claude Code (файл `--mcp-config` или `.mcp.json`; Claude Code подставляет `${VAR}`):

```json
{"mcpServers": {"almighty": {"type": "http", "url": "http://127.0.0.1:25650/mcp", "headers": {"Authorization": "Bearer ${ALMIGHTY_TOKEN}"}}}}
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.almighty]
url = "http://127.0.0.1:25650/mcp"
bearer_token_env_var = "ALMIGHTY_TOKEN"
```

Проверено: `claude -p` только с этой записью прочитал состояние сервера, выполнил команды и получил картинку карты.
Если на этом же компьютере уже был вход по коду, токен не нужен: сохранённый вход работает и без браузера.

`/almighty revoke ci` меняет токен (старый сразу перестаёт работать); `/almighty agent remove ci` отключает агента.

Командная строка `uv run mcp/almighty.py call status` (и `follow` для ленты событий) пользуется тем же токеном из
`ALMIGHTY_TOKEN` или `ALMIGHTY_TOKEN_FILE`. `uv run mcp/almighty.py mcp` — посредник stdio для приложений, которые
не умеют HTTP.
