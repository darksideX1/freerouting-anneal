# Defect register — what was found, what was fixed, what remains

Every defect catalogued during this work, with its current status verified against the
code rather than recalled. Sources: our own measurement, a systemic code review, and a
simplification analysis.

**43 commits. 16 of 23 defects closed, 2 partially, 5 open by decision.**

---

## CLOSED (15)

| # | Defect | Evidence it is closed | Commit |
|---|---|---|---|
| 1 | `ArrayStack` allocated its full 10,000-element capacity hint on every construction, in four tree-traversal hot paths; the class already grew on demand and its `reset()` was called nowhere | allocation 1,631 → 315 GB (−80.7%) on bm01; failing-first test pins the contract | *perf(datastructures): treat ArrayStack depth as a hint, not an up-front allocation* |
| 2 | Log messages built by concatenation before any level check, across 5 packages (~198 call sites); at INFO none are emitted, so all of it was waste | 306 → 220 GB (−28%) with the final package; build-failing guard | *perf(board): stop building log messages the board package never emits* + earlier |
| 3 | `FRLogger.isDebugEnabled()` did not exist, so debug calls could not be guarded at all | API added alongside the guards | (with logging fix) |
| 4 | `--router.algorithm=freerouting-router-v19` was parsed, reported "Unknown", and then ignored — the scheduler hardcoded `new BatchAutorouter(job)`. **Anyone selecting v1.9 by flag ran v2 and compared it against itself** | `RouterFactory` + 4 selector tests; v1.9 verified dispatching | *fix(router): honour the algorithm setting instead of hardcoding the router* |
| 5 | The routed board was saved **only** from inside a progress-event listener, so a router that emits no progress wrote a **0-byte `.ses` while reporting success**. v1.9 did exactly that | 15 runs at 0 bytes → 24,870 bytes | *fix(scheduler): save the routed board instead of relying on a progress event* |
| 6 | JVM crash, ~1 run in 5: SIGSEGV in C2 compiling `MazeSearchAlgo::expand_to_room_doors` (1,250 bytes of bytecode) | **0 crashes in 20 runs**; fixed as a side effect of shrinking that method | (side effect of *perf(board): stop building log messages the board package never emits*) |
| 7 | `BatchOptimizerMultiThreaded` swallowed the thread interrupt — the restoring line was present but **commented out**, the only such site in the codebase | restored; build-failing guard | *fix(optimizer): restore the thread interrupt status after awaitTermination* |
| 8 | A routing pass returned a boolean, so an exception mid-pass was indistinguishable from "finished, nothing left to route" | `PassOutcome` tri-state; aborted passes now logged as errors | *fix(autoroute): a pass has three outcomes, not two* |
| 9 | `new Point[0]` allocated at 32 sites across 10 files, mostly as a diagnostic argument | shared `Point.EMPTY`; build-failing guard | *perf(geometry): share one empty Point array instead of allocating per call* |
| 10 | `autoroute_pass` ~260 lines at nesting depth 10, with diagnostics interleaved into routing and a counter block duplicated three times | decomposed; `.ses` byte-identical n=5 | *refactor(autoroute): separate diagnostics from routing in autoroute_pass* |
| 11 | `runBatchLoop` carried **dead state**: a `Set` declared and cleared twice, never populated or read, plus the commented-out logic it belonged to | removed; `.ses` byte-identical n=5 | *refactor(autoroute): remove dead state from runBatchLoop* |
| 12 | `Freerouting.main` (cyclomatic 163) also decided when to solicit sponsorship | extracted; full suite green | *refactor(cli): lift the sponsorship notice out of the routing entry point* |
| 13 | A 52-line debug dump fired whenever a net number equalled 94, walking every item on that net | deleted with the `autoroute_pass` decomposition | *refactor(autoroute): separate diagnostics from routing in autoroute_pass* |
| 14 | `is_complete_shape_debug_anchor` hardcoded **four exact coordinates of one room on one board**, evaluated on every `complete_shape` call for every user | deleted; 12 constructs removed | *refactor(board): delete the hardcoded board-coordinate debug anchor* |
| 15 | `IntOctagon.normalize()` constructed a new octagon even when nothing changed | returns `this` when unchanged; −4.5% allocation | *perf(geometry): normalize() returns this when nothing changed; guard keyed on content* |
| 16 | **Board-specific debug scaffolding**: hardcoded net numbers 33/66/67/84/98, two `"U27-"` reference-designator filters, and a room anchor pinned to four exact coordinates of one room on one board — evaluated on every `complete_shape` call for every user | **Fully removed**, not frozen: 18 blocks, 9 methods, 12 call sites, 4 locals, 17.2 KB across 7 files. Zero board-specific hits remain in `src/main/java`. `.ses` **byte-identical at n=3** against the pre-removal baseline on a board first verified deterministic at n=2 | (this commit) |

