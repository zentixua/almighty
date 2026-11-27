# claude.ai, Claude Desktop chat and ChatGPT on the web (advanced)

English · [Русский](cloud.ru.md)

These apps do not run on your computer: they connect from Anthropic's or OpenAI's servers. So they need your server's
bridge on a **public HTTPS address**, which a home or hosting setup does not have by default. If you only want an
agent, use a local app instead: Claude Code, or the ChatGPT desktop app in Codex mode ([codex.md](codex.md)).

1. Give the bridge a public HTTPS address without opening the bridge itself: a reverse proxy with a certificate
   for your domain on the server machine (for example Caddy: `mc.example.org { reverse_proxy 127.0.0.1:25650 }`), or
   a tunnel such as Cloudflare Tunnel. claude.ai needs port 443. See [Hosting](../hosting.md).
2. Tell the mod its public address in `config/almighty-common.toml`: `bridge.public_url = "https://mc.example.org"`,
   and restart. Sign-in pages and `/almighty connect` then use it.
3. Add the server:
   - **claude.ai / Claude Desktop chat:** the claude.ai link from `/almighty connect` (it fills in Settings →
     Connectors → Add custom connector), then Connect. On Team and Enterprise plans an owner adds connectors.
   - **ChatGPT on the web:** Settings → Apps → Advanced → Developer mode, then Create, with the address
     `https://mc.example.org/mcp`. OpenAI documents write actions only for Business, Enterprise and Edu plans; Pro is
     read-only. OpenAI's Secure MCP Tunnel is an alternative to a public address for ChatGPT only.
4. Sign in as everywhere: the page shows a code, you type `/almighty approve <code>` on the server.

Not checked end to end here: it needs a public address. Anyone on the internet can reach a public address, so read
[Security](../security.md) first.
