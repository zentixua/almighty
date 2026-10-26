#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "mcp>=1.2,<2",
#     "httpx>=0.27",
# ]
# ///
"""Connect an AI agent (game master) to the server's Almighty mod: MCP server and command line.

The mod listens over HTTP on the server (default 127.0.0.1:25650, only POST /rpc with a token); this script bridges to it:

  uv run mcp/almighty.py mcp           - MCP server (stdio) for Claude Code:
                                             claude mcp add almighty -- uv run /path/mcp/almighty.py mcp
  uv run mcp/almighty.py call status   - one call: method and JSON parameters, JSON response
  uv run mcp/almighty.py call map '{"center": [0, 0], "size": 256}' --png map.png
  uv run mcp/almighty.py follow        - event feed: one JSON line per event (for Monitor);
                                             --from-start reads from the ring start, otherwise from this minute

Address: ALMIGHTY_URL (default http://127.0.0.1:25650); token: ALMIGHTY_TOKEN or the file in
ALMIGHTY_TOKEN_FILE (the mod writes config/almighty/token in the server directory on first start).
Methods and parameters: CLAUDE.md.
"""
import argparse
import base64
import json
import logging
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
    try:
        r = _client.post(URL, json=body, **({"timeout": timeout} if timeout else {}))
    except httpx.HTTPError as e:
        raise GmError(f"bridge {URL} unreachable: {e}") from e
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


def _notes():
    """Append server mod and datapack notes to MCP instructions; if unavailable, add a line explaining where to get them."""
    try:
        notes = rpc("notes", timeout=5)
    except (GmError, OSError) as e:
        return f"\nNotes from the server's mods and datapacks: the notes tool (not received at startup: {e})."
    if not notes:
        return ""
    return "\nNotes from the server's mods and datapacks (current versions: the notes tool):\n\n" + "\n\n".join(
        f"[{n['id']}]\n{n.get('text') or n.get('error', '')}" for n in notes)


