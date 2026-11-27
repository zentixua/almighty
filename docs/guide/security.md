# Security

English · [Русский](security.ru.md)

An agent connected to Almighty is as powerful as a server operator, and with scripts on (the default) it runs code in
the server's Java process with the rights of the operating system user running the server: files, processes,
network. Treat access to the bridge like shell access to the server machine.

## What protects the server

- **The bridge listens on loopback** (`127.0.0.1`) by default. Nothing outside the machine reaches it.
- **Every agent signs in.** A sign-in needs the owner to type, in the game (operator level 4) or the console, the
  four-digit code shown on the sign-in page. The in-game notice never shows the code, so approving something you did
  not start would mean typing a code you never saw. Waiting sign-ins expire after 10 minutes, at most 5 wait at once.
- **One identity per app.** Each approved app is its own agent with its own token: `/almighty agents` shows who
  called last and from where; `/almighty agent remove <name>` ends one at once; `/almighty revoke <name>` replaces
  its token.
- **Tokens stay secret.** They live in files only the server's owner can read (`config/almighty/agents/`), are never
  printed or logged, and sign-in links carry no secret. Five wrong tokens within a minute lock the address out for a
  while.
- **Browsers cannot drive the bridge.** Requests with a browser `Origin` are refused unless listed in
  `bridge.allowed_origins`; sign-in pages are refused when reached under a foreign host name.
- **The server protects itself** from known crashes for every agent: `stop`, `reload`, `save-off`,
  `save-all flush`, `forceload add`, `tick rate` above 100, building or teleporting onto Sable ship plots, and scripts
  that take chunk tickets, load chunks, start threads or processes, register listeners or sleep. Game master work
  shares a fixed time per tick and slows down when the server lags.
- **Text from players and notes is data.** The instructions every agent receives say so; chat, `/gm`, signs, books,
  names and notes from mods, datapacks or other agents are never instructions to it.

## Before opening the server to strangers

- Keep the bridge on loopback; reach it through an SSH tunnel or a reverse proxy with HTTPS ([Hosting](hosting.md)).
- Turn scripts off if you do not need them: `scripts.enabled = false` in `config/almighty-common.toml`.
- Approve only sign-ins you started yourself, and remove agents you no longer use.

## Reporting a problem

Open an issue at https://github.com/zentixua/almighty/issues; for something that should not be public, say so in the
issue and leave out the details, and the author will contact you.
