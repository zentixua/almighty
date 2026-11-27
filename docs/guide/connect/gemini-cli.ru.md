# Gemini CLI

[English](gemini-cli.md) · Русский

**Попросите:** «Подключись к моему серверу Minecraft (Almighty) по адресу http://127.0.0.1:25650/mcp». Агент
выполнит `gemini mcp add`.

**Или сами:**

```sh
gemini mcp add -t http -s user almighty http://127.0.0.1:25650/mcp
```

**Вход:** в сессии `/mcp auth almighty`; браузер покажет страницу с кодом; наберите `/almighty approve <код>` на
сервере. Сервер поддерживает обнаружение и регистрацию, которыми пользуется Gemini CLI.

Проверено с Gemini CLI 0.71.1: `gemini mcp add` записывает сервер в настройки; для входа нужна интерактивная сессия с
аккаунтом Google, её здесь не запускали.
