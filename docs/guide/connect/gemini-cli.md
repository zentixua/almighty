# Gemini CLI

English · [Русский](gemini-cli.ru.md)

**Ask:** "Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp". The agent runs `gemini mcp add`.

**Or yourself:**

```sh
gemini mcp add -t http -s user almighty http://127.0.0.1:25650/mcp
```

**Sign in:** in a session, `/mcp auth almighty`; the browser shows the page with a code; type
`/almighty approve <code>` on the server. The server supports the discovery and registration Gemini CLI uses.

Checked with Gemini CLI 0.71.1: `gemini mcp add` writes the entry; the sign-in needs an interactive session with a
Google account, which was not run here.
