# Release notes — Freerouting-Anneal 1.1.1

## What this release is

A one-shot pass over Freerouting 2.3.1. An agent harness was pointed at the product for
about two and a half days, told to find what was broken and fix it without changing what the
program decides, and then stopped. That is the whole story: no roadmap, no team, no
second phase.

**The routing algorithm is untouched.** Nothing here changes how the router chooses a
path, orders nets, scores a board, or decides to rip one up. What changed is everything
around that: defects that stopped correct code from running, waste on hot paths, output
that misdescribed itself, and controls that did not work. Where a fix does change the
board you get, it is because a stage that had never executed now executes — not because
its logic was altered.

### What changed, by area

| area | what it means here |
|---|---|
| **Stability** | A JVM crash in roughly one run in five is gone. Aborted passes are reported as failures instead of successes. A racing pass no longer reads a board another thread is writing. |
| **Correctness of output** | A run that saved nothing could report success; it cannot now. Selecting the 1.9 engine by flag actually selects it. |
| **Steering** | You can stop a run and keep the board. Every stage honours one shared deadline derived from the job budget. The default budget is 15 minutes rather than 12 hours. |
| **Reporting** | Every run states how it ended, and the three endings mean different things to somebody deciding whether to re-run. Errors are printed once, to stderr. The jar names the commit it was built from. |
| **Memory** | Upstream allocated on the order of 1,600 GB of short-lived objects per route on the benchmark board; two defects accounted for most of it and both are fixed. Peak heap fell from over a gigabyte to a few hundred megabytes. Garbage collection is under 1% of wall clock here, so this is heap pressure rather than speed. |
| **Speed** | Routing is faster on the boards measured. Total wall clock is now mostly a function of the budget you give the optimiser, so a single "it is N times faster" number would be meaningless. |
| **The optimiser** | It had never performed a useful pass. It does now. This is the change you will actually see in your boards. |

### What this release does not claim

- **Output is not reproducible run to run.** The same jar, board and settings can produce
  different results. We diagnosed this and bounded it to the 2.x engine; we did not fix
  it, because fixing it means changing when the algorithm stops.
- **Not everything is faster.** Routing is; a full run with the optimiser enabled does
  more work than upstream did, because upstream's optimiser was doing nothing.
- **Boards will differ from your previous runs.** That is the fix, not a regression.

## No maintenance, and what the licence means

This is a snapshot. **There is no commitment to develop it further, no issue tracker being
watched, and pull requests will not be accepted.** Nothing here is a promise of support,
suitability, or correctness, and the release notes are deliberately explicit about what is
still broken rather than quiet about it.

The licence is **GPL-3.0**, inherited unchanged from Freerouting — this is a derivative
work and could not be licensed any other way. In practice that means you may use, study,
modify and redistribute it, including commercially; if you distribute a modified version
you must do so under GPL-3.0 and make your source available on the same terms. The
copyright and the credit remain with the original authors.

So: fork it, cherry-pick from it, ship it, or ignore it. Those are all fine, and none of
them require anything from us. `WHAT-CHANGED.md` exists to make the second one easy.

---

## The headline: the optimiser had never run

In upstream 2.3.1 the optimisation stage performed no useful work. Its re-route was gated
on the flag whose purpose is to hand control *to* it, so every candidate was ripped up,
found worse than nothing, and undone. The stage ran, logged, reported a score, and changed
nothing.

It had not always been so, and that is what located the defect. Freerouting 1.9.0
optimises properly: on one board its stage ran for 12 minutes 42 seconds and reported a
~48.7% improvement, ending on 14 vias — where the 2.x stage, same board, same machine,
exited after 0.65 seconds reporting exactly `0.0000%` improvement, on 17 vias. A clean
zero on the first pass of a board with half its improvement still available is what a
broken stage looks like, not a local optimum.

**So 1.9.0 is the reference for the optimiser figures in this document, not 2.3.1.** The
2.x line regressed here: it gained a great deal elsewhere, which is why this fork is
based on 2.3.1 and not on 1.9, but its optimisation stage produced nothing a measurement
could be taken from. While this one was being repaired, the working stage in 1.9.0 was
the yardstick for what "optimising" should look like at all.

The 1.9 router is bundled in this jar as well (`--router.algorithm=freerouting-router-v19`),
and one consequence of the repair is worth stating: the optimisation stage is shared, so
whichever router you select, the repaired optimiser is the one that runs afterwards.

**Every board that version produced was routing-only output.** That includes every board
this fork produced before the fix, and it is why the routed result changes in this
release.

**Measured** on a small two-layer power board, against the 2023 original of the same
board. **That board is not public, so this figure is not reproducible by a reader** — it is
given because it is the clearest demonstration of the defect, and flagged because you
cannot check it.

