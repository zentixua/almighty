# Bots and vision

A bot is a real `ServerPlayer` (our `BotPlayer` subclass) with a networkless connection, like a Carpet player.
Everything it does goes through client packets and the normal handler (`channel.writeInbound` →
`ServerGamePacketListenerImpl`). Client-side calculations (movement, aim, tick-based mining, click delays,
vehicles) are reproduced from client code in `Controls` and `Vehicles`. Servers, permission plugins and mods thus
see an ordinary player: events, advancements, permissions, vanilla anticheat. `bot.act` steps and results:
`mcp/almighty.py` (`bot`). A timed key (`hold` with `ticks`) releases itself at tick start after `ticks` (another
`hold` extends it); rule-driven autopilot controls must use this. The bot executes a rule's step on its next tick,
after releasing expired keys: a rule running every tick needs `ticks` at least 2, or the key releases and presses
again (a new click for `attack`/`use`).

- Vanilla moves player-controlled vehicles (horse, boat, pig and strider with a fishing rod, camel) where the
  driver is local (`Entity.isControlledByLocalInstance` → `isLocalPlayer`): on a real player's client, while the
  server waits for coordinates. The common path is player input: `BotPlayer.isLocalPlayer` is true while the bot
  drives a vehicle tagged `almighty:driven_by_rider` (`Vehicles.local`), so the server simulates it. The tag lists
  vanilla vehicles; a mod or datapack can add vehicles driven by rider input (`xxa`/`zza`, jump, sneak), rather than
  client keys. Code behind the local-driver check may access client classes and crash a dedicated server, so a bot
  does not drive untagged mod vehicles as local. Input uses `ServerboundPlayerInputPacket` (also sent by clients).
  Vanilla's extra client behavior is reproduced from client code in `Vehicles`: riding jumps (`LocalPlayer.aiStep`:
  hold charges power, release calls `onPlayerJump` and sends `START_RIDING_JUMP`) and boat controls (`Boat.controlBoat`,
  client only). Bots apply boat controls after the boat tick; clients apply them between drag (`floatBoat`) and
  movement, so divide thrust and turn by the next tick's drag (`getStatus`, fields `status`, `deltaRotation`,
  `landFriction`, via reflection): speed and heading match the client exactly.
- Mod vehicles driven by client code (client keys, pilot-side flight) cannot use the common path: that code cannot
  run on a server without client classes. Use a mod compatibility driver (`VehicleDriver`: pilot tick with bot
  input axes, named mod keys for `press`). Without a driver or tag, the bot cannot drive it (`steering` in `bot`).
- Immersive Aircraft (`compat/immersiveaircraft`; `compileOnly` and present in GameTest, absent from our jar and
  `mods.toml`): IA reads `KeyBindings` for a local pilot (crashes on a dedicated server), so after the aircraft tick
  the bot performs the pilot step using IA methods in client tick order: `setInputs`, `updateVelocity`, `applyBoost`
  (using boost before the tick reduces it), `updateController`, `move`, control smoothing. Three protected methods
  use reflection (no public path to the pilot step; checked against IA 1.5.2, 1.6.0 and 1.7.0 for 1.21.1). If missing,
  log a warning and disable bot aircraft control. Controls: `left`/`right`, `jump`/`sneak`, `forward`/`back`
  (aircraft: heading, throttle with brake, flight stick). Collision uses the client formula and
  `CollisionMessage.receiveServer`; dismount and boost use `press` with the key name (`CommandMessage.receiveServer`).
  Airborne dismount requires a second press within a second, as on the client. IA disables vanilla sneak dismount
  for its vehicles.
- Client mod packets use the `payload` step (id and bytes after the id in base64): decode using the serverbound
  packet codec (`ServerboundCustomPayloadPacket.STREAM_CODEC` on `RegistryFriendlyByteBuf` with server registries
  and a NeoForge connection, like the network layer); handle with the mod's normal handler through the bot's
  connection. A common path for mods whose buttons and menus send custom packets (server-side simulation), with
  no mod-specific code: agents get bytes from mod sources or notes. Unknown id (`DiscardedPayload` from the codec)
  is a step error: the server silently discards it, so the agent would never know. Trailing bytes and any codec
  error (including `StackOverflowError` on nested items) are step errors, like the network layer disconnecting a
  client, never a tick crash. Reject over 32767 bytes (vanilla limit). The step is allowed: the bridge is already
  host-only with operator scripts, and the bot sends what a client could send.
- Respawn (death, leaving the End): `PlayerList.respawn` recreates the player with `new ServerPlayer`; no NeoForge
  event selects the class. `PlayerListMixin` (`@WrapOperation` on that `new`, MixinExtras) creates `BotPlayer` only
  for bots, leaving others untouched, like Carpet: a narrow mixin rather than bot relogin or our own `respawn` copy.
  No other mixins.
- Connection: `new Connection(SERVERBOUND)` + `EmbeddedChannel(BotSink, connection)` (active in the constructor),
  then `NetworkRegistry.configureMockConnection`: NEOFORGE connection type with every mod channel (otherwise mod
  packets to bots would fail). Login: `placeNewPlayer` with `CommonListenerCookie(…, ConnectionType.NEOFORGE)`.
  Register the bot in `Bots` before login (tab names and login events already know the bot).
- `EmbeddedEventLoop.inEventLoop()` is always true: writes run immediately on the server thread. `BotSink` must
  complete the promise (`trySuccess`) and release the message: protocol changes wait for writes with
  `syncUninterruptibly`, otherwise deadlocking the server thread. Packet queue: at most 20000, discard oldest entries.