---

## PARTIALLY CLOSED (3)

| # | Defect | State |
|---|---|---|
| 17 | **CLOSED 2026-08-08.** Whole-pass `catch (Exception)` conflating crashes with normal partial results — 3 instances | Fixed in `autoroute_pass` and now in **`BatchAutorouterV19`**, which returns `PassOutcome` and reports `endedAbnormally()` truthfully instead of hardcoding false — so the partial-board protection can finally fire for the v1.9 engine. A separate silent `catch` that swallowed a per-item routing exception with no log at all is now recorded. **Still open, and now costed:** the third instance is not a bare boolean — `BatchAutorouter.autoroute_item` catches every exception and returns `AutorouteAttemptState.FAILED`, which is also the legitimate result for ordinary congestion. Closing it properly means a new attempt state, and `AutorouteAttemptState` has **41 usages and 18 state comparisons across 5 files**; any comparison left unhandled would drop that item out of the unrouted count, reporting a cleaner board than exists — worse than the conflation it fixes. Not attempted blind. Both item-level swallows now at least **name the item and net** (`describeItem` gives `J2-A3`), so a crash is attributable even though it is still counted as a routing failure. |
| 18 | **Disk hygiene**: normal runs verified clean; a crashed run drops `hs_err`, `replay` and a core dump into the user's working directory | **Closed as far as it can be.** The paths are fixed when the JVM starts, so no change inside the program can move them; `-XX:ErrorFile` / `-XX:ReplayDataFile` and the OS core-dump settings are now documented in `RUNNING-THIS-BUILD.md`. Moot in practice anyway since the SIGSEGV that produced the debris is fixed (0 in 20 runs). |

---

## OPEN BY DECISION (4)