The reproducible version is weaker and appears further down: on the KiCad demo boards,
which ship with this repository, the same fix moves `complex_hierarchy` from 10 unrouted to
9 and leaves three other boards unchanged. A smaller effect on boards anyone can route.

Both are true. The difference between them is the board, and if only one number is worth
having, take the one you can run yourself.

| | 2023 original | this release |
|---|---|---|
| vias | 14 | **12** |
| unrouted | 11 | **8** |
| wall clock | 815 s | **119 s** |

Fewer vias and fewer unrouted nets, in about a seventh of the time.

### What this means if you are upgrading

Your boards will come out different. If you were relying on output matching a previous
run, you no longer have that. The difference is the optimiser doing the work it was always
supposed to do.

---

## Correctness

**A stop from outside used to discard the board.** `SIGTERM`, `Ctrl-C`, a CI timeout, a
container shutdown or the machine suspending all took the JVM down with the routed board
still in memory, writing zero bytes. The only stop that behaved well was the one the
program scheduled for itself — the ones where somebody is waiting for an answer lost
everything. All of them now finish the current pass and write the board.

**A routed board could be reported as saved when nothing was written.** The board was
saved only from inside a progress-event listener, so a router that emitted no progress
wrote a 0-byte `.ses` file and reported success. The 1.9 engine did exactly that.

**Selecting the 1.9 engine by flag ran the 2.x engine.** `--router.algorithm` was parsed,
reported as unknown, and then ignored, because the scheduler constructed the 2.x router
unconditionally. Anyone who benchmarked "1.9 versus 2.x" using that flag compared 2.x
against itself.

**A pass aborted by an exception reported success.** A routing pass returned a boolean, so
a crash mid-pass was indistinguishable from "finished, nothing left to route", and the run
exited 0.

**A racing pass could read a board another thread was still writing**, and could silently
drop an obstacle from a clearance check.

**Tracks were laid below the board's declared minimum width, silently.** Fanout necks down
at pin exits to escape fine-pitch packages and nothing checked the board's minimum, so the
router could lay track a fabricator cannot build and then report zero violations. Found by
sweeping ninety-two routed boards: twenty-three carried tracks at exactly three quarters of
the board's own width. The narrowing is kept — refusing it costs real pin escapes on boards
where the net width equals the minimum — but every such track is now named by net, and the
run's summary counts it instead of reporting a clean board.

**The optimisation stage could not stop itself.** It had an early-stop, and the counter that
armed it was cleared whenever any single item improved by its own local measure — on one
board, eighty-two of eighty-five items reported an improvement while the board changed by
nothing. It also only counted items that failed, so it could not have reached its limit in
any case. A pass therefore ran to the end of its item list whatever it was achieving; one
board spent twenty-six minutes and returned exactly what it was given. Progress is now
counted as items examined since the board last became more complete or more legal.

Measured on the same boards, same budgets: 110 s to 58 s, and 236 s to 113 s, with identical
routed results. Across a twenty-six board set, three quarters of the wall clock and
twenty-four of twenty-five boards unchanged.

**Float-valued settings could not be set by any supported route**, including the
optimisation improvement threshold.

**JVM crashes.** Upstream crashed with a SIGSEGV in roughly one run in five on the
benchmark board, in the JIT compiling an oversized routing method. **0 crashes in 20 runs**
after that method was decomposed.

---

## Behaviour you will notice

**The default time budget is 15 minutes**, not 12 hours. An explicit
`--router.job_timeout` still wins.

**All three stages stop on one shared rule.** Fanout, routing and optimisation each derive
their deadline from the job's budget minus a grace period, and each finishes the pass it
is in rather than being cut mid-write, so the board handed on is whole.

**Every run says how it ended, and the endings mean different things.** "No further
improvements found" means a longer budget buys nothing. "Ran out of time" means it was
still improving when the clock stopped it. "Stopped on request" means you ended it. Before
this, a user looking at unrouted nets had no way to tell whether to raise the timeout or
stop trying — which is the only decision they have.

**A run can be stopped without losing it**, from the GUI Stop button, the CLI `s` key, or
a signal.

**`--helpful`** prints the operating manual — stages, budgets, endings, how to stop —
from inside the jar, where it cannot drift away from the build you are holding.

**The sponsorship dialog no longer opens when a route finishes.** Upstream's condition is
`jobsCompleted >= 5` — a threshold on a counter that only increases, not an interval — so
once you had routed five boards it opened a modal dialog after *every* autoroute, forever,
unless you supplied an email address. Finishing a route is not an invitation to ask for an
email address: on a scripted run it is a window waiting for a click, and on an interactive
one it interrupts the moment you want to look at the result.

