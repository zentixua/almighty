# AGENTS.md

The project rules are in `CLAUDE.md`. They apply to every contributor, human or agent: read `CLAUDE.md` in
full before you start.

Where `CLAUDE.md` names Claude Code tools (Read, Edit, Write), the same rule applies to your own tools.

- Review: one round, by a model that did not write the code, from the diff and the code around it, not from the PR
  description. Block only on serious errors (wrong behaviour, a crash, a blown tick budget); everything else is optional.
- A commit ends with the `Co-Authored-By:` line of your own agent.