| # | Defect | Why it is open |
|---|---|---|
| 19 | **Stage completion is timeboxed, not criterion-based.** `TimeLimit` aborts on wall-clock ms, not work done, across 4 call sites; `BatchFanout` gives **each pin** its own 10 s budget. This is the root of speed-sensitive results | Changing it changes when the algorithm stops — behaviour, not waste. Out of scope for a "leaner, same behaviour" pass. |
| 20 | **Nondeterminism.** Same jar, board and settings give different output. v1.9 is deterministic on all three boards tested; **this is a v2 property**. Fanout is *a* source (per-pin stopwatch), not the only one. The second source is **bounded to `v2.2.4..4a0ae4b7` (544 commits)** — stock `v2.2.4` is stable at n=8 on boards where this fork with fanout off is not, and fanout cannot be the variable because v2 at that tag has no fanout phase. Board-dependent: 1 of 3 boards stable in both | Diagnosed, bounded and documented; deliberately not chased to zero. Now bisectable in ~10 builds rather than open-ended. `docs/fork/VERSION-PROVENANCE.md`. |
| 21 | **`.normalize()` failures caught, logged and ignored** at 4 sites, each continuing with an un-normalised trace | Making the contract explicit is safe; changing the skip decision is not. Left for whoever owns the geometry. |
| 22 | **Remaining ~210 GB allocation** is immutable exact-arithmetic geometry in a flat profile, no site above ~9% | **RE-MEASURED 2026-08-08, and the premise was wrong.** JFR class breakdown on the current build found the largest allocator was not geometry at all: `byte[]` at 17.5% plus `String` at 3.3%, i.e. **20.8% was diagnostic string building** — because the root logger was `Level.ALL`, so `isTraceEnabled()` returned true forever and all ~198 guarded hot-path sites built strings nobody read (fixed, *fix(logging): the root level made every guard in the codebase a no-op*). Measured to standard, fixed work unit (1 pass, no truncation), n=3 per arm, both arms built from committed trees: **string share median 25.37% (range 24.59–27.14) → 6.74% (6.63–6.95)**, an 18.6 pp reduction against a worst-case spread of 2.55 pp. `.ses` **byte-identical across all six runs and both arms** (`ab3d7a01`), so the change is behaviour-free; bm01 with fanout off is established repeatable and usable as a canary. Also refuted: **`BigInteger` is 3.0% of allocation**, so a `long` fast path targets 3% and the arithmetic never needed touching. And **GC costs under 1% of wall-clock** (580 ms / 60 s), so allocation volume is a heap-pressure lever, not a speed lever. The ~210 GB headline is unreliable — the program's own "GB total allocated" reported 16.24, 0.01 and 0.00 GB for the same board across runs. Remaining churn is the immutable value objects the architecture notes always named (`IntPoint`, `IntVector`, `IntOctagon`), still flat. Original adoption note follows. — Still "inherent, not waste" in the sense that no single site dominates; that is a reason it is hard, not a reason to leave it. **The 210 GB baseline is STALE and must be re-measured before anything is changed**: it predates this fork removing 17.2 KB of debug scaffolding, including a room anchor evaluated on *every* `complete_shape` call for every user and trace strings built on hot paths. Re-measuring is free; changing exact arithmetic is not. **Guardrail:** this is a router whose output is fabricated into copper, so any change must preserve exactness *provably* — a long fast path with an exact fallback on overflow is acceptable because it changes the encoding, not the semantics; approximation of any kind is not. The layer already tiers `IntPoint`/`IntVector` against `RationalPoint`/`RationalVector`/`BigIntDirection`, and has **no long fast-path**, so the cheap ground may be value-object reuse in hot loops rather than the arithmetic itself. |
| 23 | **The search tree is traversed while it is mutated.** `MinAreaTree.remove_leaf` nulls a leaf's `bounding_shape`, `parent` and `object` **before** the replacement leaf is inserted, and `ShapeTree.Leaf.compareTo` dereferences `object` unguarded. A concurrent removal therefore throws `NullPointerException` out of `Collections.sort` and out of the traversal loops in `ShapeSearchTree` — intermittently, on both the GUI and headless paths | **Attempted and reverted.** Skipping leaves whose `object` is null looked like the fix and is not: a null object does not mean the obstacle is gone, it means the obstacle is mid-re-registration, so skipping omits real copper and the router draws through it — a silent clearance violation in a board somebody fabricates, in place of a loud crash. The crash is the safer failure and has been restored. A real fix synchronises traversal against mutation, which changes this tree's concurrency contract; that is a design change, not a patch, and is not attempted blind. See the note at the sort site in `ShapeSearchTree`. |

---

## NOT ACTED ON, DELIBERATELY