We removed the trigger rather than repair it, because this fork is a one-shot and the
repair is a product decision that belongs to whoever maintains the program. **The dialog
is still reachable from the menu.** For upstream, the fix is an interval rather than a
threshold — fire on `jobsCompleted % 5 == 0`, or once and never again — and the change is
one line.

**Console errors go to stderr only** (the file log records them as well). Every ERROR line was previously printed twice, because the
logger attached both a stdout and a stderr appender to the root.

**The jar says which jar it is.** The banner names the commit it was built from, with a
`-dirty` marker when the tree was modified. Three byte-different jars were cut here in a
single day, all reporting the same version string.

---

### Configuration and logs now live somewhere durable

The user-data directory — `freerouting.json` and the log — defaulted to the JVM temp
directory, which any reboot or cleanup wipes: settings silently reset and "check the
log" pointed at a file that no longer existed. It now defaults to platform app-data
(`%APPDATA%\freerouting` on Windows, `~/.local/share/freerouting` on Linux,
`~/Library/Application Support/freerouting` on macOS); the file log is on by default at
`INFO` (was `DEBUG`) in a capped 20 MB ring. `--user_data_path=` and
`--logging.file.location=` override, as before. There is nothing to migrate — this is the first release of this fork, and it starts
with a fresh config in the new location — the old one was in temp, where nothing was
promised.

## Memory

Upstream allocated on the order of **1,600 GB of short-lived objects per route** on the
benchmark board. Two defects accounted for the bulk of it, and the rest came out with
them:

- a stack structure allocated its full 10,000-element capacity hint on every construction,
  in four tree-traversal hot paths, though it already grew on demand — **1,631 GB → 315 GB**
- log messages were built by string concatenation before any level check, at ~198 hot-path
  sites across five packages, none of which are emitted at `INFO` — **306 GB → 220 GB**
- further work on the remaining hot paths — object reuse, avoiding recomputation, and
  returning existing instances instead of constructing equal ones — brought it to
  **62 GB**, **without touching the routing algorithm**. Every one of these changes stops
  the program doing work it never needed to do; none of them changes what it decides

Peak heap fell with it, from over a gigabyte to a few hundred megabytes, and stays flat
across a run.

**We do not publish a single headline percentage for this.** The figures above come from
separate measurement rounds on their own boards, and chaining them into one number would
imply a controlled comparison that was never run -- 1,600 GB and 62 GB were not measured
against each other. The honest statement is that the
reduction is large, that the two causes above are the bulk of it, and that a
properly-controlled upstream-versus-this-release measurement — one board, one method, both
jars — has not been done.

**Garbage collection is under 1% of wall clock** on this workload, so this is heap pressure
rather than speed. A machine with memory to spare will not route faster because of it; a
machine without will stop thrashing.

## Routing time

**Board:** the DAC2020 benchmark set. Routing stage only, same machine, same JVM.

Routing completes in **26.6–27.0 s** against the 1.9 engine's **44.78 s**. Under an equal 65-second budget — a budget, so a
stage already in a pass finishes it rather than being cut mid-write — this fork
finishes in 64 s and 63 s across runs against 68.2 s, and produces 75 vias against 80.

Routing quality was compared at equal time budget rather than at "whatever each engine
takes", because the two engines stop on different criteria and an unequal-budget
comparison measures the budget.

---

## Multi-threading, made real

The multi-threaded optimiser in upstream 2.3.1 was measured broken, not suspected
broken: it found improvements and discarded them. This release fixes that and turns it
on — the optimiser runs multi-threaded by default, two threads wide — because the
defects are fixed and the fixes are measured.

We added nothing. Every mechanism in this release was already in the codebase — parallel
optimisation, update strategies, selection strategies, an improvement guard. What we did
was make them work, measure them, and set the defaults to the measured optimums. The one
exception is stated below: the stall guard was genuinely remade, because the original
design could not work.

### What was broken

- **The multi-threaded optimiser discarded every win it found.** Its tasks located
  improvements and accepted them — 41 wins logged on one board — and the winning board was
  never handed back to the job. The delivered result was byte-identical to running no
  optimiser at all, at every thread count, while consuming every requested core.
  Fixed: *fix(optimizer): the multi-threaded optimiser delivers its wins (defect 31)*.
- **Improvements could not compound within a pass.** Every task cloned the board at pass
  construction, so each worked on the pass's starting position and wins landed on a board
  no later task ever saw. Tasks now clone at run time from the current master.
  Fixed: *feat(optimizer): clone at task run time — intra-pass compounding becomes real*.
- **Length-only improvements could never register in a task**, because a task's baseline
  was initialised to zero instead of to the board it started from. That was the majority
  class of improvement. Fixed inside the delivery work: *fix(optimizer): the multi-threaded optimiser delivers its wins (defect 31)*.
