# Change tasks — 2026-11-24-change-tasks

Medians over runs. Tokens are input + output + cache read + cache write as the API reports them; a run stopped before it reported usage is left out of the token medians and counted as unreported. Graph share is graph calls over graph + Grep/Glob/Read calls. Wall is the agent's start to its final answer; tool s and jk s are the time inside tool calls and inside jk calls (MCP and shell) from the transcript's stamps, model s the rest; every jk arm starts on a warm engine.

| Driver | Task | Arm | Runs | Green | Median tokens | Uncached in | Output | Turns | Wall s | Model s | Tool s | jk s | Graph share | Median F1 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| cursor | commons-lang-delete-dead-code | jk-graph | 1 | 1 | 163018 | 14238 | 1580 | 9 | 34 | 28 | 6 | 5 | 0.69 | - |
| cursor | petclinic-delete-dead-code | jk-graph | 1 | 1 | 116316 | 10675 | 2025 | 7 | 96 | 73 | 22 | 22 | 0.58 | - |
