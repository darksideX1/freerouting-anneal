# What changed, and how to take it

A record of what changed in this fork and how to take any of it. It is a menu, not an
argument: one fix, several, or none.

Written for whoever is reading — a Freerouting maintainer, a contributor, a user, anyone
who forked either project. Nothing here is addressed to a person or about a person.

This fork is a snapshot: nobody here is maintaining it, nobody will chase anyone about any
of this, and there is no offence available to take. The Java package is still
`app.freerouting` and no file was renamed, specifically so that `git cherry-pick` applies
cleanly rather than conflicting on every line.

```bash
git remote add anneal https://github.com/darksideX1/freerouting-anneal.git
git fetch anneal
git cherry-pick <commit>
```

Commits are identified by subject line as well as hash, because the subject is the stable
handle: `git log --grep="<subject>"` finds it even if hashes differ.

**A note on tense, because it matters here.** Everything below describes
**Freerouting 2.3.1**, the commit this fork was taken from, in the **past tense**. None of
it is a claim about the current state of upstream: some of it may have been fixed since,
and that has not been checked here. Where something is still true of *both* codebases,
including this one, it says so explicitly.

This describes a version of a program, not the work of the people who wrote it. Every
defect listed sat in a codebase that has been maintained for two decades and does
something genuinely difficult; finding faults in it took a fork, a fortnight of machine
time and an outside reviewer, which is not the same as those faults being obvious.

---

## The multi-threaded optimiser, delivered

The rest of this document is the rest of the same pass. Everything here ships in
1.1.1; this fork has exactly one release.

| # | What it was | Commit |
|---|---|---|
| 12 | **The multi-threaded optimiser discarded every win it found** — improvements were located and accepted, and the winning board was never handed back to the job. Delivered output was byte-identical to no optimiser, at every width. | *fix(optimizer): the multi-threaded optimiser delivers its wins (defect 31)* |
| 13 | **Tasks cloned the board at pass construction**, so wins could not compound within a pass — every task improved the pass's starting position. Clones now happen at task run time from the current master. | *feat(optimizer): clone at task run time — intra-pass compounding becomes real* |
| 14 | **The stall guard measured wall-clock time**, so the same configuration stopped differently on different machines and loads. Remade to measure work: a window of items/tasks with no accepted improvement. Cross-machine verified to identical via counts. | *feat(optimizer): work-quanta guard — stall windows measured in work, never wall clock* |
| 15 | **Every enum-typed setting was unreachable from the CLI** — strategy knobs parsed, reported unknown, then ran defaults. | *fix(settings): CLI can set enum-typed properties — the strategy knobs work end to end* |
| 16 | **The optimiser had no memory discipline** — clone memory now lives inside a budget (default 60 % of heap); width reduces to fit, refusal below one clone is stated with the numbers. | *feat(optimizer): memory budget (phase C) + most-to-gain selector + strategy knobs made reachable* |
| 17 | **Width scaled with core count** (cores − 1), a measured regression. Default is now the measured quality point (2); cores are a clamp ceiling, never a target. | *feat(config): 1.1.1 defaults — width 2, cores as ceiling, governed MT shipped on both paths* |

## If only one thing is worth taking

**In 2.3.1, the optimisation stage performed no useful work.**

Its re-route was gated on the flag whose purpose is to hand control *to* it, so every
candidate route was ripped up, found worse than nothing, and undone. The stage ran, logged,
reported a score, and changed nothing — so every board that version produced was
routing-only output. It is fixed in this fork. Whether it is still present upstream is not asserted here.

On a small two-layer power board, against the 2023 original of the same board: **14 vias
and 11 unrouted in
815 s became 12 vias and 8 unrouted in 119 s.**