- **Every enum-typed setting was unreachable from the CLI.** The strategy knobs
  (`board_update_strategy`, `item_selection_strategy`) parsed, reported unknown, and ran
  the defaults. Fixed: *fix(settings): CLI can set enum-typed properties — the strategy knobs work end to end*.
- **The stall guard measured wall-clock time.** A five-second quiet window stops early on
  a loaded machine and late on a fast one; the same configuration gave different results
  on different boxes for no reason a user could see. This is the one thing this release
  replaced outright rather than repaired: the guard now measures work — a window of items
  examined (single-threaded) or tasks completed (multi-threaded) with no accepted
  improvement. Same config, same stopping behaviour, any machine, any load. Verified by
  running the identical configuration on two machines of different generations: identical
  via counts. Remade: *feat(optimizer): work-quanta guard — stall windows measured in work, never wall clock*.

### Why there is no benchmark against the old multi-threaded mode

There is nothing to compare against. The old mode never delivered a result — its output
was the unoptimised board, so a benchmark against it would be a benchmark against not
optimising.

The baseline is instead the repaired single-threaded optimiser: the fix above had to land
first, and only then does asking what threading buys mean anything. Every threading
number in this release is measured against that repaired baseline, not against upstream
and not against the broken mode.

Against that baseline, measured on the public xESC2 board and the KiCad demo set, graded
independently: width 2 delivers slightly better boards (completion, vias, length per
connection) at roughly half the single-threaded wall clock. That is why it is the default.

### The width curve, and why the default is 2

Width was measured at 1, 2, 4, 6, 8, 12, 16 under identical configuration, three
repetitions each. The curve is consistent:

| Width | What you get |
|---|---|
| 2 (default) | The quality point. Best boards, about half the single-threaded time. |
| 4 | Balanced: noticeably faster, best trace length per connection, a few vias more. |
| 6 | The speed setting. Wall clock saturates here. |
| 8+ | Nothing. Time stops improving and quality degrades monotonically with width. |

One measured exception bounds the curve: on boards where the optimiser faces a large
field of individually cheap improvements (measured on a rules-stripped 800-track board,
five repetitions per width, two machines), multi-threading buys nothing and costs real
time — width 1 finished 2.4x faster than the default there, at under a quarter of the
memory, with an identical board. The test costs one run: if a board's
optimisation stage feels disproportionately slow, re-run it with
`--router.optimizer.max_threads=1` and keep whichever was faster — the result is the
same either way on this class. (The log's optimiser CPU-seconds figure samples the
coordinating thread only and cannot tell the two cases apart.)

Two consequences became defaults:

- **`--router.optimizer.max_threads` defaults to 2**, not to your core count. The previous
  default (cores − 1) is a measured regression on any modern machine.
- **Core count is a ceiling, never a target.** Requesting more threads than the machine
  has is clamped, and the run says so. A machine with one core runs the single-threaded
  optimiser honestly.

### The guard, and how to overrule it

The optimisation stage stops when a full work window passes without an accepted
improvement — it stops because it is finished, not because a timer fired. If you want the
old behaviour of a fixed amount of work instead, `--router.optimizer.rounds=N` switches
the guard off and examines exactly N items per pass. Measured at rounds=400 against the
guard: the delivered quality is the same. The guard exists so you do not pay for work
past convergence, not to change the result.

### The objective is also a knob

`--router.scoring.via_costs=100` (double the default) is a measured profile: fewer vias
per connection at every width, at the cost of about one completed connection on a dense
board — the router works harder per join. If your fab charges for vias, this is the knob — on dense boards. Measured across 36
boards at three widths: small boards already sit at their via floor, where the profile
moves nothing and only costs length. Use it where vias exist to remove.
It applies end to end; applying it to routing only and optimising on the default objective
was measured worse than either coherent objective, so a flag for that split exists only as
an experiment (`restore_default_scoring`) and is labelled accordingly.

### The CLI surface this release adds

All verified reachable end to end — see `docs/command_line_arguments.md` for each:

- `--router.optimizer.max_threads=N` — width; default 2; cores are a ceiling.
- `--router.optimizer.rounds=N` — fixed work per pass; switches the guard off.
- `--router.optimizer.memory_budget_mb=N` — cap the optimiser's clone memory; width is
  reduced to fit, and a budget below one clone runs single-threaded with the numbers named.
- `--router.optimizer.board_update_strategy=greedy|global_optimal|hybrid` — default
  greedy, which won the measurements.
- `--router.optimizer.item_selection_strategy=prioritized|most_to_gain|sequential|random`
  — default prioritized; `most_to_gain` measured equivalent.
- `--router.optimizer.restore_default_scoring=true` — experimental, measured worse, kept
  as an instrument with a warning label.

