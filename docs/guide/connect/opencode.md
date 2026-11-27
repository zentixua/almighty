# opencode

English · [Русский](opencode.ru.md)

**Ask:** "Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp". The agent runs
`opencode mcp add` or edits `opencode.json`.

**Or yourself:** add to `opencode.json` (project) or `~/.config/opencode/opencode.json`:

```json
{ "$schema": "https://opencode.ai/config.json", "mcp": { "almighty": { "type": "remote", "url": "http://127.0.0.1:25650/mcp" } } }
```

**Sign in:** `opencode mcp auth almighty` opens the page with a code; type `/almighty approve <code>` on the server.
opencode registers itself and connects as agent `opencode`. Checked with opencode 1.19.6: sign-in, then
`initialize` and `tools/list` with the new token.

opencode also reads skills from `.claude/skills` and `.agents/skills`: copy `plugin/almighty/skills/*` there for the
game master skills.
