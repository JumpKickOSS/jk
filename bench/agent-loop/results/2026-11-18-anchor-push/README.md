# Anchor push: does pushing the places a change left under an old name help agents, or is it noise?

Ticket JK-M4XED (bench); the defect found here: JK-HVWV7-anchor-push-gate. Host: daybreak WSL (Ubuntu-26.04, 12 GB).

## Stage P (2026-11-18 08:01-15:49 MST): one build, only the setting differs

- Build: arm M = origin/main 750848aea, `jk install`ed into its own home (/var/tmp/jk-bench/jk-M-home), self-update never,
  jk 0.52.1-SNAPSHOT, jk_build 193531a77b30 on every row. Arms: O = `AGENT_LOOP_ANCHOR_TAIL` unset (main's default, off),
  S = `silent`. Node v24.21.0 on PATH, so the ts-client pair's hidden node check ran.
- Tasks: traps petclinic-push-column-h2, -config-key, -json-property, -ts-client; decoys petclinic-push-decoy-getpet,
  -decoy-not-found, -decoy-limit-value, -ts-client-decoy. 2 interleaved repeats, O and S alternating.
- Configs: 8 of 10. grok-4.7 and grok-4.5 were cut (the grok CLI had lost its login; it was back at 12:14, too late to
  be worth a catch-up here, see below); cursor grok-4.7-high ran repeat 1 only (cut for the budget, 5.1 min/run).
  240 rows (120 O, 120 S), no `error` row.

## The finding that limits this stage: the push almost never reached the agent (JK-HVWV7)

On main, `AnchorsLeft.after` attaches the stale-anchor lines only to a verdict matching `^OK (test|build|gate)\b`, but the
verdict of an MCP `run(kind=gate)` reads `OK guard <ws> · 44.0s`. The gate is the call agents finish with, so:

- 118 of the 120 S transcripts carry a bare `OK guard` verdict; only **8 of 120 S runs (all traps) ever showed a push line**,
  and in each of the 8 it came on a plain `OK test` verdict (muse 5, Opus 1, agy 1, cursor grok-4.7-high 1).
- Sonnet, codex terra, codex sol and cursor composer saw no push in any run: for them S and O are the same product, and
  their S-against-O differences are **run-to-run noise** (an A/A test).

So stage P can show what the rare push costs (noise reads, extra turns) and calibrates the noise; it **cannot show whether
the push helps**. That is measured by stage P' (arm M2 = M + the one-word fix: the pattern also matches `guard`).

## What stage P shows

| Config | Pushes shown (S trap runs) | Traps green O → S | Decoy reads/run O → S | Verdict on M |
|---|---:|---|---|---|
| claude2 / sonnet-5-5 medium | 0/8 | 6/8 → 6/8 | 2.38 → 2.25 | no push shown (A/A) |
| claude2 / opus-5-5 medium | 1/8 | 8/8 → 8/8 | 4.62 → 4.50 | neutral |
| codex / gpt-6.1-terra low | 0/8 | 7/8 → 4/8 | 2.12 → 2.88 | no push shown (A/A): the 3-run gap is noise |
| codex / gpt-6.1-sol low | 0/8 | 6/8 → 6/8 | 1.75 → 1.62 | no push shown (A/A) |
| cursor / composer-2.5 | 0/8 | 6/8 → 5/8 | 13.50 → 16.38 | no push shown (A/A) |
| cursor / grok-4.7-high (1 repeat) | 1/4 | 2/4 → 3/4 | 15.25 → 12.00 | neutral (the pushed run named 4 files, fixed none, claimed) |
| agy / gemini-4-hydrogen-medium | 1/8 | 8/8 → 8/8 | 6.38 → 6.25 | neutral |
| muse / spark-1.3-contributor high | 5/8 | 6/8 → 8/8 | 9.00 → 12.38 | helps, small: json-property green 2/2 with the push, claimed 2/2 without |

Counts on M: helps 1 (muse), neutral 3, no push shown 4. Noise: **no pushed file was read and then left alone** in any run
(noise reads 0.00 for every config); no decoy run was ever pushed (correct: a decoy leaves no place under an old name).
Decoy reads and turns move by up to +3 reads/run and +10-14 turns (composer, muse) in configs that saw no push on any decoy:
that is the noise band to read P' against. Trap green moves by up to 3 of 8 with no push shown (codex terra).

Per task over all configs (O → S): column-h2 11/15 → 10/15, config-key 15/15 → 13/15, json-property 8/15 → 10/15,
ts-client 15/15 → 15/15; every decoy 15/15 both ways. json-property and column-h2 are the traps agents miss.

Files: TABLE.md (full tables), rows-stageP-M-{O,S}.jsonl (raw rows), runs-stageP-M.jsonl (per run: pushes, pushed files,
reads of them, edits), runlog-stageP-daybreak.txt, pushnoise.py / calls.py / tabulate.py / ptask.py (tables), plan3.py /
runner.py (plan and runner; CUTS lines logged as CUT).
