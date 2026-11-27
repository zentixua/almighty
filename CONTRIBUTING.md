# Contributing to Almighty

Read the [guide](docs/guide/README.md) and search the existing
[issues](https://github.com/zentixua/almighty/issues) before reporting a problem. Use the bug report form, include
the exact versions of Almighty, Minecraft, the loader and Java, the agent app you connected, the steps to reproduce,
and the complete server log. Remove tokens, private addresses and other personal data first: never post the contents
of `config/almighty/token`, `config/almighty/agents/` or `config/almighty/oauth/`.

## Building

Gradle runs on Java 25; each Minecraft version compiles with its own Java toolchain, which Gradle downloads.

```sh
git clone https://github.com/zentixua/almighty.git
cd almighty
./gradlew build                  # every version and loader: compile and unit tests
./gradlew :1.21.1-neoforge:build # one version and loader (versions/<version>-<loader>)
./gradlew runGameTestServer      # GameTests on a headless server
```

The jars are in `versions/<version>-<loader>/build/libs/`. The `mcp/gm` kit is tested with
`python3 -m unittest discover -s mcp/gm/tests` and `node --test` in `mcp/gm/almighty-chat`.

One source tree serves every version: code that differs between versions sits in Stonecutter conditions, and the
committed source is the 1.21.1 NeoForge one. [docs/developers.md](docs/developers.md) gives an overview and the
bridge API; [AGENTS.md](AGENTS.md) explains the layout, the internals, every bridge
method and the known pitfalls.

## Changes

- Keep a pull request to one change, and describe what an owner, a player or an agent will see.
- Code, comments, logs, commits and developer documentation are in English. Text for players and owners (the README,
  `docs/guide`, release notes, `en_us`/`ru_ru`) is in English and Russian, updated in the same pull request, with no
  mixing inside a file.
- A behaviour fix comes with a unit test or GameTest; a promise in a tool description or the guide is held by one.
- Public text states what is true now: no change history outside `docs/releases/`.
- The plugin's `skills/` and `docs/guide/tools.md` are generated: edit the notes in
  `src/main/resources/data/almighty/almighty/notes/` and `assets/almighty/mcp/tools.json`, then run
  `python3 scripts/export_skills.py` and `python3 scripts/gen_tools_md.py`.
- CI builds and tests every version and loader; it must pass before merge.

Security problems go through [SECURITY.md](SECURITY.md), not a public issue.