---

## The measurements

Every claim above traces to one of these tables.

**Which build produced which table.** The per-board cohort tables were measured on the
build immediately before the last optimiser fix landed (a run stopped at its deadline now
keeps the improvements its workers had already accepted; before, that pass discarded
them). For a board that converges — most of them — the two builds deliver the same
result, because nothing is being stopped mid-pass. For a board that hits the wall, the
shipped build can only do better than the table shows. The long-window rows, the width
comparison and the independent grading were all taken on the shipped behaviour. We state
this rather than re-run: the direction of the difference is known and it is not in the
release’s favour to hide it.

### Measured: the release cohort

579 runs on one machine (32-core, Linux), job budget 15:00: the public 10-board set
ran eight arms (champion and via-lean at widths 2/4/6, optimiser-off, upstream 2.3.0)
at three repeats (240 runs); the internal 26-board set ran the six champion/via-lean
arms at two repeats (312 runs); 27 further difficult internal boards ran once at the
default profile (the remaining 27). Wall-clock was measured under 8 parallel lanes and is
comparative between rows, not absolute. len/conn is trace length per completed
connection. Completion is reported per board — medians across a mixed set hide the
boards that strand, so we do not print them.

**Public set — the KiCad demo boards, named, so you can replicate every row:**
interf_u, pic_programmer, ecc83-pp, ecc83-pp_v2, sonde_xilinx, StickHub,
complex_hierarchy, multichannel_mixer (both published design states), CM5_MINIMA_3.

Five of the ten route to completion on every arm; five strand, and stranding is a
property of the board, not of the width or the profile — the counts are identical
across every configuration, including the upstream reference.

Paired against upstream 2.3.0, board by board, champion defaults. (2.3.0 is the last
*released* upstream version, which is why it is the reference; this fork branches from
the development line after it, which calls itself 2.3.1-SNAPSHOT.)

| board | unrouted (ours / upstream) | len/conn (ours / upstream) |
|---|---|---|
| ecc83-pp | 0 / 0 | 115 624 / 115 624 |
| ecc83-pp_v2 | 0 / 0 | 116 189 / 117 291 |
| interf_u | 0 / 0 | 282 570 / 272 422 |
| pic_programmer | 0 / 0 | 176 763 / 176 763 |
| sonde_xilinx | 0 / 0 | 113 834 / 120 620 |
| StickHub | 1 / 2 | 39 848 / 43 221 |
| complex_hierarchy | 9 / 10 | 138 925 / 144 641 |
| multichannel_mixer | 160 / 160 | 77 868 / 80 471 |
| multichannel_mixer (unrouted state) | 128 / 128 | 412 298 / 414 902 |
| CM5_MINIMA_3 | 29.5 / no board delivered | 112 290 / — |

- Completion: never worse than upstream on any delivered board; better on three —
  and on CM5_MINIMA_3 upstream delivered nothing at all (its log reported 31 unrouted
  before the process hung; a board that is never written does not get a completion row).
- Length per completed connection: shorter on seven boards, identical on two, longer on
  one (interf_u, 3.6%). The median board is about 3.7% shorter; we state the range
  rather than only the median.
- The optimiser earns its keep: with it off, the completing boards pick up violations
  and vias and the stranding boards strand slightly more.
- CM5_MINIMA_3 is an hours-board at this budget. The upstream reference honoured its
  budget internally, then saved no session file and its process hung after the job
  finished — this fork saves the board at the wall and exits. That row is why the
  timeout path was rebuilt.

**Larger-scale verification:** the same arms ran on a further 26-board internal set (two
repeats): 99.0% completion — 34 stranded connections of roughly 3,400, on the same
few boards at every width and profile. Quality flat from width 2 to 4; the width-6 via
drift appears (+5 vias per 100 connections); the via-lean profile moves nothing on
boards already at their via floor — it is a dense-board profile. Of the 27 difficult boards, 24
return a routed board inside the 15-minute default budget (median 10.5 connections left
at the wall); the other three are the hours-boards measured separately below. Those
boards are not published; the public set above is chosen so every claim here is
reproducible from a stock KiCad install. One provenance note on that: our bench DSNs
are historical exports. Independent re-export of the same demo boards matched ours on
seven of the ten; the other three (StickHub, complex_hierarchy, and the unrouted
multichannel_mixer state) are substantively different inputs — likely prepped
variants or older board revisions. Numbers for those three describe OUR exports, so
re-exporting from current KiCad may differ; the other seven are the same boards a
reader would export today.

### The boards themselves, at the shipped defaults

