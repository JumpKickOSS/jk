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

Files for stage P: TABLE.md (full tables), rows-stageP-M-{O,S}.jsonl (raw rows), runs-stageP-M.jsonl (per run: pushes, pushed files,
reads of them, edits), runlog-stageP-daybreak.txt, pushnoise.py / calls.py / tabulate.py / ptask.py (tables), plan3.py /
runner.py (plan and runner; CUTS lines logged as CUT).

## Stage P' (2026-11-18 16:12 - 2026-11-19 00:02 MST): the same, on main plus the fix

- Build: arm M2 = origin/main 750848aea with `AnchorsLeft.REFRESHED` = `^OK (test|build|gate|guard)\b` (the one-word fix of
  JK-HVWV7), installed into /var/tmp/jk-bench/jk-M2-home, jk 0.52.1-SNAPSHOT, jk_build 09fca0a7260d on every row, engine sha
  86efa1b4. The install also drops one assertion of AnchorsLeftTest (`after(dir, "OK guard ...")` is empty), which holds the bug
  itself: with it the engine does not install (chain5.sh shows both edits; the canonical checkout was restored after the install).
- Same 8 tasks, O and S, 2 interleaved repeats. Configs: 9 (grok-4.7 and grok-4.5 back after a new grok login). Cut for the
  budget: cursor grok-4.7-high (all of P'; 5.1 min/run in P) and grok-4.7's repeat 2 (5.2 min/run). 272 rows, one silent grok-4.7
  run red on the 20-minute cap, no `error` row.

### Verdict per config (the table to read)

"Pushed" = silent trap runs whose jk answer named places left under the old name. A conversion is a silent run green where the
O run of the same config, task and repeat was not; a loss the reverse. Stage P (M: the push almost never shown) is the noise
reference: there, with no push shown, trap green moved by up to 3 of 8 in one config and runs with no push made 4 losses and
1 conversion; trap tokens moved from -38% to +21% (per config, sum of per-task medians).

| Config | Pushed (S traps) | Traps green O → S | Pushed: conversions / losses | Trap tokens O → S | Decoys (never pushed): reads, turns O → S | Verdict |
|---|---:|---|---|---|---|---|
| claude2 / sonnet-5-5 medium | 4/8 | 7/8 → 8/8 | 0 / 0 (all 4 fixed the named place) | +13% | 2.50 → 2.25, 27 → 25.5 | neutral |
| claude2 / opus-5-5 medium | 2/8 | 8/8 → 7/8 | 0 / 1 (shown, argued away, see below) | +14% | 4.12 → 5.00, 39.5 → 43.5 | neutral |
| codex / gpt-6.1-terra low | 0/8 | 6/8 → 8/8 | 0 / 0 | -29% | 2.62 → 3.12, 24.5 → 25.5 | neutral (its `rename` left nothing stale: the push had nothing to say) |
| codex / gpt-6.1-sol low | 4/8 | 5/8 → 7/8 | **2 / 0** (json-property both repeats) | +24% | 1.50 → 1.62, 23 → 22.5 | **helps** |
| grok / grok-4.7 high (1 repeat) | 1/4 | 3/4 → 3/4 | 0 / 0 | -59% | 14.75 → 17.50, 43 → 49 | neutral |
| grok / grok-4.5 high | 3/8 | 8/8 → 8/8 | 0 / 0 | -28% | 7.75 → 7.12, 27 → 27.5 | neutral (at the ceiling) |
| cursor / composer-2.5 | 5/8 | 5/8 → 6/8 | **1 / 0** (json-property) | -12% | 17.38 → 11.88, 48 → 39 | **helps** (small) |
| agy / gemini-4-hydrogen-medium | 4/8 | 8/8 → 8/8 | 0 / 0 (all fixed the named places) | -10% | 5.38 → 4.62, 37.5 → 34.5 | neutral (at the ceiling) |
| muse / spark-1.3-contributor high | 5/8 | 6/8 → 7/8 | 0 / 0 (stage P: 2 / 0, json-property) | +17% | 11.00 → 9.25, 67.5 → 65.5 | neutral (helps in P) |

Counts on M2: **helps 2, neutral 7, hurts 0** (with stage P: muse helps too).

Pooled over all configs: trap green O 56/68 → S 62/68 (one-sided Fisher p = 0.10; stage P, push mostly unseen: 49/60 → 48/60).
Per task: **json-property 11/17 → 16/17 (p = 0.04)**, column-h2 12/17 → 12/17, config-key 16/17 → 17/17, ts-client 17/17 both;
every decoy 17/17 both ways. Runs that showed a push: 5 conversions and 1 loss over both stages; runs with no push: 5 and 4 (noise).

Noise: **no silent decoy run was ever pushed** (0 of 68 here, 0 of 60 in P); **no pushed file was read and then left alone in any
run** (noise reads 0.00 everywhere); decoy reads and turns move inside stage P's no-push band. The cost when the push fires is
on traps: the agent fixes the named place and runs the gate again (trap turns up 1.5 to 6 for Sonnet, codex sol, composer and
muse; a gate is ~45 s of petclinic's suite).

What the push does not fix: on column-h2 four pushed runs (Opus, codex sol, composer twice) were told the four places
(mysql and postgres schema, postgres data, Owner.java) and argued the push away. Opus: "The jk report still flags `telephone`
in the MySQL/PostgreSQL schemas, ... That's on purpose; those places should keep the old name." The line reaches the agent but
does not say the places must change; an agent that decided the task was H2-only keeps that reading. grok rows count a push but
name no files (pushnoise.py does not parse grok's transcript format for the file list).

### Does the silent push earn default-on?

**Yes, on this evidence, once JK-HVWV7 lands on main.** It costs nothing where nothing is stale (no decoy run was pushed, no
pushed file was read for nothing, decoy tokens and turns inside the noise), and where it fires agents act on it: most pushed
runs fixed the place named, json-property (the trap agents miss most) went from 11/17 to 16/17, and pushed runs converted 5
failures for 1 loss that the agent argued away. Not proven per config: every per-config difference is inside the ±3-of-8
noise band, so the case rests on the pooled trap and the zero cost. Without the fix the setting does almost nothing (stage P:
8 of 120 silent runs ever saw it), so default-on before JK-HVWV7 would be a no-op.

### count and kinds (2026-11-19 00:05-02:03 MST)

Because P' showed an effect and time was left: `count` (Sc) and `kinds` (Sk) on M2 for the three configs where the push helped
(codex sol, cursor composer, muse), the four traps only (a decoy leaves nothing stale, so no mode shows anything there),
2 repeats, 48 rows, no error. They ran after P', not interleaved with O and S (same build, same host). TABLE-P2k.md.

| Traps green, 3 configs | O (off) | S (silent) | Sc (count) | Sk (kinds) |
|---|---:|---:|---:|---:|
| all four traps | 16/24 | 20/24 | 22/24 | 22/24 |
| json-property | 1/6 | 5/6 | 6/6 | 6/6 |
| column-h2 | 3/6 | 3/6 | 4/6 | 4/6 |
| config-key, ts-client | 12/12 | 12/12 | 12/12 | 12/12 |

count and kinds are no worse than silent and not separably better (2 runs of 24, inside the noise; not interleaved). Tokens and
turns against silent: inside stage P's per-config noise (composer count +13%, muse kinds -30%). count and kinds are the only modes with noise reads: muse read pushed files it then left alone (0.50 a
run under kinds, 0.25 under count, 0 under silent). On this evidence silent stays the default to recommend; count and kinds do
not earn a different one.

Files for stage P': TABLE-P2.md, rows-stageP2-M2-{O,S}.jsonl, runs-stageP2-M2.jsonl, runlog-stageP2-daybreak.txt, pconv.py,
chain5.sh (install with the fix, warm, run); count/kinds: TABLE-P2k.md, rows-stageP2k-M2-{Sc,Sk}.jsonl,
runs-stageP2k-M2.jsonl, runlog-stageP2k-daybreak.txt, chain6.sh.
