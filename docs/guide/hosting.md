# Hosting and remote access

English · [Русский](hosting.ru.md)

The bridge listens on `127.0.0.1:25650` by default: only programs on the server machine reach it. That is the safe
setup, and it is enough when your agent app runs on the same computer as the server. Otherwise, bring the agent to
the bridge without opening the bridge to the internet:

| Your setup | Recommended |
|---|---|
| Server on your own PC or in your home network | Run the agent on that PC, or keep the bridge on loopback and use the PC's own agent app. |
| VPS or dedicated server with SSH | An SSH tunnel from your computer: `ssh -N -L 25650:127.0.0.1:25650 you@server`, then connect the agent to `http://127.0.0.1:25650/mcp` on your computer. Or Tailscale on both machines. The bridge stays on loopback. |
| Self-hosted panel (Crafty, Pterodactyl, Pelican) | A tunnel to the host as above, or an allocated port bound to the container's own address (`bridge.host`), reachable only from the host. |
| Paid shared hosting (no SSH) | Only an extra allocated port on the public internet. Not recommended: the bridge speaks plain HTTP. If you must, use it only with a reverse proxy or tunnel that adds HTTPS in front (below). |
| Free hosting such as Aternos | Not supported: it offers only mods from its own library and no extra ports. |
| Cloud agent apps (claude.ai, ChatGPT on the web, cloud agents) | A public HTTPS address in front of the bridge: see below and [cloud.md](connect/cloud.md). |

## A public HTTPS address

Cloud apps connect from their makers' servers, so they need a public HTTPS address. Keep the bridge on loopback and
put an existing tool in front of it:

- On a VPS with a domain: a reverse proxy with automatic certificates, for example Caddy:
  `mc.example.org { reverse_proxy 127.0.0.1:25650 }`.
- Without a domain: Cloudflare Tunnel (`cloudflared tunnel --url http://127.0.0.1:25650`) gives an HTTPS address.

Then set `bridge.public_url = "https://mc.example.org"` in `config/almighty-common.toml` and restart, so sign-in and
links use the public address. Everyone on the internet can reach that address; only the sign-in (your typed code)
and agent tokens stand between them and operator rights, so read [Security](security.md).

## Several servers, proxies, Bedrock

- Behind Velocity or BungeeCord, each backend server has its own bridge and its own agents.
- Bedrock players through Geyser and Floodgate are players like any other; Geyser for modded servers supports only
  the newest Minecraft version, so on 1.21.1 it is not available.