- **`BatchAutorouterV19` is NOT deleted.** A similarity analysis recommended removing it as an abandoned fork; it is wired into the interactive path and is the only in-tree v1.9 reference implementation. Only its settings string was broken, and that is fixed.
- **~80 "dead code" hits are NOT acted on.** They are reflection-invoked framework entry points — JAX-RS filters, servlet listeners, websocket callbacks. The tool flagged `Freerouting.main` itself as dead, which is the tell. Deleting them would remove the live API layer.
- **`BoardComparator.compare` (cyclomatic 137)** is flagged but untouched. It is the mechanism behind `compareBoardFiles` — the tool used to measure the very regression under study. Worth reading before trusting its output; not worth refactoring blind.
- **Duplicated helpers** across the autorouter family (6 pairs at 0.95–1.0 similarity) are left alone pending a decision on V19's future.

## Defect 25 — the optimiser performs no-op passes (was: "reports 0.0000% and stops")

**Status:** open, and RENAMED — the original title blamed the threshold, which turned out
to be innocent.

### ANSWERED 2026-08-09: it is not the threshold

With defect 26 fixed the threshold can be set to zero, so the optimiser keeps going. It was
given 100 passes and 62 seconds on the same board. Build *fix(settings): Float settings were unsettable, and the refusal named the wrong reason (defect 26)*, n=3:

| arm | wall | vias | trace length |
|---|---|---|---|
| ours, as shipped (stops after 1 pass) | 10 s | 17 | 2.504 |
| ours, threshold 0 — **100 passes, 62 s of optimiser** | 73 s | **17** | **2.504** |
| original 1.9.0 | 815 s | **14** | **2.273** |

**Sixty-two seconds of optimiser passes produced byte-identical output** — identical to the
single-pass run and identical across all three runs of the arm.

So the `0.0000%` was accurate, and the hypothesis that the optimiser merely gave up too
early is wrong. But the inference drawn here originally — that byte-identical output proves
the passes attempt nothing — does not hold, and it is worth keeping the correction visible.

The optimiser rips up an item, attempts to re-route it, and **undoes the whole thing when
the result is not an improvement**. A candidate that is attempted and rejected therefore
leaves the board byte-identical *by design*. Attempted-and-rejected and never-attempted look
exactly alike from the output, so no amount of staring at the board could separate them.
That is precisely why the next step below was instrumentation rather than more inference.

**What the instrumentation showed:** each pass examined **45 items and improved 0**. So the
passes were not idle — they were selecting candidates and failing to improve any of them,
the possibility dismissed above. The cause was one layer down: the re-route ran under a stop
check that was already true, because `is_stop_auto_router_requested()` returns true for the
state meaning *"the autorouter has finished, hand over to the optimiser"*. The re-route
executed **zero passes**, so the ripped-up item could not be restored, every result was
judged worse than the original, and every attempt was correctly undone.

The defect is therefore not "stops too soon" and not "does no work", but **"does the work
under a stop flag that forbids it"** — and it is the direct cause of this fork shipping a
board with three more vias and 9% more trace length than the 2023 original manages. Fixed by
making the ten in-class stop checks role-aware (`routingShouldStop(thread, isOptimizerReroute)`),
after which the same board reports **44 examined / 40 improved** and lands 12 vias against
the original's 14.

### Original evidence

**Found:** 2026-08-09. **Found:** 2026-08-09, decomposing a US-2 comparison against the original
freerouting 1.9.0 on a rules-stripped four-layer board from an independent bench.

Same starting board, same machine:

```
ours     Optimizer pass #1 completed in 0.59 s
         Stopping optimizer because the improvement in this pass (0.0000%)
         is below the threshold (1.00%).
         Optimization stage completed in 0.65 s.        -> 17 vias

1.9.0    Route optimization was completed in 12 minute(s) 42.24 s
         and it improved the design by ~48.66%.          -> 14 vias
```

Exactly `0.0000%` on the first pass is the part that reads like a defect rather than a
local optimum. A real optimiser on a board with roughly half its improvement still
available should find *something* in its first pass; reporting a clean zero suggests the
improvement metric is measuring the wrong thing, or the pass is not doing what its name
says.