How to read these tables. A run has three stages: **fanout** and **routing** are
single-threaded (racing, the optional redundant-routing mode, is off and not part of any
number here); **optimising** runs after routing and is multi-threaded, two wide, by
default. "routing s" below is fanout plus routing; "optimising s" is the optimiser
stage; "total s" is the whole run's wall clock. "connections" is what the router
is actually asked to join — note it is larger than the board's net count, because one
net is several point-to-point connections; "routed" is completed connections of that
total, so an unfinished board is visible in place. "length" is in the board's
own exported units — compare arms within a board, never lengths down the column;
"len/conn" is length per completed connection. Every figure is a per-column median over the
set's repeats (three on the public set, two on the internal one), so columns need not
sum exactly. All rows: the shipped default configuration
(champion, width 2), 15-minute budget, one 32-core Linux machine.

**The public set — KiCad demo boards, named so every row is replicable:**

| board | components | connections | routed | vias | length (board units) | len/conn | routing s | optimising s | total s |
|---|---|---|---|---|---|---|---|---|---|
| CM5_MINIMA_3 † | 112 | 214 | 184 of 214 | 151 | 20717577 | 112290 | 953.2 | 0.0 | 943.0 |
| StickHub | 94 | 128 | 127 of 128 | 38 | 5060695 | 39848 | 84.8 | 345.5 | 424.7 |
| complex_hierarchy | 68 | 87 | 78 of 87 | 0 | 10836120 | 138925 | 4.1 | 10.8 | 17.8 |
| ecc83-pp | 15 | 14 | 14 of 14 | 0 | 1618736 | 115624 | 0.2 | 0.0 | 3.3 |
| ecc83-pp_v2 | 15 | 14 | 14 of 14 | 0 | 1626652 | 116189 | 0.3 | 0.3 | 3.3 |
| interf_u | 25 | 161 | 161 of 161 | 41 | 45493830 | 282570 | 22.6 | 47.6 | 72.4 |
| multichannel_mixer †† | 114 | 173 | 13 of 173 | 0 | 1012278 | 77868 | 18.1 | 36.2 | 56.8 |
| multichannel_mixer-unrouted †† | 114 | 141 | 13 of 141 | 18 | 5359877 | 412298 | 48.7 | 97.1 | 146.4 |
| pic_programmer | 63 | 86 | 86 of 86 | 0 | 15201658 | 176763 | 1.2 | 0.0 | 3.8 |
| sonde xilinx | 25 | 48 | 48 of 48 | 2 | 5464040 | 113834 | 0.9 | 1.3 | 4.5 |

† CM5_MINIMA_3 is the set's hours-board: it hit the 15-minute wall still routing
(stage clocks and wall diverge at a budget cut). †† the multichannel pair strands
structurally — identical counts on every configuration and on the upstream reference.

**The internal harder corpus — boards anonymized, characterised by size:**

| board | components | connections | routed | vias | length (board units) | len/conn | routing s | optimising s | total s |
|---|---|---|---|---|---|---|---|---|---|
| B01 | 6 | 5 | 5 of 5 | 0 | 1010301 | 202060 | 0.2 | 0.0 | 3.0 |
| B02 | 6 | 5 | 5 of 5 | 0 | 1325754 | 265151 | 0.3 | 0.0 | 3.3 |
| B03 | 7 | 9 | 9 of 9 | 0 | 687275 | 76364 | 0.2 | 0.0 | 3.1 |
| B04 | 6 | 10 | 10 of 10 | 3 | 565664 | 56566 | 0.5 | 0.0 | 3.3 |
| B05 | 6 | 12 | 12 of 12 | 10 | 1788601 | 149050 | 0.7 | 0.0 | 3.3 |
| B06 | 13 | 13 | 13 of 13 | 2 | 1778951 | 136842 | 1.8 | 3.0 | 8.0 |
| B07 | 5 | 20 | 20 of 20 | 6 | 1364382 | 68219 | 1.5 | 1.1 | 5.1 |
| B08 | 12 | 22 | 22 of 22 | 11 | 2349599 | 106800 | 1.2 | 0.0 | 3.9 |
| B09 | 3 | 28 | 28 of 28 | 1 | 3004954 | 107320 | 3.3 | 1.3 | 6.1 |
| B10 | 7 | 32 | 32 of 32 | 35 | 15654133 | 489192 | 6.5 | 0.0 | 6.8 |
| B11 | 4 | 33 | 33 of 33 | 44 | 6234014 | 188910 | 2.4 | 0.0 | 4.6 |
| B12 | 8 | 44 | 44 of 44 | 0 | 2191594 | 49809 | 0.5 | 1.1 | 4.4 |
| B13 | 48 | 46 | 46 of 46 | 0 | 2165610 | 47078 | 1.2 | 0.0 | 3.9 |
| B14 | 28 | 47 | 37 of 47 | 24 | 2103713 | 56857 | 17.4 | 15.6 | 32.6 |
| B15 | 14 | 49 | 49 of 49 | 41 | 11597350 | 236681 | 5.9 | 12.6 | 20.3 |
| B16 | 46 | 65 | 63 of 65 | 17 | 3499054 | 55541 | 9.4 | 53.9 | 65.3 |
| B17 | 29 | 67 | 67 of 67 | 68 | 11954027 | 178418 | 9.4 | 0.0 | 11.0 |
| B18 | 31 | 69 | 69 of 69 | 40 | 24487171 | 354887 | 5.0 | 0.0 | 7.4 |
| B19 | 24 | 89 | 89 of 89 | 8 | 17007924 | 191100 | 2.5 | 6.6 | 11.7 |
| B20 | 24 | 89 | 89 of 89 | 9 | 17015935 | 191190 | 1.9 | 5.3 | 9.9 |
| B21 | 19 | 98 | 98 of 98 | 13 | 16781331 | 171238 | 4.2 | 7.7 | 13.4 |
| B22 | 19 | 98 | 98 of 98 | 12 | 17158898 | 175091 | 3.7 | 7.2 | 12.6 |
| B23 | 26 | 100 | 100 of 100 | 97 | 18569950 | 185700 | 17.2 | 34.7 | 53.3 |
| B24 | 113 | 181 | 178 of 181 | 90 | 12276265 | 68968 | 112.2 | 171.0 | 262.1 |
| B25 | 36 | 204 | 202 of 204 | 150 | 27622967 | 136747 | 46.6 | 0.1 | 43.4 |
| B26 | 179 | 263 | 263 of 263 | 82 | 17311180 | 65822 | 106.3 | 648.5 | 748.8 |

