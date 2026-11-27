#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "httpx>=0.27",
# ]
# ///
"""Command line for the server's Almighty mod, and a stdio relay for MCP clients that cannot speak HTTP.

Optional: the mod serves MCP itself at <address>/mcp (default http://127.0.0.1:25650/mcp); any harness that speaks
Streamable HTTP connects there directly (docs/guide). This script is for the command line and for stdio-only clients:

  uv run mcp/almighty.py call status   - one call: method and JSON parameters, JSON response
  uv run mcp/almighty.py call map '{"center": [0, 0], "size": 256}' --png map.png
  uv run mcp/almighty.py follow        - event feed: one JSON line per event (for Monitor);
                                             --from-start reads from the ring start, otherwise from this minute
  uv run mcp/almighty.py mcp           - stdio MCP relay to <address>/mcp (tools come from the mod)

Address: ALMIGHTY_URL (default http://127.0.0.1:25650); token: ALMIGHTY_TOKEN or the file in
ALMIGHTY_TOKEN_FILE (the mod writes config/almighty/token in the server directory on first start).
Methods and parameters: AGENTS.md ("Methods").
"""
import argparse
import base64
import json
import os
import sys
import time

import httpx

URL = os.environ.get("ALMIGHTY_URL", "http://127.0.0.1:25650").rstrip("/") + "/rpc"


class GmError(Exception):
    pass


def _token():
    token = os.environ.get("ALMIGHTY_TOKEN")
    if token:
        return token.strip()
    path = os.environ.get("ALMIGHTY_TOKEN_FILE")
    if not path:
        raise GmError("no token: set ALMIGHTY_TOKEN or ALMIGHTY_TOKEN_FILE (config/almighty/token on the server)")
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


_client = None


def rpc(method, params=None, timeout=None):
    """Call a mod method; mod errors raise GmError with their message. timeout overrides the response deadline in seconds."""
    global _client
    if _client is None:
        # The bridge waits up to 60 s for the server response; loopback needs no environment proxy.
        _client = httpx.Client(timeout=70, trust_env=False, headers={"Authorization": "Bearer " + _token()})
    body = {"method": method, "params": {k: v for k, v in (params or {}).items() if v is not None}}
    for attempt in range(4):
        try:
            r = _client.post(URL, json=body, **({"timeout": timeout} if timeout else {}))
        except httpx.HTTPError as e:
            raise GmError(f"bridge {URL} unreachable: {e}") from e
        if r.status_code == 503 and attempt < 3 and '"busy"' in r.text:
            time.sleep(0.5 * 2 ** attempt)  # the call was not started: the bridge's queue was full
            continue
        if r.status_code == 401 and attempt == 0 and not os.environ.get("ALMIGHTY_TOKEN"):
            # The owner rotated the token (/almighty revoke): read the file again once.
            _client.headers["Authorization"] = "Bearer " + _token()
            continue
        break
    try:
        data = r.json()
    except ValueError:
        raise GmError(f"bridge answered {r.status_code}: {r.text[:300]}")
    if not data.get("ok"):
        err = data.get("error") or {}
        raise GmError(f"{err.get('code', r.status_code)}: {err.get('message', r.text[:300])}")
    return data["result"]


def _split_image(result):
    """Snapshot response to (PNG bytes or None, remaining description)."""
    if isinstance(result, dict) and "png_base64" in result:
        rest = dict(result)
        png = base64.b64decode(rest.pop("png_base64"))
        return png, rest
    return None, result


MCP_URL = URL[: -len("/rpc")] + "/mcp"


