# claude.ai, чат Claude Desktop и ChatGPT в браузере (сложнее)

[English](cloud.md) · Русский

Эти приложения работают не на вашем компьютере: они подключаются с серверов Anthropic или OpenAI. Поэтому им нужен
мост вашего сервера на **публичном адресе HTTPS**, а дома или на хостинге такого адреса по умолчанию нет. Если вам
просто нужен агент, возьмите приложение на компьютере: Claude Code или приложение ChatGPT для компьютера в режиме
Codex ([codex.ru.md](codex.ru.md)).

1. Дайте мосту публичный адрес HTTPS, не открывая сам мост: обратный прокси с сертификатом для вашего домена на
   машине сервера (например, Caddy: `mc.example.org { reverse_proxy 127.0.0.1:25650 }`) или туннель, например
   Cloudflare Tunnel. claude.ai нужен порт 443. Смотрите [Хостинг](../hosting.ru.md).
2. Сообщите моду его публичный адрес в `config/almighty-common.toml`: `bridge.public_url = "https://mc.example.org"`,
   и перезапустите сервер. После этого страницы входа и `/almighty connect` используют его.
3. Добавьте сервер:
   - **claude.ai и чат Claude Desktop:** ссылка claude.ai из `/almighty connect` (она заполняет Settings →
     Connectors → Add custom connector), затем «Connect». В планах Team и Enterprise коннекторы добавляет владелец.
   - **ChatGPT в браузере:** Settings → Apps → Advanced → Developer mode, затем «Create» с адресом
     `https://mc.example.org/mcp`. По документации OpenAI действия с записью есть только в планах Business,
     Enterprise и Edu; в Pro доступно только чтение. Secure MCP Tunnel от OpenAI заменяет публичный адрес, но только
     для ChatGPT.
4. Вход как везде: страница показывает код, вы набираете `/almighty approve <код>` на сервере.

Здесь это от начала до конца не проверено: нужен публичный адрес. Публичный адрес доступен любому в интернете,
поэтому сначала прочитайте [Безопасность](../security.ru.md).
