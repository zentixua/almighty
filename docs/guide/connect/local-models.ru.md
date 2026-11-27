# Локальные модели

[English](local-models.md) · Русский

Приложения, которые запускают модель на вашем компьютере, подключаются так же, как любые другие. Маленьким моделям
помогают две вещи:

- Добавьте к адресу `?profile=compact`: 6 инструментов с описанием в одну строку вместо 14 длинных (подробности —
  в памятках, агент читает их, когда нужно).
- Добавьте `images=false`, если модель не видит картинок (LM Studio не передаёт картинки MCP моделям со зрением, у
  текстовых моделей GLM нет ввода картинок). Тогда карты и виды приходят только текстом.

Адрес для этих приложений: `http://127.0.0.1:25650/mcp?profile=compact&images=false`.

| Приложение | Как |
|---|---|
| LM Studio | ссылка LM Studio из `/almighty connect` (она ставит адрес без параметров: допишите их в LM Studio) или Program → Install → Edit `mcp.json`: `{"mcpServers": {"almighty": {"url": "<адрес>"}}}`; войдите, когда приложение попросит |
| Goose | ссылка Goose из `/almighty connect` или `goose configure` → Add Extension → Remote (streamable HTTP) |
| Cline | попросите его или MCP Servers → Remote Servers → добавьте адрес |
| Continue | `~/.continue/config.yaml`: `mcpServers: [{name: almighty, type: streamable-http, url: <адрес>}]` |
| Ollama | через одно из приложений выше (у самой Ollama нет клиента MCP) |
| GLM (z.ai) в Claude Code, opencode или Cline | как в этом приложении; используйте `images=false` |

Вход такой же: приложение открывает страницу, вы набираете `/almighty approve <код>` на сервере. Приложения, которые
не умеют входить, могут взять токен агента ([headless.ru.md](headless.ru.md)).