def serve_mcp():
    """stdio MCP for clients that cannot speak HTTP: each JSON-RPC line goes to the mod's own /mcp endpoint.

    The tools, their descriptions and checks live in the mod (one source for every harness); this only relays.
    Clients that speak Streamable HTTP connect to <address>/mcp directly and need no Python at all.
    """
    import io
    stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")
    stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)

    def emit(obj):
        stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
        stdout.flush()

    def fail(msg_id, code, message):
        if msg_id is not None:
            emit({"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}})

    client = httpx.Client(timeout=70, trust_env=False, headers={"Authorization": "Bearer " + _token(),
                                                               "Accept": "application/json, text/event-stream"})
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            emit({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
            continue
        msg_id = msg.get("id") if isinstance(msg, dict) else None
        params = msg.get("params") if isinstance(msg, dict) else None
        params = params if isinstance(params, dict) else {}
        meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
        headers = {}
        version = meta.get("io.modelcontextprotocol/protocolVersion")
        if isinstance(version, str):
            headers["MCP-Protocol-Version"] = version
            headers["Mcp-Method"] = str(msg.get("method", ""))
            name = params.get("name")
            if isinstance(name, str):
                headers["Mcp-Name"] = name if name.isascii() and name.isprintable() and not name.startswith("=?base64?") \
                    else "=?base64?" + base64.b64encode(name.encode()).decode() + "?="
        for attempt in range(2):
            try:
                r = client.post(MCP_URL, content=line.encode("utf-8"), headers=headers)
            except httpx.HTTPError as e:
                r = None
                fail(msg_id, -32603, f"bridge {MCP_URL} unreachable: {e}")
                break
            if r.status_code == 401 and attempt == 0 and not os.environ.get("ALMIGHTY_TOKEN"):
                # The owner rotated the token (/almighty revoke): read the file again once.
                client.headers["Authorization"] = "Bearer " + _token()
                continue
            break
        if r is None or r.status_code == 202 or not r.content:
            continue
        try:
            reply = r.json()
        except ValueError:
            fail(msg_id, -32603, f"bridge answered {r.status_code}: {r.text[:300]}")
            continue
        if isinstance(reply, dict) and "error" in reply and reply.get("id") is None and msg_id is not None:
            reply["id"] = msg_id
        if msg_id is not None:
            emit(reply)


def follow(from_start):
    """Feed as lines; if the bridge is unavailable or the server restarts, report it in a line and keep waiting."""
    after, boot, down = 0, None, False
    if not from_start:
        while True:
            try:
                page = rpc("events", {"after": after, "limit": 1000})
            except GmError as e:
                print(json.dumps({"type": "bridge_down", "error": str(e)}, ensure_ascii=False), flush=True)
                down = True
                time.sleep(10)
                continue
            boot, after = page["boot"], page["next"]
            if len(page["events"]) < 1000:
                break
    pause = 2
    while True:
        try:
            page = rpc("events", {"after": after, "wait": 25, "limit": 500})
        except GmError as e:
            if not down:
                print(json.dumps({"type": "bridge_down", "error": str(e)}, ensure_ascii=False), flush=True)
                down = True
            time.sleep(pause)
            pause = min(pause * 2, 30)
            continue
        if down:
            print(json.dumps({"type": "bridge_up"}), flush=True)
            down, pause = False, 2
        if boot is not None and page["boot"] != boot:
            print(json.dumps({"type": "server_restarted", "boot": page["boot"]}), flush=True)
        boot = page["boot"]
        if page.get("dropped"):
            print(json.dumps({"type": "dropped", "count": page["dropped"]}), flush=True)
        for event in page["events"]:
            print(json.dumps(event, ensure_ascii=False), flush=True)
        after = page["next"]


def main():
    parser = argparse.ArgumentParser(description="Almighty: MCP server, one method call, event feed")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("mcp", help="stdio MCP relay to the mod's /mcp endpoint")
    c = sub.add_parser("call", help="call one method")
    c.add_argument("method")
    c.add_argument("params", nargs="?", default="{}", help="parameters as JSON")
    c.add_argument("--png", help="write the image of a view to this file")
    f = sub.add_parser("follow", help="event feed, one JSON line per event")
    f.add_argument("--from-start", action="store_true", help="start from the oldest event in the ring")
    a = parser.parse_args()
    try:
        if a.cmd == "mcp":
            serve_mcp()
        elif a.cmd == "call":
            png, rest = _split_image(rpc(a.method, json.loads(a.params)))
            if png is not None:
                if not a.png:
                    raise GmError("the answer has an image: pass --png <file>")
                with open(a.png, "wb") as out:
                    out.write(png)
                rest["png"] = a.png
            print(json.dumps(rest, ensure_ascii=False, indent=1))
        else:
            follow(a.from_start)
    except GmError as e:
        print(f"gm: {e}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
