# Local models

English · [Русский](local-models.ru.md)

Apps that run a model on your own computer connect like any other app. Two things help small models:

- Add `?profile=compact` to the address: 6 tools with one-line descriptions instead of 14 long ones (the details are
  in the notes, which the agent reads when it needs them).
- Add `images=false` if the model cannot see pictures (LM Studio does not pass MCP images to vision models; GLM
  text models have no image input). Maps and views then come as text only.

Address for these apps: `http://127.0.0.1:25650/mcp?profile=compact&images=false`.

| App | How |
|---|---|
| LM Studio | the LM Studio link from `/almighty connect` (it installs the plain address: add the query in LM Studio), or Program → Install → Edit `mcp.json`: `{"mcpServers": {"almighty": {"url": "<address>"}}}`; sign in when it asks |
| Goose | the Goose link from `/almighty connect`, or `goose configure` → Add Extension → Remote (streamable HTTP) |
| Cline | ask it, or MCP Servers → Remote Servers → add the address |
| Continue | `~/.continue/config.yaml`: `mcpServers: [{name: almighty, type: streamable-http, url: <address>}]` |
| Ollama | through one of the apps above (Ollama itself has no MCP client) |
| GLM (z.ai) in Claude Code, opencode or Cline | as that app; use `images=false` |

Sign-in is the same: the app opens the page, you type `/almighty approve <code>` on the server. Apps that cannot
sign in can use an agent token instead ([headless.md](headless.md)).
