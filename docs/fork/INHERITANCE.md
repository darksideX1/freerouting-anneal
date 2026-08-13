# Inheriting this fork — what it is, what changed, and how to check it

Written for someone who maintains upstream Freerouting, or has just cloned this, and has
been told that a downstream fork fixed things. It answers four questions in that order:
what this is, what changed, how to check any of it without trusting this page, and where
the traps are.

Everything here traces to a file in this repository or to the shipped jar's own output.
Where two sources in the repository disagree, this page says which two and does not pick a
winner — the list is `docs/fork/INHERITANCE-GAPS.md`.

If you have an hour, read **§7** first; it is the order the rest of this was written to be
read in.

---

## 1. What this fork is

Upstream Freerouting 2.3.1 (fork point `4a0ae4b7`), with the things that did not work made
to work and the defaults set to the measured optimums.

`RELEASE-NOTES.md` states the scope of the release in its own words:

> We added nothing. Every mechanism in this release was already in the codebase — parallel
> optimisation, update strategies, selection strategies, an improvement guard. What we did
> was make them work, measure them, and set the defaults to the measured optimums.

There is one exception, and it is stated as one: the optimiser's stall guard was replaced
outright rather than repaired, because it measured wall-clock time and therefore stopped
differently on different machines. It now measures work.

**The routing algorithm is untouched.** `README.md` and `RELEASE-NOTES.md` both say so, and
both qualify it the same way: where a fix changes the board you get, it is because a stage
that had never executed now executes, not because its logic was altered.

**The Java package is still `app.freerouting` and no file was renamed**, specifically so
that `git cherry-pick` applies rather than conflicting on every line. `WHAT-CHANGED.md` is
written as a menu for exactly that: one fix, several, or none.

### The one that matters most

In 2.3.1 the optimisation stage performed no useful work. Its re-route was gated on the
flag whose purpose is to hand control *to* it, so every candidate route was ripped up,
found worse than nothing, and undone. The stage ran, logged, reported a score, and changed
nothing — **every board that version produced was routing-only output**.

`DEFECT-REGISTER.md` records how that was established, and the correction it forced: the
optimiser undoes a rejected candidate by design, so attempted-and-rejected and
never-attempted look identical from the output. Instrumentation separated them — each pass
examined 45 items and improved 0 — and traced the cause to
`is_stop_auto_router_requested()` returning true for the state that means *the autorouter
has finished, hand over to the optimiser*. After the ten in-class stop checks were made
role-aware, the same board reported 44 examined / 40 improved.

Measured on a small two-layer power board against the 2023 original of the same board:
**14 vias and 11 unrouted in 815 s became 12 vias and 8 unrouted in 119 s.** That board is
not public. The reproducible version of the same claim is weaker and is stated as such in
`RELEASE-NOTES.md`: on the KiCad demo boards the fix moves `complex_hierarchy` from 10
unrouted to 9 and leaves three other boards unchanged.

This fix is **not a clean cherry-pick** — see §2.

---

## 2. What was broken, and the commit that fixed it

Both tables are `WHAT-CHANGED.md`'s, which is the file to work from if you are taking
individual fixes: it identifies commits by subject line as well as hash, because the
subject survives a rebase and the hash does not.

### Delivered in 1.1.1 — the multi-threaded optimiser

| What it was | Commit |
|---|---|
| The multi-threaded optimiser discarded every win it found — improvements were located and accepted, and the winning board was never handed back to the job. Delivered output was byte-identical to no optimiser, at every width. | *fix(optimizer): the multi-threaded optimiser delivers its wins (defect 31)* |
| Tasks cloned the board at pass construction, so wins could not compound within a pass — every task improved the pass's starting position. Clones now happen at task run time from the current master. | *feat(optimizer): clone at task run time — intra-pass compounding becomes real* |
| The stall guard measured wall-clock time, so the same configuration stopped differently on different machines and loads. Remade to measure work: a window of items/tasks with no accepted improvement. | *feat(optimizer): work-quanta guard — stall windows measured in work, never wall clock* |
| Every enum-typed setting was unreachable from the CLI — strategy knobs parsed, reported unknown, then ran defaults. | *fix(settings): CLI can set enum-typed properties — the strategy knobs work end to end* |
| The optimiser had no memory discipline — clone memory now lives inside a budget (default 60 % of heap); width reduces to fit, refusal below one clone is stated with the numbers. | *feat(optimizer): memory budget (phase C) + most-to-gain selector + strategy knobs made reachable* |
| Width scaled with core count (cores − 1), a measured regression. Default is now the measured quality point (2); cores are a clamp ceiling, never a target. | *feat(config): 1.1.1 defaults — width 2, cores as ceiling, governed MT shipped on both paths* |