Why it matters beyond quality: it silently flatters every wall-clock comparison we make.
A stage that exits immediately makes the total look excellent, and the resulting number
gets read as "our router is faster" when it partly means "our router does less". A 74×
figure derived from exactly this was withdrawn before it left the lane.

First step is to establish which of the two it is: instrument what the optimiser pass
actually changed on this board, and compare its score delta against an independent
measure (via count, total trace length) rather than against its own metric.


## Defect 24 — a pass aborted by an exception reports `COMPLETED` and exits 0

**Status:** open. **Found:** 2026-08-08, racing confirmation run on *docs(fork): racing measured — it loses by 51x, stays off*.

When an autorouting pass is ended by an exception, the pass is correctly treated as
aborted and the partially routed board is correctly not written over the good one — both
of those are working as intended. But the **job** then finishes in state `COMPLETED`, and
`CliOutcome` maps `COMPLETED` to exit code **0**.

Observed: two of three racing runs threw a `NullPointerException`, wrote a 4140-byte
`.ses` holding 195 unrouted items (the input board, nothing routed), reported
`finished with state: COMPLETED (195 unrouted, 0 clearance violations)`, and exited 0.

Why it matters: the exit-code contract exists so a script can tell a routed board from a
failure without parsing the log. Here it reports success for a run that routed nothing.
This is a hole in the contract this fork added, not an upstream defect, which makes it
ours to close.

Fix shape: an aborted pass must be visible in the terminal job state. `STOPPED_EARLY`
(exit 4) is the honest answer when some passes succeeded and one aborted; `FAILED`
(exit 1) when nothing usable was produced. Needs a failing test at the
`RoutingJobState`/`CliOutcome` boundary first — the existing tests cover the mapping,
not the abort path that reaches it.

## Defect 29 - the fanout stage lays stubs below the declared minimum track width

**Found:** 2026-08-09 by an independent bench on their bench, reported via an independent reviewer. Not found by us.

**Status:** OPEN, unverified here. Recorded with the reporter numbers rather than paraphrased.

With fanout ON, eleven tracks landed at **0.1874 mm against a 0.200 mm declared minimum** --
0.7496 of a 0.25 mm net width. The fanout stage appears to size escape stubs as a fraction of
net width with nothing clamping the result to the board minimum.

### Why it matters more than eleven tracks

The router emits the violation SILENTLY. Any measurement taken with fanout on is therefore
contaminated by DRC violations the run did not report -- which includes measurements taken by
people who never set the flag either way and do not know which default they got. That is a
larger set than the people who chose fanout.

Our own numbers are in that set. This fork has not audited which fanout default its runs used.

### What to check first

Whether the stub width is derived from net width without consulting the board rules at all,
or whether it consults them and the clamp is missing on one path. The first is a design
choice to revisit; the second is a bug with a one-line shape. They are distinguishable by
reading where the fanout stage computes its trace width.


## Defect 28 — a stop from outside discards the board instead of saving it

**Found:** 2026-08-09, while checking whether a run cut short still yields something usable.

**Status:** OPEN.

### What happens

Send `SIGTERM` to a routing run 60 seconds in — after auto-routing has finished and the
optimiser is working — and the process exits with **no `.ses` written at all**:

```
exited on SIGTERM
ses_written=NO  bytes=0
```

Everything the run had achieved is gone. Not a partial board, not the pre-optimisation result
that was already complete on disk in memory: nothing.

### Why this matters more than it looks

The job's *own* timeout is graceful. When `router.job_timeout` expires the stage logs
`Optimization stage completed with timeout` and the board is written — that path was built
carefully and works. Every *other* way a run ends does not:

- `Ctrl-C` in a terminal;
- a CI or supervisor timeout;
- container shutdown, or an orchestrator reclaiming the process;
- the GUI, which offers Cancel and has no Stop (same gap, different surface).

So the one stop that behaves well is the one the program schedules for itself, and every stop
a *user* can actually initiate loses the work. That is backwards: the user-initiated stop is
the one where somebody is waiting for an answer.

