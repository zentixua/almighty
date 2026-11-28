# Claude Code

[English](claude-code.md) · Русский

Работает в терминале и во вкладке Code приложения Claude для компьютера.

**Попросите:** «Подключись к моему серверу Minecraft (Almighty) по адресу http://127.0.0.1:25650/mcp». Claude Code
сам выполнит `claude mcp add` (вы один раз разрешите) и скажет, что нужно войти. Если он сначала обратится по
адресу напрямую, сервер ответит, что ключ или токен не нужен, и назовёт команду `claude mcp add`.

**Или сами:**

```sh
claude mcp add --transport http --scope user almighty http://127.0.0.1:25650/mcp
```

**Вход:** в сессии `/mcp`, выберите `almighty`, «Authenticate» (или `claude mcp login almighty` в терминале). Браузер
покажет код; наберите на сервере `/almighty approve <код> claude-code owner`, если это ваше собственное приложение
([кто может подтвердить](../getting-started.ru.md#3-подтвердите-вход-кодом)). Claude Code сам хранит и обновляет
вход. Он подключается как агент `claude-code` (или под именем, которое вы дали).

Не меняйте имя `almighty`: канал чата и хуки (по желанию) узнают инструменты по имени `mcp__almighty__*`.

**Плагин (по желанию):** навыки ведущего и запись сервера за один шаг:

```sh
claude plugin marketplace add zentixua/almighty
claude plugin install almighty@almighty
```

Без браузера (`claude -p`, Agent SDK): сохранённый вход работает и там; для CI возьмите токен агента, смотрите
[headless.ru.md](headless.ru.md). Чтобы отвечать игрокам, как только они пишут, смотрите
[Режим ведущего](../game-master.ru.md).