A fourth defect was fixed inside the delivery work rather than separately: a task's
improvement baseline was initialised to zero instead of to the board it started from, so
length-only improvements — the majority class — could never register in a task at all
(*fix(optimizer): the multi-threaded optimiser delivers its wins (defect 31)*).

### Clean single-commit fixes, each with a failing-first test

Identified by commit subject in `WHAT-CHANGED.md`; the short form:

| What it was | Commit subject |
|---|---|
| A routed board could be "saved" as 0 bytes while reporting success — the board was written only from inside a progress-event listener, and the 1.9 engine emits no progress. | `fix(scheduler): save the routed board instead of relying on a progress event` |
| `--router.algorithm` was parsed, reported unknown, then ignored; the scheduler constructed the 2.x router unconditionally. Anyone who benchmarked "1.9 vs 2.x" with that flag compared 2.x against itself. | `fix(router): honour the algorithm setting instead of hardcoding the engine` |
| A pass aborted by an exception was indistinguishable from "finished, nothing left to route", because the pass returned a boolean. | `fix(autoroute): a pass has three outcomes, not two` |
| A thread interrupt was swallowed in the multi-threaded optimizer — the restoring line was present but commented out, the only such site in the codebase. | `fix(optimizer): restore the thread interrupt status after await` |
| The root log level was `ALL`, so `isTraceEnabled()` returned true forever and ~198 guarded hot-path sites built strings nobody read. | `fix(logging): the root level made every guard in the codebase a no-op` |
| `ArrayStack` allocated its full 10,000-element capacity hint on every construction, in four tree-traversal hot paths, though the class already grew on demand. | `perf(datastructures): treat ArrayStack depth as a hint, not an allocation` |
| Log messages were built by concatenation before any level check across five packages. | `perf(board): stop building log messages the board package never emits` |
| `IntOctagon.normalize()` constructed a new octagon even when nothing changed. | `perf(geometry): normalize() returns this when nothing changed` |
| `new Point[0]` allocated at 32 sites across 10 files. | `perf(geometry): share one empty Point array instead of allocating` |
| Board-specific debug scaffolding evaluated for every user: hardcoded net numbers, two reference-designator filters, and a room anchor pinned to four exact coordinates of one room on one board, checked on every `complete_shape` call. 17.2 KB across 7 files, output byte-identical after removal. | `refactor(board): delete the hardcoded board-coordinate debug anchors` |
| The sponsorship dialog opened after every autoroute indefinitely — the condition was `jobsCompleted >= 5`, a threshold on a counter that only increases rather than an interval. The trigger was removed; the dialog remains reachable from the menu. | `fix(gui): no sponsorship dialog on autoroute finish` |

The last one is the only place in this fork where the recommended repair differs from what
was done: `WHAT-CHANGED.md` names the one-line interval form (`% 5 == 0`) as the fix for a
maintained project, and removes the trigger here because the fork does not intend to make
product decisions.

### A JVM crash fixed as a side effect

Upstream crashed with a SIGSEGV in roughly **1 run in 5** on the benchmark board, in C2
compiling `MazeSearchAlgo::expand_to_room_doors` (1,250 bytes of bytecode). **0 crashes in
20 runs** after the logging-concatenation commit shrank that method. It was never diagnosed
as a logging problem — unexplained crash reports on large boards are worth checking against
this.

### Not separable

PR #13 was squashed, so three arrived as one 32-file commit
(`fix: the optimiser has never worked (defect 25), plus defects 26/27...`): the optimiser
gate above, float-valued settings being unsettable by any supported route, and a racing
pass reading a board another thread was still writing. Taking the optimiser fix alone means
reading that diff and extracting the gate condition — a small change inside a large commit.

---

## 3. The three stages, and which of them are multi-threaded