- Bot connections are not in `ServerConnectionListener`, so nothing ticks them; `Bots.tick` (start of the mod's
  `ServerTickEvent.Post`) calls `doTick` and steps itself. Bots skip `ServerGamePacketListenerImpl.tick` (keepalive,
  idle kick, position reset to client coordinates with `absMoveTo`); vanilla `disconnect` still calls
  `handleDisconnection` via `executeBlocking`. That tick also reduces spam counters (chat and commands +20 per
  message, kick above 200; creative dropping). Bots decrement them by one per tick themselves (reflection), or the
  11th message over their entire lifetime would disconnect them.
- After movement, reproduce the movement handler: `chunkSource.move` only when a client would send a movement
  packet (`MovePackets`, like `LocalPlayer.sendPosition`: displacement over 2·10⁻⁴ from the last packet's position,
  rotation, on-ground change, every 20 ticks when stationary; every tick when riding). `ChunkMap.move` scans all
  tracked entities in the world; calling it every tick for stationary bots cost 37% of the server thread on a
  Zearth copy. Also `doCheckFallDamage`, player movement (`setKnownMovement`, added to projectiles: bow, trident,
  pearl), statistics and hunger (`checkMovementStatistics`). `ServerPlayer.checkFallDamage` is empty; fall damage
  requires this path.
- The vehicle, not physics, positions the rider. The world's vehicle tick seats riders (`rideTick` →
  `positionRider`) only where the world ticks entities (simulation distance from the player's chunk, with the
  chunk ready; not yet ahead of a fast aircraft over unloaded terrain; only the player's chunk in GameTest).
  Vehicles moved by the bot's pilot step (`VehicleDriver`) move there too, like on the client. After the pilot
  step, seat the bot like a player after client vehicle movement (`handleMoveVehicle` → NeoForge's
  `resyncPlayerWithVehicle`: `positionRider`, keep own view), and revert rider physics (`doTick`) as the handler
  tick does (`absMoveTo` to tick start). Otherwise the aircraft leaves entity-ticking chunks, the bot stays behind
  (and falls while still riding), its chunks stay with it, and the aircraft freezes in midair.
- Chunks: until chunks around the bot's bounding box plus its per-tick velocity are ready (`getChunkNow`), do not
  tick the bot, as with a client loading the world (player physics reads blocks and fluids underfoot; unready
  chunks would load synchronously in the tick). NeoForge teleports load destination chunks synchronously (patch
  in `Entity.setPosRaw`, also used by `/tp`). Login with `pos` or another dimension first holds the location with
  a GM ticket (`Areas`, chunk plus ring), waits in the background up to 30 s (`Bots.PLACE_WAIT`), then teleports and
  releases the lease. Player tickets retain the chunks (`chunkSource.move` immediately after teleport; keep the
  lease 100 more ticks, as player tickets enter through a queue). Create `ServerPlayer` in the Overworld, like
  vanilla login (`getPlayerForLogin`): its constructor searches around spawn, which would load chunks in a mod
  dimension with a sky. Bot/player responses (`looking_at`) use `Rays`, not `pick`.
- Teleports: reply with `ServerboundAcceptTeleportationPacket` to every `ClientboundPlayerPositionPacket`, or
  `isChangingDimension` stays set and the bot remains invulnerable after a dimension change. Acknowledgment moves
  the player (`absMoveTo`), so send it only when the destination chunk is ready (`getChunkNow`, `loading` in `bot`):
  returning bots log in at their saved location, and login teleport acknowledgment occurs in the world.
  Head: `setYHeadRot` on login and rotation; a living entity's view (`getViewYRot`) uses head rotation.
- Chat: acknowledge signed messages with `ServerboundChatAckPacket` (server disconnects at 4096 unacknowledged).
  Bots have no Mojang key: servers with `enforce-secure-profile` (dedicated, online) expect signatures, and clients
  hide unsigned player messages. Chat then follows the command-block path: `ServerChatEvent` and broadcast as a
  "disguised" message; commands with signable text (`/msg`, `/me`) use the bot's source without signatures.
  GameTest cannot test this path (`enforceSecureProfile` exists only on dedicated servers).
- Bots do not acknowledge chunk batches: the server sends one batch and waits. They receive no entity/block
  packets for unsent chunks (unneeded: vision and state read the server); chunk loading is unaffected.
- Bots do not handle connection reconfiguration (`ClientboundStartConfigurationPacket`); they disconnect.
- Profile cache: login writes the bot to `usercache.json`, so `/op`, `/whitelist`, `/ban` by name would find it;
  restore the entry (`GameProfileCache` reflection; GameTest has no cache, `NO_SERVICES`).
- Markers and protection: tab list and chat show `bots.marker` ("[bot]") in gray before the name (`TabListNameFormat`,
  `NameFormat`). Without `bots.allow_disguise` (owner's choice, default false), a bot cannot log in unmarked or use
  a server player's name or skin (online, whitelist, operators, profile cache; a skin's owner is in the `textures`
  property's base64 JSON, `profileName`/`profileId`). UUID is always its own. A player logging in with a bot's name
  takes it over (`PlayerLoggedInEvent`, `HIGHEST`). Bot limit: `bots.max` (8).
- Vision (`see`): eyes of a player, bot, any entity, or a free camera; rays use `Rays` (only `getChunkNow`, unloaded
  chunks are walls). Entity visibility: three rays through blocks' visual shapes to its bounding box. The image
  (`EyeView`) is a job under the tick budget: one ray per point through blocks (shape, map color, brighter/darker
  faces, sky/block light, water/glass tint, haze); entities are boxes colored by kind.