def serve_mcp():
    from mcp.server.fastmcp import FastMCP, Image

    # An INFO line for every httpx request clutters the MCP log.
    logging.getLogger("httpx").setLevel(logging.WARNING)

    mcp = FastMCP("almighty", instructions=(
        "Game master on a Minecraft server (Almighty mod). Tools: command - commands and functions, script - Groovy "
        "code in the game, rule - a reaction to any game event, run on the server itself, events - the event feed,\n"
        "view - map, side view and blocks as text, build - building with undo, area - load a place where nobody is, "
        "job - jobs, status - server and player, entities - entities, say - messages to players,\n"
        "bot - your own bot players: they join as players and act only through what a player has (keys, mouse, "
        "menus, chat); view eye - what a player, a bot or a free camera sees.\n"
        "Players write to the game master privately with /gm <text> (gm event in the feed); reply privately with say "
        "and to.\n"
        "To load a distant place, use area prepare. A script does not hand its closures and objects to the game "
        "(tickets, comparators, listeners, deferred tasks) and does not load chunks itself: reactions belong in rule.\n"
        "Coordinates are in blocks. Long jobs (build, image view) answer with the job's description if they do not "
        "finish within wait seconds; continue with job(id, wait)." + _notes()))

    def _out(result):
        png, rest = _split_image(result)
        text = json.dumps(rest, ensure_ascii=False, indent=1)
        return [Image(data=png, format="png"), text] if png else text

    # Validate here, not in the mod: the bridge would name from/to instead of start/end and silently ignore extra parameters.
    def _need(where, **given):
        missing = [k for k, v in given.items() if v is None]
        if missing:
            raise GmError(f"{where}: missing parameter {', '.join(missing)}")

    def _only(where, **given):
        extra = [k for k, v in given.items() if v is not None]
        if extra:
            raise GmError(f"{where}: does not take {', '.join(extra)}")

    def _points(where, n, **given):
        for k, v in given.items():
            if v is not None and len(v) != n:
                raise GmError(f"{where}: {k} must be {'[x, z]' if n == 2 else '[x, y, z]'}")

    @mcp.tool()
    def status(player: str | None = None) -> str:
        """Without player: the server - tick time (mspt, tps), player names, chunks, entities, time and weather per
        dimension, the game master's jobs and chunks. With player (a name): that player in detail - position, health,
        game mode, inventory by slot, effects, respawn point, the block they look at."""
        if player is None:
            return _out(rpc("status"))
        return _out(rpc("player", {"name": player}))

    @mcp.tool()
    def entities(center: list[int] | None = None, radius: int | None = None, start: list[int] | None = None,
                 end: list[int] | None = None, type: str | None = None, limit: int = 50,
                 dimension: str | None = None) -> str:
        """Entities around center [x, y, z] within radius, or in the box start..end; type filters
        (minecraft:zombie or zombie). Nearest first; the counts by type cover all matches. Loaded places only.
        Sable ships (Create Aeronautics) are not entities: call ships {name | center+radius} gives position, rotation,
        velocity (blocks per second), bounding boxes in the world and on the plot; in a script, gm.ship(name)."""
        return _out(rpc("entities", {"center": center, "radius": radius, "from": start, "to": end, "type": type,
                                     "limit": limit, "dimension": dimension}))

    @mcp.tool()
    def command(commands: list[str] | None = None, lines: list[str] | None = None, args: dict | None = None,
                pos: list[int] | None = None, dimension: str | None = None) -> str:
        """Runs as the game master (permission level 4), one of two:
        commands - commands in order, each with ok, result, output, errors;
        lines - a function from .mcfunction lines, without a file or /reload: lines starting with $ are macros with
        $(name) taken from args; a parse error names the line.
        The source is the world spawn point or pos [x, y, z]. Heavy work (a large /fill) goes through build."""
        if (commands is None) == (lines is None):
            raise GmError("command: exactly one of commands (commands) or lines (function lines)")
        if commands is not None:
            if args is not None:
                raise GmError("command: args only goes with lines (function macros)")
            return _out(rpc("command", {"commands": commands, "pos": pos, "dimension": dimension}))
        return _out(rpc("function", {"lines": lines, "args": args, "pos": pos, "dimension": dimension}))

    @mcp.tool()
    def say(text: str | None = None, component: dict | None = None, to: list[str] | None = None,
            style: str = "chat") -> str:
        """Messages to players: style chat (with the game master's name), title, subtitle or actionbar; to - who gets
        it (everyone by default); component - JSON text as in /tellraw instead of text."""
        return _out(rpc("say", {"text": text, "component": component, "to": to, "style": style}))

    @mcp.tool()
    def script(code: str, args: dict | None = None, timeout_ms: int = 1000) -> str:
        """Groovy code on the server thread: everything in the game and in any mod is reachable. Variables: server
        (MinecraftServer); gm - helper: gm.command('...') and gm.commandAs(entity, '...') return results like command,
        gm.emit(data) writes an emit event to the feed, gm.player(name), gm.level('minecraft:the_nether'); state - a
        map shared by all scripts and rules until the server restarts; args. Common classes are already imported
        (BlockPos, Blocks, Items, ServerPlayer, Entity, Vec3, AABB, Component, BuiltInRegistries...).
        The returned value goes to value as JSON, println to output; an error gives ok=false, error and line.
        timeout_ms (up to 10000) is a hard limit: a loop is interrupted, what was done stays. There is no undo: make
        large block changes with build. The time limit applies only while the call runs: a closure or script object
        handed to the game (a TicketType with a comparator closure, a listener, a deferred task) is called by the game
        later without a limit, and its error crashes the world tick, so do not hand them over; react to events with
        rule. Do not load chunks (own TicketType, level.getChunk load synchronously): for a distant place use
        area prepare."""
        return _out(rpc("script", {"code": code, "args": args, "timeout_ms": timeout_ms}))

    @mcp.tool()
    def rule(action: str = "list", event: str | None = None, script: str | None = None, name: str | None = None,
             every: int | None = None, limit: int | None = None, priority: str | None = None,
             canceled: bool | None = None, budget_ms: float | None = None, persist: bool | None = None,
             id: int | None = None, query: str | None = None) -> str:
        """A rule is a reaction to any game event, run on the server in the same tick, without the game master.
        action:
        add - event: a NeoForge event class of the game or any mod by short name ("BlockEvent.BreakEvent",
        "LivingDamageEvent.Pre", "CommandEvent"), or "tick" for every server tick. Without script, the event's
        properties go to the feed (type emit); script is Groovy with event and the script variables: gm.emit(data)
        writes to the feed, event.canceled = true cancels a cancellable event (to forbid something, use priority
        "highest"). canceled - also receive events that are already cancelled. every - at most once per N ticks (for
        tick, every Nth tick); limit - remove after N runs; budget_ms (default 5) - average time per tick over the last
        20 ticks; a single run may take at most 20 x budget_ms (at least 50 ms; the first run is not counted). Going
        over the budget, 10 errors in a row or more than 200 feed entries per second turn the rule off (rule.off in
        the feed; errors are rule.error). name - a rule with the same name replaces the earlier one; persist - survive
        a restart (stored with the world).
        remove - by id or name. list - all rules (a rule that was turned off has state off and a reason; a saved rule
        that did not load at startup is unloaded, remove it by name). types - find event classes by query (name, mod,
        cancellable)."""
        given = {"event": event, "script": script, "name": name, "every": every, "limit": limit, "priority": priority,
                 "canceled": canceled, "budget_ms": budget_ms, "persist": persist, "id": id, "query": query}
        takes = {"add": ("event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist"),
                 "remove": ("id", "name"), "list": (), "types": ("query",)}
        if action not in takes:
            raise GmError(f"rule: action is add, remove, list or types, not {action!r}")
        _only(f"rule {action}", **{k: v for k, v in given.items() if k not in takes[action]})
        if action == "add":
            _need("rule add", event=event)
            return _out(rpc("rule.add", {k: given[k] for k in takes["add"]}))
        if action == "remove":
            if id is None and name is None:
                raise GmError("rule remove: needs id or name")
            return _out(rpc("rule.remove", {"id": id, "name": name}))
        if action == "list":
            return _out(rpc("rules"))
        return _out(rpc("event.types", {"query": query}))

    @mcp.tool()
    def events(after: int = 0, wait: int = 0, limit: int = 200) -> str:
        """Events numbered after `after`: chat, joins and leaves, deaths with cause, advancements, dimension changes,
        player commands, gm (a player wrote to the game master privately with /gm <text>; reply privately with say and
        to), server stalls (lag), finished jobs (job), emit (rules and scripts), rule.off and rule.error (rule turned
        off, rule error). wait - wait up to 25 s for new events. boot changes when the server restarts."""
        return _out(rpc("events", {"after": after, "wait": wait, "limit": limit}))

    @mcp.tool()
    def view(kind: str, center: list[int] | None = None, size: int = 128, start: list[int] | None = None,
             end: list[int] | None = None, below: int | None = None, marks: list[dict] | None = None,
             direction: str | None = None, properties: bool = True, dimension: str | None = None, wait: int = 30,
             who: str | None = None, eye: list[float] | None = None, yaw: float | None = None,
             pitch: float | None = None, image: bool = True, width: int | None = None, height: int | None = None,
             fov: float | None = None, distance: int | None = None, radius: int | None = None):
        """Look at the world; kind:
        map - top-down map like a vanilla map: center [x, z] and size (up to 1024), or corners start/end [x, z]; north
        is up. below - look under the ceiling from this height (caves, the Nether, floors); marks - your own markers
        [{label, x, z}]. Players are coloured squares (see legend). Grey checkerboard: chunk not loaded (area prepare).
        look - view of the box start..end [x, y, z] with direction: north/south/east/west (facade, section), down
        (plan), up. Nearer is brighter; light blue means empty all the way through. Face up to 256x256, depth up to 256.
        blocks - blocks of the box start..end [x, y, z] as text (up to 64 per axis, up to 32768): a palette of
        symbol -> block, layers from bottom to top, rows from north to south, characters from west to east; '.' air,
        '?' not loaded; properties=false leaves out block properties. build (op layers) takes the same format.
        eye - through someone's eyes: who - a player name (bots too) or an entity UUID, or a free camera eye [x, y, z]
        with yaw (0 south, 90 west, -90 east) and pitch (down is +). Answer: where it looks, light, aim - the block or
        entity under the crosshair within arm's reach, aim_far - the first thing on the ray up to distance (96),
        entities - entities within radius (48) with distance, angle from the view direction (yaw_offset right +,
        pitch_offset down +), in_view and visible (not behind blocks); for a bot, sounds it heard. image (default
        true) - a width x height picture (224x126, up to 480x270) with field of view fov (70): blocks in map colours
        with light, entities as boxes, crosshair at the centre.
        map, look and eye return a picture; if not done within wait seconds, the job's description, then job."""
        eye_only = {"who": who, "eye": eye, "yaw": yaw, "pitch": pitch, "width": width, "height": height, "fov": fov,
                    "distance": distance, "radius": radius}
        if kind == "eye":
            _only("view eye", center=center, start=start, end=end, below=below, marks=marks, direction=direction)
            if (who is None) == (eye is None):
                raise GmError("view eye: exactly one of who (name or UUID) or eye [x, y, z]")
            if who is None:
                _points("view eye", 3, eye=eye)
            elif yaw is not None or pitch is not None or dimension is not None:
                raise GmError("view eye: yaw, pitch and dimension only go with the free camera eye")
            ident = {}
            if who is not None:
                ident = {"uuid": who} if len(who) == 36 and who.count("-") == 4 else {"name": who}
            return _out(rpc("see", {**ident, "at": eye, "yaw": yaw, "pitch": pitch, "dimension": dimension,
                                    "image": image, "width": width, "height": height, "fov": fov,
                                    "distance": distance, "radius": radius, "wait": wait}))
        _only(f"view {kind}", **{k: v for k, v in eye_only.items() if v is not None})
        if kind == "map":
            _only("view map", direction=direction)
            by_center = center is not None and start is None and end is None
            by_corners = center is None and start is not None and end is not None
            if not (by_center or by_corners):
                raise GmError("view map: center [x, z] or both corners start and end [x, z]")
            _points("view map", 2, center=center, start=start, end=end)
            return _out(rpc("map", {"center": center, "size": size if center else None, "from": start, "to": end,
                                    "below": below, "marks": marks, "dimension": dimension, "wait": wait}))
        if kind == "look":
            _only("view look", center=center, below=below, marks=marks)
            _need("view look", start=start, end=end, direction=direction)
            _points("view look", 3, start=start, end=end)
            return _out(rpc("look", {"from": start, "to": end, "look": direction, "dimension": dimension,
                                     "wait": wait}))
        if kind == "blocks":
            _only("view blocks", center=center, below=below, marks=marks, direction=direction)
            _need("view blocks", start=start, end=end)
            _points("view blocks", 3, start=start, end=end)
            return _out(rpc("blocks", {"from": start, "to": end, "properties": properties, "dimension": dimension}))
        raise GmError(f"view: kind is map, look, blocks or eye, not {kind!r}")

    @mcp.tool()
    def build(ops: list[dict] | None = None, undo: int | None = None, dimension: str | None = None,
              wait: int = 30) -> str:
        """Builds in portions between ticks (the server does not freeze), with undo; one of two:
        ops - in order:
        {"op": "fill", "from": [x,y,z], "to": [x,y,z], "block": "stone", "mode": "replace|keep|hollow|outline"},
        {"op": "set", "pos": [x,y,z], "block": "oak_stairs[facing=east]"},
        {"op": "layers", "origin": [x,y,z], "palette": {"#": "stone"}, "layers": [["#.#", ...], ...]}
        (a symbol not in the palette leaves the block untouched). Waits for its chunks to load by itself. The answer
        has the job number for undo.
        undo - a job number: undo that job (a build or an undo), restoring blocks and block entity contents."""
        if (ops is None) == (undo is None):
            raise GmError("build: exactly one of ops (build) or undo (job number to undo)")
        if undo is not None:
            _only("build undo", dimension=dimension)
            return _out(rpc("undo", {"job": undo, "wait": wait}))
        return _out(rpc("build", {"ops": ops, "dimension": dimension, "wait": wait}))

    @mcp.tool()
    def area(action: str = "list", start: list[int] | None = None, end: list[int] | None = None,
             ttl_seconds: int = 300, wait: int = 0, id: int | None = None, dimension: str | None = None) -> str:
        """The game master's chunks; action:
        prepare - load the chunks of the box start..end [x, z] without ticking them (in the background) and hold them
        for ttl_seconds (up to 3600), to map or read a place where nobody is; wait - wait until they are ready. The
        answer is the area with its number.
        release - release area id. list - what the game master holds: areas and builds."""
        if action == "prepare":
            _only("area prepare", id=id)
            _need("area prepare", start=start, end=end)
            _points("area prepare", 2, start=start, end=end)
            return _out(rpc("area.prepare", {"from": start, "to": end, "ttl_seconds": ttl_seconds, "wait": wait,
                                             "dimension": dimension}))
        if action == "release":
            _only("area release", start=start, end=end, dimension=dimension)
            _need("area release", id=id)
            return _out(rpc("area.release", {"id": id}))
        if action == "list":
            _only("area list", start=start, end=end, id=id, dimension=dimension)
            return _out(rpc("areas"))
        raise GmError(f"area: action is prepare, release or list, not {action!r}")

    @mcp.tool()
    def job(id: int | None = None, wait: int = 0, cancel: bool = False):
        """The game master's jobs. Without id: running jobs and the latest finished ones. With id: that job; wait -
        wait up to that many seconds for it to finish; an image view returns its picture only once. With id and
        cancel=true: stop the job (what is done stays; undo with build undo)."""
        if id is None:
            if cancel or wait:
                raise GmError("job: cancel and wait need a job id")
            return _out(rpc("jobs"))
        if cancel:
            if wait:
                raise GmError("job: cancel does not wait; leave out wait")
            return _out(rpc("cancel", {"id": id}))
        return _out(rpc("job", {"id": id, "wait": wait}))

    @mcp.tool()
    def bot(action: str = "list", name: str | None = None, actions: list[dict] | None = None, replace: bool = False,
            wait: int = 30, pos: list[float] | None = None, dimension: str | None = None, yaw: float | None = None,
            pitch: float | None = None, gamemode: str | None = None, skin: str | dict | None = None,
            marker: bool | None = None, auto_respawn: bool | None = None, after: int | None = None):
        """Your own bot players: they join the server as players (marked in the tab list) and do what a player does
        by hand: keys and mouse, hotbar, menus, chat and commands (with their own player's permissions), signs,
        respawning - through the same packets as a client, so the server and mods see an ordinary player. action:
        spawn - name (3-16 Latin letters, digits, _), pos [x, y, z], dimension, yaw, pitch, gamemode, skin (account
        name or {value, signature}), auto_respawn (true by default); the spawn position is loaded before the bot joins
        (up to 30 s). The name or skin of a player of the server, and joining without the marker (marker=false), only
        if the owner turned on bots.allow_disguise; when a player with the bot's name joins, the bot gives way.
        remove - name. list - all bots. state - the bot in detail: body, inventory, vehicle (vehicle: type, whether
        the bot drives it, mod keys in keys), open menu (slots by number), keys, program, mail (chat, whispers,
        screens, titles, death) after number after.
        act - a program of actions, step by step; instant steps run back to back in one tick, only wait and walk_to
        take time.
        Steps: {"hold": "forward"|["forward","sprint"], "ticks": n} (forward back left right jump sneak sprint attack
        use; without ticks - hold until release; with ticks - the key releases itself after n ticks, and a new hold
        extends it: this is how a rule autopilot holds the controls, and the controls return to neutral when the rule
        stops; for a rule that runs every tick, ticks >= 2),
        {"release": "all"|keys},
        {"wait": ticks}, {"look": [yaw, pitch]}, {"turn": [dyaw, dpitch]},
        {"look_at": [x, y, z] | UUID | name} (integers mean the block centre), {"click": "attack"|"use", "at": ...},
        {"slot": 0-8}, {"drop": "one"|"stack"}, {"swap_hands": true}, {"menu": slot, "button": 0, "mode":
        "pickup"|"quick_move"|"swap"|"throw"|"pickup_all"|"clone"|"quick_craft"} (-999 is outside the window),
        {"menu_button": n}, {"close": true}, {"chat": "text" | "/command"}, {"sign": [x, y, z], "lines": [...]},
        {"respawn": true}, {"fly": true|false}, {"player_command": "start_fall_flying"|...}, {"walk_to": [x, z],
        "sprint": false, "within": 0.6}, {"press": "key.immersive_aircraft.dismount"} (a mod vehicle key by its name
        in the controls settings; the available ones are keys in state), {"payload": "mod:packet", "data": base64} (a
        mod packet from the client: the bytes after the id, as on the wire, taken from the mod's sources or its note;
        this is how a bot presses buttons of mods whose client sends its own packets). A failed step stops the
        program. replace - drop the running program; otherwise the new one waits in the queue. wait - wait up to that
        many seconds for the end (up to 50); if it does not end in time, a description is returned; the end of the
        program is a bot_done event in the feed, the result is in state.
        When a rule that drove a bot (bot.act or bot.send in the rule's script) is turned off, the bot stops: programs
        are dropped, keys released (bot_stopped event; bots_stopped in rule.off).
        Vehicles: get in with click use on it, get out with sneak. A horse, boat, pig or camel is driven with keys, as
        by a client (jump while riding: hold and release jump); a mod vehicle that reads player input is driven
        the same way once it has the entity tag almighty:driven_by_rider (datapack). Immersive Aircraft vehicles:
        left/right, jump/sneak, forward/back are its controls, as for a player (for an aircraft: heading, more/less
        thrust with brake, pitch);
        get out with press key.immersive_aircraft.dismount (in the air, twice within a second), boost with
        key.immersive_aircraft.boost."""
        given = {"name": name, "actions": actions, "pos": pos, "dimension": dimension, "yaw": yaw, "pitch": pitch,
                 "gamemode": gamemode, "skin": skin, "marker": marker, "auto_respawn": auto_respawn, "after": after}
        takes = {"spawn": ("name", "pos", "dimension", "yaw", "pitch", "gamemode", "skin", "marker", "auto_respawn"),
                 "remove": ("name",), "list": (), "state": ("name", "after"), "act": ("name", "actions")}
        if action not in takes:
            raise GmError(f"bot: action is spawn, remove, list, state or act, not {action!r}")
        _only(f"bot {action}", **{k: v for k, v in given.items() if k not in takes[action]})
        if action == "list":
            return _out(rpc("bots"))
        _need(f"bot {action}", name=name)
        if action == "spawn":
            _points("bot spawn", 3, pos=pos)
            return _out(rpc("bot.spawn", {k: given[k] for k in takes["spawn"]}))
        if action == "remove":
            return _out(rpc("bot.remove", {"name": name}))
        if action == "state":
            return _out(rpc("bot", {"name": name, "after": after}))
        _need("bot act", actions=actions)
        return _out(rpc("bot.act", {"name": name, "actions": actions, "replace": replace, "wait": wait}))

    @mcp.tool()
    def notes() -> str:
        """Notes from the server's mods and datapacks: how to work with their mechanics (commands, rules). They are
        already in the instructions when the adapter starts; this returns them from the server's current data, after
        /reload or a change of mods."""
        return _out(rpc("notes"))

    @mcp.tool()
    def call(method: str, params: dict | None = None):
        """Any bridge method as is (for a new method that has no tool yet)."""
        return _out(rpc(method, params))

    mcp.run()


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
    sub.add_parser("mcp", help="MCP server (stdio)")
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