The pipeline is three stages in order, each deriving its deadline from **one** job budget
(`StageDeadline`: the job's budget minus a grace period), each finishing the pass it is in
rather than being cut mid-write.

| Stage | What it does | Threading |
|---|---|---|
| Fanout | escapes pins out of fine-pitch packages so routing has pads it can reach | single-threaded |
| Auto-routing | connects the nets | single-threaded |
| Optimisation | rips up and re-routes individual items, keeping only improvements | **multi-threaded by default, two threads wide** |

The jar states the same three lines under `--helpful`, which is the copy that cannot drift
from the build in your hand.

**There is no parallel router.** The only multi-core routing mode is racing, and racing is
redundancy rather than work-sharing: N attempts with identical settings, differing only in
the order items are tried (seeded per thread, so a race reproduces), best score kept. It is
opt-in (`--router.racing_enabled=true` with `--router.max_threads=N`), measured on every
board tried as the same or a worse result than a single attempt, and documented as an
option on the record rather than a recommendation. Two correctness defects in it were fixed
here — deterministic per-thread ordering seeds and memory-bounded copies — and the
algorithm was not tuned.

**`router.max_threads` and `router.optimizer.max_threads` are different settings.** Racing
reads the first, the optimiser pool reads the second. `MAINTAINING.md` records that this
twin naming cost a measurement round: probes set the optimiser knob while measuring racing,
so two nominally different widths were the same configuration. The names are historical and
both are honoured, each by its own stage.

### Why the optimiser default is 2

Width was measured at 1, 2, 4, 6, 8, 12, 16 under identical configuration, three
repetitions each, on the public xESC2 board with `rounds=400`
(`docs/fork/MT-METHODOLOGY-REVIEW.md`):

| width | wall (median) | CPU | busy/requested | vias (band) | length |
|---|---|---|---|---|---|
| 2 | 234 s | 224% | 2.5/2 | **88-91** | **12.23-12.30 M** |
| 4 | 182 s | 341% | 4.0/4 | 92-93 | 12.42-12.55 M |
| 6 | 155 s | 432% | 5.2/6 | 91-92 | 12.59-12.77 M |
| 8 | 155 s | 550% | 6.1/8 | 91-92 | 12.57-12.59 M |
| 12 | 154 s | 766% | 8.7/12 | 90-92 | 12.85-13.05 M |
| 16 | 151 s | 914% | 11/16 | **94-95** | **13.12-13.13 M** |
| ST reference | 481 s | 105% | — | 91 | 12.31 M |

Three conclusions are drawn there and carried into the defaults: wall scaling saturates at
width 6–8 against a hard ~152 s floor; quality degrades monotonically with width, because
more width means more first-generation work and less compounding; and width must never
scale with core count. Core count is a **ceiling** — a request above it is clamped and the
run says so. A one-core machine runs the single-threaded optimiser.

The optimiser also lives inside a memory budget, 60 % of the JVM maximum heap by default.
One clone per thread is the cost of width, so a tight budget reduces width to fit and each
reduction is a stated warning; a budget below the measured cost of a single clone is
refused with the numbers named and the stage runs single-threaded instead.

### Why the optimiser stops when it does

It stops when a full work window passes with no accepted improvement — a window of items
examined single-threaded, or tasks completed multi-threaded. A wall-clock window stops
early on a loaded machine and late on a fast one; the work window was verified by running
the identical configuration on two machines of different generations to identical via
counts.

`--router.optimizer.rounds=N` switches the guard off and examines exactly N items per pass.
Measured at `rounds=400` against the guard, delivered quality is the same. The guard exists
so you do not pay for work past convergence, not to change the result.

`README.md` records one boundary on this: the work window governs the default `greedy`
strategy. `global_optimal`, and the global phases of `hybrid`, publish only at pass end, so
they run the full scheduled item set and are bounded by the job budget and `rounds`
instead.

---

## 4. The settings model

`docs/command_line_arguments.md` gives the three layers most people need: **code defaults**
(the measured optimums this release ships) → **`freerouting.json`** in the user-data
directory → **command-line arguments** for the single run. Every `--x.y=z` option has an
identically-named key in the JSON file, so anything on that page can be made permanent by
editing it.

The full ladder is `docs/settings.md`, resolved by `SettingsMerger`, lowest priority first:

| Priority | Source | Class |
|---|---|---|
| 0 | Default Settings (hardcoded baseline) | `DefaultSettings` |
| 10 | JSON configuration file (`freerouting.json`) | `JsonFileSettings` |
| 20 | DSN file metadata | `DsnFileSettings` |
| 30 | SES file metadata | `SesFileSettings` |
| 40 | RULES file overrides | `RulesFileSettings` |
| 50 | GUI (interactive user changes) | `GuiSettings` |
| 55 | Environment variables (`FREEROUTING__ROUTER__*`) | `EnvironmentVariablesSource` |
| 60 | CLI arguments (`--router.*`) | `CliSettings` |
| 70 | REST API caller | `ApiSettings` |

One architectural constraint follows from that and is worth knowing before you edit
`RouterSettings`: its fields are deliberately nullable with **no default initialisers**.
`ReflectionUtil.copyFields()` copies a field only when the source's value is non-null and
differs from the Java language default, so a field initialised in the constructor would
make every source look like an explicit override and let a low-priority source win.

The user-data directory now defaults to platform app-data — `%APPDATA%\freerouting`,
`~/.local/share/freerouting`, `~/Library/Application Support/freerouting` — and moves with
`--user_data_path=`. It previously defaulted to the JVM temp directory, which meant
settings silently reset on any cleanup and "check the log" pointed at a file that no longer
existed.

### Legacy short flags

The short flags predate this fork; the long forms are the complete surface. From
`docs/command_line_arguments.md`, the mappings that are not obvious:

| Short | Long | Values accepted by the SHORT form |
|---|---|---|
| `-mt N` | `--router.max_threads=N` (racing width) | any number; acts only with racing enabled |
| `-us S` | `--router.optimizer.board_update_strategy=S` | `greedy`, `global` (= `global_optimal`), `hybrid` |
| `-is S` | `--router.optimizer.item_selection_strategy=S` | `sequential`, `random`, `prioritized` — **`most_to_gain` needs the long flag**; anything unrecognised falls back to `prioritized` |
| `-oit P` | `--router.optimizer.improvement_threshold=P/100` | percent here, fraction there |

**Two syntax families, and mixing them used to fail silently.** Long `--section.property`
settings take `=`; short single-dash flags take a space. A long flag given with a space was
historically parsed as two unknown arguments and ignored. It is now refused by name at
ERROR — verified against the shipped jar:

```
ERROR  Unrecognised argument form: '--router.job_timeout'. Long options require
       --name=value; the space-separated form '--router.job_timeout <value>' is NOT
       supported and would be silently ignored. This argument was NOT applied.
```

The run continues with the setting unapplied, so the ERROR line is the only signal. The
same class of failure is the reason `RUNNING-THIS-BUILD.md` tells you to grep every log for
`Unknown settings property`: exit code 0 does not mean your flags applied.

---

## 5. How to verify all of this yourself

Nothing above needs to be taken on trust. In rough order of cost:

### The jar identifies itself

The first line of any run names the commit the jar was built from:

```
INFO   Freerouting v1.1.1 (build 7e2e3ca6, build-date: 2026-08-12)
```

A `-dirty` suffix means the tree was modified when that jar was built, so it is not the
commit it names and nobody can reproduce it from that sha. `docs/fork/VERSION-PROVENANCE.md`
carries the incident that produced the rule — three byte-different jars cut in one day, all
reporting the same version string — and the rule itself: every published determinism,
performance or quality figure carries the commit, never the version string, plus the fanout
state.

### Build and test

```bash
./gradlew executableJar     # build/libs/freerouting-current-executable.jar
./gradlew test
```

`docs/developer.md` has the whole procedure and two warnings that cost time. `./gradlew
assemble` succeeds and produces a jar that is not runnable (`no main manifest attribute`).
And some tests read files Gradle does not track as inputs — `build.gradle`, the release
workflow, the KiCad manifests — so after editing one of those an incremental run reports
up-to-date and **silently skips them**; use `--rerun-tasks`.

**`SessionManagerTest` fails 5 of 6 on a fresh clone.** It is test isolation
(`globalSettings` is null when the class runs after others), it is unowned, it predates
this fork. If you see it on a clean checkout you did not break it. Everything else is
expected green.

The test tree is 173 source files. The ones that pin the claims in §2 and §3 by name:

| Claim | Test |
|---|---|
| the optimiser's re-route is no longer gated shut | `OptimizerRerouteGateTest` |
| a multi-threaded win is handed back to the job | `OptimizerDeliveryTest` |
| the work-window guard ends a pass, and `rounds` replaces it | `OptimizerPassLimiterTest` |
| what counts as progress (a completed connection or a removed violation, not a re-routed item) | `OptimizerProgressTest`, `OptimizerScoreProgressTest` |
| width is clamped to the machine, loudly | `SafeThreadCountTest` |
| clone memory stays inside its budget | `OptimizerMemoryBudgetTest` |
| all three stages derive one deadline | `StageDeadlineTest`, `OptimizerDeadlineTest`, `FanoutDeadlineTest` |
| `--router.algorithm` dispatches the engine it names | `RouterFactoryTest` |
| a pass has three outcomes, not two | `PassOutcomeTest` |
| the run's ending and its exit code agree | `CliOutcomeTest`, `JobTimeoutOutcomeTest` |
| the run report is written and matches the job | `FinalRunReportTest`, `UnroutedReportTest` |
| the banner names the commit, and the version does not drift across manifests | `VersionBannerTest`, `ShippedVersionTest` |

### Boards you can route

`fixtures/` holds 157 files, including `Issue508-DAC2020_bm01.dsn` — the DAC2020 benchmark
board upstream's own table cites, and the board `docs/fork/PHASE3-MEASUREMENTS.md` is
measured on. `scripts/benchmark/fixtures/KiCad_10_demos/` holds the KiCad demo boards used
for every publishable figure — `interf_u`, `pic_programmer`, `complex_hierarchy`,
`StickHub`, `ecc83-pp`, `multichannel_mixer`, `CM5_MINIMA_3`, with `video` and
`royalblue54L` among four carrying a `.dsn_disabled` extension that the benchmark scripts
skip. They are zeroized — every track and via removed, footprints, pads, zones, netlist and rules
untouched — and exported through the `pcbnew` Python API, because `kicad-cli` has no
Specctra export.

### The measurement documents, and the rules they were written under

`docs/fork/PHASE3-MEASUREMENTS.md` (the 1.0.x evidence base) and
`docs/fork/MT-METHODOLOGY-REVIEW.md` (the 1.1.1 multi-threading evidence base) carry every
number with its board, configuration and run count. Both are governed by the same five
rules, which are the ones to apply if you re-run any of this:

- **Fix the work, not the clock.** A deadline truncates arms at different points and makes
  repeatability unassessable.
- **Record the banner, not the filename.** One measurement round was invalidated by a
  `-dirty` jar and re-run.
- **Interleave arms.** Batched arms let machine drift look like effect.
- **A delta must exceed the within-arm spread** of both arms before it is a difference.
- **Establish the board's repeatability first.** `Issue508-DAC2020_bm01.dsn` with fanout
  off produced 1 distinct `.ses` across 3 full runs and is used as the canary; some boards
  are not repeatable, and on those a 1-of-N outlier is the engine, not the change.

`MT-METHODOLOGY-REVIEW.md` adds four more for anything multi-threaded: hash the output
per run (summary metrics cannot distinguish geometrically different boards), split the
route wall from the optimise wall (the router core is single-threaded, so total-wall
figures Amdahl-saturate and under-sell the optimiser), discard the first run per
configuration as JVM warm-up, and carry max RSS and allocation totals as permanent columns.

### What "independently graded" means, procedurally

It means a third party re-measures the delivered boards and the connectivity figure comes
from an instrument that is not the router itself. The procedure, not the tooling, is the
part that matters, and three properties of it are recorded:

1. **The router does not score itself.** `RELEASE-NOTES.md` states the standard for the
   published figures: "connectivity scored by an instrument that is not the router itself."
   This exists because the application has repeatedly reported work it was not doing —
   `MT-METHODOLOGY-REVIEW.md` opens by ruling out the engine's own logs as a sole source.
2. **The independent grading's error bar is published in this repository**
   (`MT-METHODOLOGY-REVIEW.md`): unconnected counts are run-stable on identical input;
   DRC error counts drift by ±7 on identical input, with one clearance type accounting
   for the drift. So an independent unconnected count is safe to quote as a point, and an
   error-count delta inside the drift band is not a difference.
3. **Three repetitions per cell, hashed outputs.** Each run's output is hashed and the
   repetitions give bands rather than points. (Which artifact the grading side consumes
   — the delivered `.ses` or a re-export — is stated nowhere in this set; recorded in
   `INHERITANCE-GAPS.md`.)

The root documents — `README.md`, `RELEASE-NOTES.md`, `WHAT-CHANGED.md`,
`MAINTAINING.md` — name no instrument, vendor or partner, and "independently graded" is
the phrase they use instead. If you want an equivalent grading arm, the requirement is a
DRC/connectivity checker you did not write and the router did not produce, plus a measured
drift band for every column you intend to quote.

---

## 6. Where the bodies are buried

`MAINTAINING.md` is the five-minute handover a maintainer would have given, and its
**Traps** section is the one that costs hours if skipped. `docs/fork/DEFECT-REGISTER.md` is
the per-defect record. This is the index into both.

### Open by decision — each was examined, understood, and left

- **The search tree is traversed while it is mutated.** `MinAreaTree.remove_leaf` clears a
  leaf's `bounding_shape`, `parent` and `object` before the replacement is inserted, and
  `ShapeTree.Leaf.compareTo` dereferences `object` unguarded, so a concurrent removal
  throws out of `Collections.sort` and out of the traversal loops in `ShapeSearchTree`.
  **The obvious fix is worse than the bug**, was tried, and was reverted: a null object does
  not mean the obstacle is gone, it means the obstacle is mid-re-registration, so skipping
  it omits real copper and the router draws through it — a silent clearance violation on a
  board somebody fabricates, in place of a loud crash. A real fix synchronises traversal
  against mutation, which changes this tree's concurrency contract.
- **Output is not bit-for-bit reproducible.** Same jar, board and settings give different
  results. Bounded rather than fixed, and the bound is the useful part — see below.
- **Stage completion is timeboxed, not criterion-based.** `TimeLimit` aborts on elapsed
  milliseconds rather than work done, across four call sites, and `BatchFanout` gives
  **each pin** its own 10 s budget. This is the root of most speed-sensitive behaviour, and
  changing it changes when the algorithm stops.
- **`normalize()` failures are caught, logged and ignored** at four sites, each continuing
  with an un-normalised trace. Making the contract explicit is safe; changing the skip
  decision is not.
- **Remaining allocation is flat.** After the two fixes below, no single site is above ~9 %,
  and the register records that the old ~210 GB headline is itself unreliable — the
  program's own "GB total allocated" reported 16.24, 0.01 and 0.00 GB for the same board
  across runs. GC is under 1 % of wall clock, so allocation volume is a heap-pressure lever,
  not a speed lever.
- **`BatchAutorouterV19` is not deleted**, though similarity analysis recommends it: it is
  wired into the interactive path and is the only in-tree reference implementation of the
  1.9 engine.
- **~80 "dead code" findings are not acted on.** They are reflection-invoked entry points —
  JAX-RS filters, servlet listeners, websocket callbacks. The tool also flagged
  `Freerouting.main`, which is the tell.

### The non-reproducibility bound

This is diagnosed and bounded, not fixed, and the bound is what makes it tractable:

- The **1.9 engine is deterministic on every board tested**, and stock **2.2.4 is stable
  where later builds are not**, which places the cause inside `v2.2.4..4a0ae4b7` — **544
  commits**, ~10 builds to bisect.
- **Fanout is one source and not the only one**: its per-pin stopwatch makes anything that
  changes machine speed change the routed result. `--router.fanout.enabled=false` is the
  reproducibility dial; it costs some quality and buys stability.
- **It is board-dependent.** Measured across two builds on three boards, one board was
  stable in both and two were not.
- One caveat is carried rather than buried: the two arms of that comparison were not
  verified to use byte-identical board files, so it is indicative until they are.

The trap the same document exists to prevent is worth repeating, because it invalidates
comparisons made in good faith: **"freerouting v2" names more than one engine.** Between
`v2.2.4` and `4a0ae4b7` an entire routing phase was added — `BatchFanout` exists only under
`src_v19/` at the earlier tag — so on a stock 2.2.4 jar `--router.fanout.enabled` is
rejected outright and a 2×2 experiment crossing fanout against anything else runs
fanout-free in all four cells and produces a clean-looking table that means nothing.

### Known broken, and not a surprise

- **`SessionManagerTest` fails 5 of 6 on a fresh clone.** Unowned, pre-existing.
- **Windows CI has a history of hanging.** `MAINTAINING.md`: two separate fixes for it are
  already merged and it has hung since; it was green on the release build, which is not the
  same as fixed. If it hangs for you, you have found the same ghost, not a new one. No
  symptom, job name or workaround is recorded — see `INHERITANCE-GAPS.md`.
- **Fanout necks down below the declared minimum track width.** Confirmed after a third
  party reported it: twenty-three of ninety-two routed boards carried tracks at exactly
  three quarters of the board's own width. The narrowing is **kept** — refusing it costs
  real pin escapes where the net width equals the board minimum, measured at twelve of one
  fixture's 157 SMD pins — and what changed is that it is no longer silent: every such
  track is named by net and the job summary counts it. This affects fanout-on runs, which
  is the default, and therefore contaminates fanout-on measurements including this fork's
  own.
- **Net count does not predict routing difficulty.** One 111-net board routes in 0.92 s;
  one 95-net board has run for over fifty minutes. If any heuristic, estimate or placement
  cost keys on net count or density as a proxy for difficulty, that pair is a cheap
  falsification test.

### Traps in the repository itself

From `MAINTAINING.md`, because each of these bites on a first push rather than later:

- **The tag is the button.** `create-release.yml` fires on any tag matching `v*` and
  publishes a GitHub release; `docker-release.yml` fires on a release being published.
  There is no separate publish step and no confirmation prompt. Two other workflows that
  published on every push to `master` have had their push triggers removed and are
  manual-only.
- **The CI matrix is Linux-only on purpose.** macOS bills at ten times a Linux minute and
  Windows at two. Platform coverage lives in `create-release.yml`, which still builds on
  Windows and macOS because the MSI and the DMG can only be produced there. Restoring the
  three-platform matrix is one line. Worth knowing first: when an account runs out of
  Actions minutes, jobs fail at scheduling — every check red within seconds, zero steps
  executed — which looks exactly like broken code.
- **Never commit a jar into the KiCad plugin directory.** `kicadPlugin` stages the current
  jar and produces the release zip; a jar in the tree makes the tree dirty, which makes the
  jar built from it report a build nobody can reproduce from the sha it names. The previous
  arrangement shipped a plugin two releases out of date.
- **The clone is large and cannot be made smaller.** Roughly half a gigabyte of committed
  plugin archives and over a gigabyte of history, inherited. Rewriting history to purge
  them would break the ability to cherry-pick these commits, which is the main reason the
  fork was published.

### Release ritual

1. `./gradlew test --rerun-tasks` — expect the `SessionManagerTest` failures and nothing
   else.
2. Bump `ext.publishInfo.versionId` in `gradle/project-info.gradle`. That single value
   feeds the jar banner, the KiCad plugin filename and the integration manifests, and
   `ShippedVersionTest` fails if any of them drift apart.
3. Commit, then tag `v<version>`.

Never ship a `-dirty` jar.

---

## 7. An hour of reading, in order

| Read | Why | Minutes |
|---|---|---|
| `README.md` — "If you are coming from Freerouting" | the two behaviour changes you will notice first | 5 |
| `WHAT-CHANGED.md` | the menu: every fix, its commit, and whether it cherry-picks | 15 |
| `MAINTAINING.md` — **Traps** and **Known broken** | the things that cost hours | 10 |
| this page, §3 and §4 | how the pipeline and the settings resolve | 10 |
| `docs/fork/VERSION-PROVENANCE.md` | before you compare any two freerouting measurements, including your own | 5 |
| `docs/fork/INHERITANCE-GAPS.md` | what the document set does not answer, so you do not go looking | 5 |
| `docs/fork/DEFECT-REGISTER.md` — the OPEN sections | the reasoning behind each thing left alone | 10 |

`RELEASE-NOTES.md` and `docs/fork/PHASE3-MEASUREMENTS.md` are reference rather than
reading: go to them for a specific number, with the board and run count attached.

---

## 8. What this fork does not offer

No maintenance, no issue tracker being watched, no pull requests accepted. It is a
snapshot, published once. The licence is GPL-3.0, inherited unchanged, because this is a
derivative work and could not be licensed any other way; copyright and credit remain with
the original authors.

Fork it, cherry-pick from it, ship it, or ignore it. None of those require anything from
anyone here, and `WHAT-CHANGED.md` exists to make the second one cheap.
