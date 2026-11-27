# Codex и приложение ChatGPT для компьютера

[English](codex.md) · Русский

Одинаково для Codex CLI, расширения Codex для IDE и **приложения ChatGPT для компьютера в режиме Codex** (у них общий
`~/.codex/config.toml`). Так Almighty работает с планом ChatGPT: Codex входит в любой план, включая Free.

**Попросите:** «Подключись к моему серверу Minecraft (Almighty) по адресу http://127.0.0.1:25650/mcp». Codex
поправит свои настройки (они вне рабочей папки, поэтому он сначала спросит). Затем начните новую сессию.

**Или сами:**

```sh
codex mcp add almighty --url http://127.0.0.1:25650/mcp
codex mcp login almighty
```

**Вход:** `codex mcp login almighty` открывает в браузере страницу с кодом; наберите `/almighty approve <код>` на
сервере. После этого `codex mcp list` показывает `Auth: OAuth`. Codex подключается как агент `codex`.

**Плагин (по желанию):**

```sh
codex plugin marketplace add zentixua/almighty
codex plugin add almighty@almighty
```

Без браузера (`codex exec`): сохранённый вход работает; для CI возьмите токен агента с `bearer_token_env_var`,
смотрите [headless.ru.md](headless.ru.md).
