# VS Code (режим агента Copilot)

[English](vscode.md) · Русский

**Попросите:** «Подключись к моему серверу Minecraft (Almighty) по адресу http://127.0.0.1:25650/mcp». Агент
выполнит `code --add-mcp` или поправит `mcp.json`.

**Или сами:** нажмите ссылку VS Code из `/almighty connect` или выполните:

```sh
code --add-mcp '{"name":"almighty","type":"http","url":"http://127.0.0.1:25650/mcp"}'
```

**Вход:** VS Code спросит, можно ли выполнить вход на сервер; разрешите, затем наберите код со страницы:
`/almighty approve <код>`.
