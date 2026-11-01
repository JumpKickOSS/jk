# Change tasks, 2026-10-31

`TABLE.md` and `rows.jsonl` hold every run of the day; the run number says which build ran it.

| Runs | Build | What |
|---|---|---|
| 1–3 | graph-shared with the MCP `run` wait fixes (`998a81a72`) | round 2: Claude Code (all 15 tasks; jk-nograph on the 4 renames), Composer, grok, agy, muse (8 question tasks), Composer on the renames (jk and jk-nograph) |
| 200–202 | same | Sonnet 5.5 through cursor-agent (question tasks) |
| 300–301 | same | grok checks: 300 before the `--rules` fix (no system prompt reached the model), 301 after |
| 400 | same | Claude Code checks of the no-run-record fix on two edit tasks |
| 500–502 | final graph-shared (JK-4359, 4357, 4358, 4355, reference index, scoped rename hint) | Claude Code, all 15 tasks: 45/45 green |

grok runs from before the `--rules` fix (runs 1–3 of the first attempt) were removed: the harness had
passed the system prompt through `--system-prompt-override`, which grok 1.0.70 no longer shows the
model, so those runs measured nothing.