A partial board is a result. A run that has routed 161 nets and is polishing them has produced
something worth keeping even if it is interrupted at second 61.

### The shape of the fix

A shutdown hook that requests the same orderly stop the job deadline already requests, rather
than letting the JVM exit. The mechanism exists — `StoppableThread` already carries the
tri-state that lets a stage finish its current pass and hand back a whole board, and the
optimiser already knows how to end mid-stage and save. What is missing is wiring an external
signal to it.

It should also answer the Stop/Cancel distinction the GUI lacks: **stop** means finish the
current unit of work and keep the board; **cancel** means discard. Today every external stop
is a cancel, whether the user meant it or not.

### Note on how this was found

Two 30-minute board runs were killed by a harness whose cap equalled the job budget, so
neither could report. That was a harness error, but chasing it surfaced the real one: even
given the time, an externally stopped run has nothing to show for itself.


## Defect 27 — racing can silently drop an obstacle from a clearance check

**Found:** 2026-08-09, while auditing `ShapeSearchTree.overlapping_tree_entries_with_clearance`
for allocation waste (US-3). Not found by looking for a correctness bug, which is worth
noting: the census pointed at this method for its allocation and the defect was sitting in
the same twenty lines.

**Status:** FIXED, 2026-08-09, commit *fix: stop the clearance-candidate sort from discarding candidates (defect 27)*. The `TreeSet` is now a stable sort on
clearance alone, so nothing can be dropped whatever the ids do. Verified as an A/B on one
variable — jars built with and without the fix from the same tree — with power-b2 routing to
hash `0d2c0f549f` on both, twice each; board, autoroute, drc and fixture suites pass.

The allocation effect is deliberately **not** claimed as US-3 progress: removing the Set
removes a red-black node per candidate and a defensive copy per call, but it was not
measured, and an unmeasured allocation number is not a number.

### What happens

The method collects candidate obstacles into a `TreeSet<EntrySortedByClearance>` to sort
them by clearance. `EntrySortedByClearance.compareTo` breaks ties on `entry_id_no`, which
comes from:

```java
private static int last_generated_id_no;   // static. not volatile, not atomic.
...
++last_generated_id_no;
entry_id_no = last_generated_id_no;
```

That read-modify-write is unsynchronised and the field is **static**, so it is shared across
every thread and every cloned board. Racing mode runs N `BatchAutorouterThread`s that each
call into this method concurrently. Two threads can interleave and produce the **same**
`entry_id_no`. If those two entries also carry the same clearance, `compareTo` returns 0,
and a `TreeSet` treats them as the same element — so **one obstacle is silently discarded**.

A discarded obstacle is a clearance check that never happens. The router believes the space
is free, and the violation surfaces later as a DRC error, or not at all.

There is a latent single-threaded version too: lines 1126-1128 deliberately wrap the counter
to 0 at `Integer.MAX_VALUE`, so a long-lived session eventually collides with itself.

### Why it may matter beyond correctness

Racing is currently "wired, crash-free, and does not win". This is a mechanism by which a
racing thread could produce a board that is *wrong* rather than merely worse, and by which
two runs of the same input could differ. It should be ruled in or out before any racing
quality number is trusted — including the ones already recorded in PHASE3-MEASUREMENTS.md.

### The fix, and why it is also the US-3 fix

The `TreeSet` is a sorter wearing a Set's clothes: because the comparator is a total order
(clearance, then a unique id), it never actually deduplicates in the single-threaded case.
It only deduplicates when the id uniqueness breaks — i.e. exactly when it must not.

Replacing it with a list plus a **stable sort on clearance alone** is:

- **order-identical** in the single-threaded case — ids are assigned in insertion order, so
  ordering by (clearance, id) and stable-sorting by clearance give the same sequence;
- **immune to the race** — with no Set there is no dedup, so colliding ids cannot drop
  anything;
