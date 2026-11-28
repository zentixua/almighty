# Hosting and remote access

English · [Русский](hosting.ru.md)

The bridge listens on `127.0.0.1:25650` by default: only programs on the server machine reach it. That is the safe
setup, and it is enough when your agent app runs on the same computer as the server. Otherwise, bring the agent to
the bridge without opening the bridge to the internet:

| Your setup | Recommended |
|---|---|
| Server on your own PC or in your home network | Run the agent on that PC, or keep the bridge on loopback and use the PC's own agent app. |
| VPS or dedicated server with SSH | An SSH tunnel from your computer: `ssh -N -L 25650:127.0.0.1:25650 you@server`, then connect the agent to `http://127.0.0.1:25650/mcp` on your computer. The bridge stays on loopback. Or Tailscale on both machines, with `bridge.host` set to the server's Tailscale address. |
| Self-hosted panel (Crafty, Pterodactyl, Pelican) | A tunnel to the host as above, or an allocated port bound to the container's own address (`bridge.host`), reachable only from the host, with `bridge.allow_plain_http_off_loopback = true`. |
| Paid shared hosting (no SSH) | Only an extra allocated port on the public internet, which means the bridge itself is public. Use it only with the bridge's own HTTPS: a certificate for that host's name in `bridge.tls_cert` and `bridge.tls_key`. Without it, tokens and sign-in codes cross the internet in plain text: do not. A proxy elsewhere does not help, because it would still reach the bridge over plain HTTP. |
| Free hosting such as Aternos | Not supported: it offers only mods from its own library and no extra ports. |
| Cloud agent apps (claude.ai, ChatGPT on the web, cloud agents) | A public HTTPS address in front of the bridge: see below and [cloud.md](connect/cloud.md). |

## A public HTTPS address

Cloud apps connect from their makers' servers, so they need a public HTTPS address. Keep the bridge on loopback and
put an existing tool in front of it:

- On a VPS with a domain: a reverse proxy with automatic certificates, for example Caddy:
  `mc.example.org { reverse_proxy 127.0.0.1:25650 }`.
- Without a domain: Cloudflare Tunnel. A quick tunnel (`cloudflared tunnel --url http://127.0.0.1:25650`) gets a new address
  each time it starts, so `bridge.public_url` has to change with it; a named tunnel keeps its address.

Then set `bridge.public_url = "https://mc.example.org"` in `config/almighty-common.toml` and restart, so sign-in and
links use the public address. Everyone on the internet can reach that address; only the sign-in (your typed code)
and agent tokens stand between them and operator rights, so read [Security](security.md).

Behind a proxy or tunnel the server sees every client as the proxy: `/almighty agents` and sign-in notices show its
address (usually `127.0.0.1`), and the wrong-token lockout counts all outside clients together (a valid token always
passes it, so agents keep working). `allow_from` sees the proxy's address too.

## The bridge's own HTTPS

When the bridge itself must be reachable from other machines, give it your certificate instead of a proxy:

```toml
[bridge]
host = "0.0.0.0"
tls_cert = "certs/fullchain.pem"
tls_key = "certs/privkey.pem"
```

The files are a PEM certificate chain and an unencrypted PKCS#8 private key (`BEGIN PRIVATE KEY`), for example from
Let's Encrypt; paths are relative to the server folder. The bridge then speaks only HTTPS, and sign-in and
`/almighty connect` use `https`. Almighty does not create certificates itself.

Without TLS the bridge starts only on loopback: with any other `bridge.host` it does not start, and the log names the
fix. On a network only the server machine reaches (a container's private address whose port is not published), set
`bridge.allow_plain_http_off_loopback = true` to start it anyway. Never do that on a public address.

## Approving sign-ins on a remote server

The sign-in code is approved where you can type on the server: in game, or in the server console (the panel's console
or the server's terminal over SSH; RCON counts as the console). On a fresh server the first level-4 operator who
approves in game becomes the owner. From the console, name yourself as the owner while approving your own app:
`/almighty approve <code> <name> owner <your player name>`. Once the server has an owner, only the console and owners
who are also level-4 operators approve sign-ins ([Getting
started](getting-started.md#3-approve-the-sign-in-with-its-code)).

An agent token used from a known machine can be limited to its addresses in `policy.toml`:

```toml
[agents.ci]
allow_from = ["203.0.113.7", "10.0.0.0/8"]
```

From any other address the token is refused and counts as a wrong token.

## Several servers, proxies, Bedrock

- Behind Velocity or BungeeCord, each backend server has its own bridge and its own agents. With Velocity modern
  forwarding set `identity = "proxy"` in `policy.toml`, so players count as verified.
- Bedrock players through Geyser and Floodgate are players like any other and count as verified. Geyser for modded
  servers supports only some Minecraft versions; check its site for yours.
