# Mission almighty-core: report

Plan steps 1–9 (and the policy-editing and declarative-rule parts of 10–11) of `docs/design/plan-1.0.md`.
Each step went in as its own PR, merged after green CI and one review round by a fresh session that did not write
the code (its findings and the fixes are in each PR's comments). Every step was also run on a trial dedicated
NeoForge 21.1.264 server on loopback (`run/ded`, RCON for the console), driving the bridge over HTTP and bots as
players.

| Step | PR | State | Evidence |
|---|---|---|---|
| 1 Bridge hygiene, multiple agents | #40 | merged | Unit: per-agent tokens, origin rule, lockout (a valid token always passes), long polls do not starve calls, full pool → 503. Trial: two agents, `/almighty agents`, revoke. |
| 2 Deny list, guards, budgeted world calls | #43 | merged | Unit: deny list (stop, reload, save-off, forceload, tick rate, `^gm`, `^almighty`, plot coordinates, `execute … run` chains), script accident guard. GameTest: world calls share `work.ms_per_tick`, lag guard. Trial: each denied command refused. |
| 3 Identity, addressing, tickets, presence | #45 | merged | Unit: addressing (router port), tickets bound to player/agent/seq with 10 min expiry. GameTest: `/gm` tickets, bots never addressed. Trial: away reply in the player's language. |
| 4 Audit log, `/almighty log`, `pause` | #47 | merged | Unit: JSONL writer with end marker. GameTest: pause freezes only jobs with effects; cancel/release allowed while paused. Trial: `/almighty log`, `pause`/`resume`. |
| 5 Nodes, evaluator, gate, preset `private` | #48 | merged | Unit: every method declares a node; `private` is today's behaviour; no ticket never exceeds the floor; bad file → lockdown. GameTest: permission handler path. |
| 6 Origins, confirmations, receipts | #51 | merged | GameTest: commands run as the requester; another player's ticket needs that player's confirmation; forged prompts refused; ask_admin for commands. Trial: in-game prompts and receipts via bots. |
| 7 Content limits | #52 | merged | GameTest: a guest build cannot place a command block or a sign with a click event; `never_build`/`never_give` tags; spawn eggs refused; hourly give quota. |
| 8 Change journal, conflict-aware undo | #54 | merged | GameTest: undo restores only positions still holding the change's state; region-split reverts; redo needs the owner. Trial: `kill -9` mid-build, restart, undo of the interrupted change. |
| 9 Presets, zones, roles, quotas, claims | #56 | merged | GameTest: zone denies building, agent arena, commands through zones by every coordinate, bots in zones, granted roles, permission handler, block quota (also for the agent's own builds), claim source; only a real owner runs `/almighty owner`. Trial: Open Parties and Claims (real mod): refused in a claim, zone refusal, `/almighty role`. |
| 10 Policy editing by asking (part) | #57 | merged | Unit: restricting applies, granting waits for the owner, host-level refused, last owner kept, the agent unasked is not the owner on `private`, undo bound to its text, scripts owner-only. Trial: 202 then applied after `/almighty confirm` from the console; two `/almighty policy undo` back to the start. |
| 11 Declarative rules, approved scripts (part) | #58 | in review | Unit: conditions, no command injection through fields, approved scripts only while unchanged. GameTest: a declarative rule stops working when its creator loses the right; cancels only where the creator may build; server-wide cancel needs the owner; TTL. Trial: approve/edit/run a named script, owner chat rule, welcome rule turned off when the floor dropped. |

## Harness check

The headless Claude Code harness drove the trial server through `mcp/almighty.py mcp` (status, build, view,
bot calls). Codex CLI is not installed in this environment, so it was not checked.

## Open Skies

Artem (27.11): Open Skies may be reset to defaults, and its Almighty config and data deleted. So no legacy preset
is pinned there. The mod still gives an install that already has a token `private`, which is safe for other
servers that update. Open Skies gets the fresh-install defaults (`friends`) once its `config/almighty/` is
removed. The reset is a step on a live server, so it is left to Artem. On `friends` the GM acts unasked as a member
would, and asks owners for anything more.

## Not in this mission

- FTB Chunks support was compiled against its API but not run on a server.