This one is **not a clean cherry-pick** — see [Not separable](#not-separable) below. It is
still the one to look at first, even if the cleaner path is reimplementing it in ten
minutes after reading the diff.

---

> Commits are named by their subject rather than by hash: this release is published
> as a single snapshot, so the development history the hashes belong to is not part
> of the published repository. Every subject below is greppable in the tree.

## Clean single-commit fixes

Each is one commit, independent of the others, with a failing-first test. Numbers 12-17
above belong to the same list; they are separated only because they share one subject.

| # | What it was | Commit subject |
|---|---|---|
| 1 | **A routed board could be "saved" as 0 bytes while reporting success.** The board was written only from inside a progress-event listener, so a router emitting no progress wrote nothing and exited 0. The 1.9 engine did exactly that. | `fix(scheduler): save the routed board instead of relying on a progress event` |
| 2 | **`--router.algorithm` was parsed, reported unknown, then ignored** — the scheduler constructed the 2.x router unconditionally. Anyone who benchmarked "1.9 vs 2.x" with that flag compared 2.x against itself. | `fix(router): honour the algorithm setting instead of hardcoding the engine` |
| 3 | **A pass aborted by an exception was indistinguishable from "finished, nothing left to route"**, because the pass returned a boolean. Crashes were reported as clean completions. | `fix(autoroute): a pass has three outcomes, not two` |
| 4 | **A thread interrupt was swallowed** in the multi-threaded optimizer — the restoring line was present but commented out, the only such site in the codebase. | `fix(optimizer): restore the thread interrupt status after await` |
| 5 | **The root log level made every level guard useless.** It was `ALL`, so `isTraceEnabled()` returned true forever and ~198 guarded hot-path sites built strings nobody read. | `fix(logging): the root level made every guard in the codebase a no-op` |
| 6 | **`ArrayStack` allocated its full 10,000-element capacity hint on every construction**, in four tree-traversal hot paths, though the class already grew on demand. | `perf(datastructures): treat ArrayStack depth as a hint, not an allocation` |
| 7 | **Log messages were built by concatenation before any level check** across five packages. At INFO none are emitted, so all of it was waste. This also shrank an oversized method enough to stop a JIT crash — see below. | `perf(board): stop building log messages the board package never emits` |
| 8 | **`IntOctagon.normalize()` constructed a new octagon even when nothing changed.** | `perf(geometry): normalize() returns this when nothing changed` |
| 9 | **`new Point[0]` allocated at 32 sites across 10 files**, mostly as a diagnostic argument. | `perf(geometry): share one empty Point array instead of allocating` |
| 10 | **Board-specific debug scaffolding evaluated for every user**: hardcoded net numbers, two reference-designator filters, and a room anchor pinned to four exact coordinates of one room on one board, checked on every `complete_shape` call. 17.2 KB across 7 files. Output byte-identical after removal. | `refactor(board): delete the hardcoded board-coordinate debug anchors` |
| 11 | **In 2.3.1 the sponsorship dialog opened after every autoroute, indefinitely.** The condition was `jobsCompleted >= 5` — a threshold on a counter that only increases, rather than an interval — so once a user had routed five boards, every subsequent route opened a modal dialog unless they supplied an email address. We removed the trigger; **the repair is an interval** (`% 5 == 0`) or a shown-once flag, a one-line change. The dialog remains reachable from the menu. | `fix(gui): no sponsorship dialog on autoroute finish` |

### A JVM crash fixed as a side effect

Upstream crashed with a SIGSEGV in roughly **1 run in 5** on the benchmark board — in C2
compiling `MazeSearchAlgo::expand_to_room_doors`, 1,250 bytes of bytecode. **0 crashes in
20 runs** after commit 7 above shrank that method. Unexplained crash reports on large boards are worth checking against this; it was never diagnosed as a logging problem.

---

## Not separable

One squashed commit bundles what, so these three arrived as one commit
(`fix: the optimiser has never worked (defect 25), plus defects 26/27...`, 32 files):

- **The optimiser performs no useful passes** (the headline above).
- **Float-valued settings cannot be set by any supported route**, including the optimiser's
  improvement threshold.
- **A racing pass can read a board another thread is still writing**, and can silently drop
  an obstacle from a clearance check.

Taking the optimiser fix alone means reading that diff and extracting the gate condition.
It is a small change inside a large commit. We would have split it had we known it would
be read this way; squashing it was our process, not a claim that these belong together.

Similarly, one commit bundles the 15-minute default timeout, the shared
stage deadline, board-saving on external stop, and the run-ending messages. Those are
behaviour choices rather than defect fixes, and different choices are entirely reasonable.

---

## Upstream-known defects, and which ones touch these numbers

`AGENTS.md`, inherited with the fork, records defects upstream was already tracking. Three
of them are present in this build, unfixed, and sit inside the published measurements:

- **Issue 093** — routing a board with a bottom-copper pour introduces clearance violations
  and logs an internal error mid-pass. Untouched here; `interf_u` in the demo-board tables
  is the board it is reported against, so that row measures the defect too. One thing did
  change: a pass cut short by an exception now reports itself as aborted instead of
  looking like a clean finish.
- **Issue 558** — KiCad's DSN export omits the copper-to-edge clearance, so the router may
  lay traces closer to the edge than KiCad allows and KiCad's DRC flags them on import.
  Not fixable downstream: the exporter has to write it. It biases every DSN-fed engine
  equally, including in any DRC-based grading of this one.
- **Copper pour void / isolation is not detected** — a foreign-net trace can island part of
  a pour while the net still reports as routed. Completion figures here are
  engine-self-reported, so this is a possible false clean in them.

Two others do not apply to this build. The note that
`BoardStatistics.clearanceViolations.totalCount` counts only outline violations describes
older code — at this fork point it already runs the full item-pair check. (The live trap
next to it is different: that statistics object takes a flag to skip the check entirely,
and a skipped check reports zero. Every violation figure published here comes from a path
with it on.) And `adjustPlaneAutorouteSettings()`, flagged as fragile for non-KiCad DSNs,
is a fallback this KiCad-exported corpus never exercised.

## What we looked at and deliberately left alone

Offered because knowing where the mines are is worth as much as knowing where the fixes
are. Unlike the section above, **these are present tense on purpose: each is still true of
this fork as well as of 2.3.1.** We looked, understood, and left them. Fuller reasoning in
`MAINTAINING.md`.

- **The search tree is traversed while it is mutated.** `MinAreaTree.remove_leaf` clears a
  leaf's fields before the replacement is inserted and the comparator dereferences them, so
  a concurrent removal throws out of the traversal. **The obvious fix is worse than the
  bug:** skipping entries whose object is null does not mean the obstacle is gone, it means
  the obstacle is mid-re-registration, so skipping omits real copper and the router draws
  through it — a silent clearance violation on a fabricated board, in place of a loud
  crash. We tried it, reverted it, and restored the crash.
- **Output is not bit-for-bit reproducible.** Same jar, board and settings give different
  results. The 1.9 engine is deterministic on every board tested and stock 2.2.4 is stable
  where later builds are not, which bounds the cause to a known range of upstream commits.
  Fanout's per-pin stopwatch is one source and not the only one. We stopped at diagnosis:
  fixing it changes when the algorithm stops, which changes results.
- **Fanout and routing are budget-bounded, not criterion-based** — they stop when the
  job's clock says so rather than on work done. (The optimiser is the exception since
  this release: its default guard measures work, not elapsed time.)
- **`BatchAutorouterV19` is not deleted**, though similarity analysis recommends it. It is
  wired into the interactive path and is the only in-tree reference implementation of the
  1.9 engine. Only its settings string was broken, and that is fixed.
- **~80 "dead code" findings are not acted on.** They are reflection-invoked entry points —
  JAX-RS filters, servlet listeners, websocket callbacks. The tool also flagged
  `Freerouting.main`, which is the tell.

---

## One thing that is not a fix, but is worth knowing

**Net count does not predict routing difficulty.** One 111-net board routes in 0.92 s; one
95-net board has run for over fifty minutes. If any heuristic, estimate or placement cost
in the codebase keys on net count or density as a proxy for difficulty, that pair is a
cheap falsification test.

---

## Provenance of the numbers

Figures above come from runs on one machine with the run count stated. Allocation
measurements used JFR `ObjectAllocationSample`, weight-summed, n=3, on a named board with
recorded pass count and heap settings; `RELEASE-NOTES.md` gives the configuration. One
earlier headline figure was withdrawn for having an unrecorded baseline, and that
withdrawal is documented rather than quietly dropped.

Where something is unverified, it says so. Where we could not reproduce a report, it says
that too.