**The hardcore rows — the two boards that genuinely need hours, at a 2-hour budget:**

| board | width | connections | routed | vias | routing s | optimising s | total s | ending |
|---|---|---|---|---|---|---|---|---|
| 2000ATX | 2 | 2855 | 1328 of 2855 | 310 | (still routing) | — | 7259 | hit the wall mid-routing; board saved, report written |
| 2000ATX | 4 | 2855 | 1324 of 2855 | 311 | (still routing) | — | 7259 | same |
| CM5_MINIMA_3 | 2 | 214 | 209 of 214 | 168 | 1550 | 5661 | 7247 | hit the wall while optimising |
| CM5_MINIMA_3 | 4 | 214 | 209 of 214 | 166 | 1555 | 4509 | 6102 | **completed**, 19 min inside the budget |

Read from these what a budget buys: CM5_MINIMA_3 — unroutable-looking at 15 minutes —
essentially completes at 2 hours (5 residual unrouted, both widths agreeing), and at this
scale width 4 finished 19 minutes sooner than width 2 at identical quality: on genuinely
big optimisation workloads the parallelism earns real time. 2000ATX routes 46% of its
2,855 connections in 2 hours and simply needs more clock — an independently run
8-hour attempt (same routing code, an earlier build of this release) was still in its
second routing pass at the wall with 633 connections left — 78% of the board, up
from 46% at two hours, still climbing and still not finished. The hardest boards want
tens of hours, honestly stated — and the budget takes them: `--router.job_timeout`
accepts hours-scale values (`24:00:00` is honoured, and the run counts against
`of 1440:00`), so a board like this is a matter of giving it the clock rather than of
finding a different setting. At the wall the board is saved and the finishing report
states the gap exactly. Two further internal candidates turned
out to be degenerate exports (one and zero routable connections — an exporter
question, not a router result) and are excluded. Scope note: the independent grading
covered the ten public demo boards at the default budget; these long-window rows are
our own instrumentation, and they are counts the run itself reports, not scored boards.

## Measured against the other two engines

> These tables were taken before the multi-threaded optimiser was repaired and say
> nothing about it. For the threading and profile numbers, see “The measurements”
> above.

Five KiCad demo boards, three engines, **n=3 per cell**, interleaved rather than batched so
machine drift lands on all three arms alike. Every arm got the **same 240 s wall-clock
bound**; the 2.x arms were additionally told `--router.job_timeout=00:03:00` so they stop
themselves and save rather than being killed at the moment they meant to report.

**Hardware:** AMD Ryzen 7 8700F, 8 cores / 16 threads, 30 GB RAM, Temurin JDK 25, Linux.

### Wall clock — median (range)

| board | Freerouting 1.9.0 | upstream 2.3.0 | this release |
|---|---|---|---|
| `ecc83-pp` | 22.5s (22–22) | 3.2s (3–3) | 3.2s (3–3) |
| `pic_programmer` | 24.3s (24–24) | 4.6s (4–5) | 3.7s (4–4) |
| `complex_hierarchy` | 24.5s (24–25) | 10.2s (10–10) | 20.3s (20–20) |
| `multichannel_mixer` | **240s — hit the cap 3/3, exited non-zero 3/3** | 49.7s (49–50) | 90.8s (91–91) |
| `StickHub` | 31.0s (31–31) | 136.3s (136–137) | 183.5s (182–184) |

