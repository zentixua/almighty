# Security policy

Almighty gives AI agents operator rights and, with scripts on, code execution on the server machine, so its access
controls matter. Security fixes target the latest release for every supported Minecraft version and loader.

In scope: anything that lets someone act through the bridge without an approved sign-in or token, exceed the rights
`policy.toml` gives them, read a token, forge a confirmation or a player's identity, or crash the server through a
call the deny list should refuse. Out of scope: what an agent you approved with owner rights does with them, and
setups that expose the bridge on a public network without TLS.

Do not post exploit details, tokens or private logs in a public issue. Use **Report a vulnerability** on the
repository's [Security page](https://github.com/zentixua/almighty/security) if it is available. If it is not, open an
[issue](https://github.com/zentixua/almighty/issues/new) titled **Security contact request**, without technical
details, and the maintainer will arrange a private channel.

Include the affected versions, steps to reproduce, the impact, and any workaround. No response time is guaranteed.

How Almighty protects a server, and how to set it up safely: [Safety and security](docs/guide/security.md).
