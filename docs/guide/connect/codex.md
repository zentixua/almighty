# Codex and the ChatGPT desktop app

English · [Русский](codex.ru.md)

The same for the Codex CLI, the Codex IDE extension and **the ChatGPT desktop app in Codex mode** (they share
`~/.codex/config.toml`). This is the way to use Almighty with a ChatGPT plan: Codex is included in every plan, Free
included.

**Ask:** "Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp". Codex edits its config (outside
the workspace, so it asks first). Start a new session afterwards.

**Or yourself:**

```sh
codex mcp add almighty --url http://127.0.0.1:25650/mcp
codex mcp login almighty
```

**Sign in:** `codex mcp login almighty` opens the browser page with a code; type
`/almighty approve <code>` on the server, as the page says ([who may approve, and what the app
gets](../getting-started.md#3-approve-the-sign-in-with-its-code)). `codex mcp list` then shows `Auth: OAuth`. It
connects as agent `codex` (or the name you gave).

**Plugin (optional):**

```sh
codex plugin marketplace add zentixua/almighty
codex plugin add almighty@almighty
```

Headless (`codex exec`): the stored login works; for CI use an agent token with `bearer_token_env_var`, see
[headless.md](headless.md).