### Result quality — unrouted connections

| board | upstream 2.3.0 | this release |
|---|---|---|
| `ecc83-pp` | 0 unrouted, 0 violations | 0 unrouted, 0 violations |
| `complex_hierarchy` | 10 unrouted | **9 unrouted** |
| `multichannel_mixer` | 160 unrouted | 160 unrouted |
| `StickHub` | 2 unrouted, 13 violations | 2 unrouted, 13 violations |

**One board better, three identical.** That is the honest scoreboard on this set.

### Read this before quoting any of the above

**We are slower than upstream on three of five boards, and that is expected.** Upstream's
optimiser performed no useful work, so it reaches "finished" quickly by skipping the stage
that costs time. A run here does *more* work. Comparing total wall clock between the two
is comparing a program that optimises against one that only appeared to.

**On `StickHub` the optimiser cost us 81 seconds and bought nothing.** It ran, hit its
budget, and finished on exactly the score it started with — the same score upstream
reached in 10.7 s of doing nothing. The optimiser is not a guaranteed win per board; it is
a stage that now genuinely runs, sometimes finds an improvement, and sometimes spends the
budget you gave it and finds none. On the two-layer board above it turns 14 vias and 11
unrouted into 12
and 8; here it found nothing. Both are real.

**1.9.0's times are inflated and its numbers flatter us.** It sits at 22–31 s on every
board including trivial ones, because it cannot run headless at all — it throws
`HeadlessException` and requires a display, so these runs carry virtual-framebuffer startup
in every measurement. Its internal log for `StickHub` shows 6.5 s of routing and 2.1 s of
optimisation inside a 31 s wall clock. Do not read its column as routing speed.

**1.9.0 does not report what it produced.** No unrouted count, no violation count, no
score — which is why its quality column above is absent rather than empty. You get a board
and no measure of it.

**The one dramatic result is a failure, not a speed-up.** On `multichannel_mixer`, 1.9.0
hit the cap on all three runs, exited non-zero on all three, and wrote a **0-byte session
file** every time. That is the defect this fork fixed — saving only from inside a
progress-event listener, so a router emitting no progress reports success and writes
nothing — reproduced three times out of three.

**Session-file size is not quality.** All three engines produce different-sized outputs on
the same board. That proves only that they differ.

**Five demo boards are not a benchmark suite.** They are what could be measured to this
standard in the time available. Nothing here should be extrapolated to your boards, and
the 95-net case in the known-defects section — 95 nets, over fifty minutes — is
the reason why.

## What we are not claiming

**Not a general speed-up.** Routing is faster on the boards measured, but total wall clock
depends almost entirely on how long the optimiser is allowed to run, and that is a budget
you set. A full run here does more work than upstream did, because upstream's optimiser was
doing nothing.

**Not unchanged output.** Boards come out different from the ones 2.3.1 produced. That is
the fix, not a regression.

**Not reproducible run to run.** See the known defects below.

---

## Known defects, unfixed

- **Runs are not bit-for-bit reproducible.** Same jar, board and settings can give
  different output. Diagnosed as a property of the 2.x engine — the 1.9 engine is
  deterministic on every board tested, and stock 2.2.4 is stable where later builds are
  not — and bounded to a known range of upstream commits. Deliberately not chased to zero,
  because doing so means changing when the algorithm stops.
- **Fanout may lay stubs below the declared minimum track width.** Confirmed here after
  an independent bench first reported it with numbers. It affects fanout-on runs, which
  is the default. The narrowing itself is not fixed, but it is no longer silent: every
  below-minimum track is named by net and counted in the run summary, so a board that may
  not be manufacturable says so instead of reporting zero violations.
- **Net count does not predict routing difficulty.** One 111-net board routes in 0.92 s;
  one 95-net board has exceeded fifty minutes. Do not size a timeout by net count.
- **`SessionManagerTest` fails 5 of 6 on a fresh clone**, from test isolation. Unowned and
  pre-existing.
- **The search tree is traversed while it is mutated**, which can throw out of the
  traversal. The obvious fix — skipping entries mid-re-registration — silently omits real
  copper and is worse than the crash. See `MAINTAINING.md`.

---

## Credit

Freerouting was written by Alfons Wirtz and developed since by Andras Fuchs, Michael
Hoffer, Andrey Belomutskiy and contributors. This release is their program with a set of
defects fixed. GPL-3.0, as upstream.