- **cheaper** — it removes one red-black node allocation per candidate per call, in the
  method the allocation census ranked second (~24%);
- **smaller** — `entry_id_no` and the static counter exist only to break ties inside the
  Set, and both become dead.

One change, three results. Note the shape of it: the allocation win and the correctness fix
are the same edit, which is the opposite of the tradeoff US-3 kept running into elsewhere.

### Test owed before the fix lands

A failing-first test that does not depend on thread interleaving: extract the
collect-and-sort into a package-private helper, force two entries to share an id (simulating
what the race produces), and assert every candidate survives. Red against the `TreeSet`,
green against the sorted list, and it stays meaningful afterwards as "ties are preserved,
in insertion order".


## Defect 26 — the optimiser improvement threshold cannot be set by any supported route

> **STATUS 1.1.1: FIXED.** `-oit P` (percent) and `--router.optimizer.improvement_threshold`
> (fraction) both reach the setting end to end since the Float-settings fix (PR #13) and the
> enum/CLI reachability work. Shipped default: `0.01` (1%). The account below is the
> as-found record.

**Status:** open. **Found:** 2026-08-09, while trying to give our optimiser a real budget
so it could be compared fairly against the original 1.9.0.

`BatchOptimizer` stops when `scoreImprovement < optimizer.optimizationImprovementThreshold`.
On an independent bench's that four-layer board our first pass measures `0.0000` against the default `0.01`, so it
stops after 0.65 s. With the threshold at 0 the comparison `0.0 < 0.0` is false and the
optimiser would continue — **so this setting is the only thing standing between us and the
experiment**, and it cannot be changed:

| route | result |
|---|---|
| `--router.optimizer.improvement_threshold=0` | refused: *Argument NOT applied (unknown settings property)* |
| `--router.optimizer.optimizationImprovementThreshold=0` | same |
| `-oit 0` (legacy parser in `GlobalSettings`) | not reached by the modern `CliSettings` path |
| `freerouting.json` in the working directory | not read at all — zero mentions in the run log |

`ReflectionUtil.setFieldValue` resolves by field name **or** `@SerializedName`, so
`optimizer.improvement_threshold` is genuinely resolvable. The argument is rejected before
it ever gets there, by a validator whose allow-list is narrower than the settings model it
guards. That is the defect: a setting that exists, is documented, is reachable by
reflection, and has no working way to set it.

Worth saying plainly: the refusal is **loud**, not silent — the ERROR line names the
argument and says "The run is not configured as requested". That guard is this fork's own
earlier work and it did its job. I twice reported the flag as silently ignored without
reading the log, which is the failure the guard exists to prevent, committed by the person
who benefits from it most.

### Localised, and two hypotheses ruled out

`--router.optimizer.enabled=true` resolves and works — it is passed in every run here and
the optimizer stage duly runs, and it does NOT appear in the refused count. In the same
namespace, at the same depth:

| argument | refused | effective threshold |
|---|---|---|
| `--router.optimizer.enabled=true` | no | (works) |
| `--router.optimizer.improvement_threshold=0` | **yes** | 1.00% |
| `--router.optimizer.optimizationImprovementThreshold=0` | **yes** | 1.00% |

So it is **not** a `@SerializedName` mapping problem (the Java field-name spelling is
refused too) and **not** a path-depth problem (`optimizer.enabled` is the same depth and
resolves). Both hypotheses were tested and both are wrong. The failure is specific to this
field, which is where a fix should start looking — `getFieldByNameOrSerializedName` against
`OptimizerSettings`, with a debugger rather than another guess.

Worth noting for whoever picks it up: `GlobalSettings.routerSettings` is marked deprecated
in favour of the settings merger, so even a resolving write may land somewhere the runtime
no longer reads. Establish which of the two is happening before changing anything.

Fix has two halves: make the field resolve (or make the refusal accurate), and establish
why the working-directory `freerouting.json` is not picked up on this path.

