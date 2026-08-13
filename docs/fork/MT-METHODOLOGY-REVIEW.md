# Multi-threading methodology review — what is in this app, what it is worth, and how 1.1.1 measures it

Status: for comment. Owner: the fork maintainer. 2026-08-11.

The codebase contains three distinct parallelism mechanisms plus a set of strategy knobs
layered on one of them. Every claim below that carries a number was measured externally
(`/usr/bin/time`, per-thread sampling); every structural claim was traced in code. Nothing
here is taken from the engine's own logs alone — this application has repeatedly reported
work it was not doing.

## The mechanisms, compared

| mechanism | kind | state | measured |
|---|---|---|---|
| Racing (router) | redundant — N identical routers, keep best score | runs; width read from `router.max_threads`, NOT `optimizer.max_threads` (twin-name trap — our probes set the wrong one, so both "widths" raced ~15); unsound result | 411% CPU at both probe arms (identical configs); worse board (106 vias vs 104) at 2.7× wall |
| MT optimiser | cooperative — items rerouted on clones, wins merged | delivery fixed (defect 31); compounding still broken | 2.1× faster than ST at ~75% of its quality (100 vs 91 vias) |
| Router core | none — strictly single-threaded | no work-sharing mode exists | 126% CPU (one core + JVM housekeeping) |

## What is under the knobs (assessed, in the order of consequence)

### 1. GREEDY's compounding is structurally false — and the fix is one timing change

`OptimizeRouteTask`'s **constructor** does `job.board.deepCopy()`. All tasks are constructed
in the scheduling loop at pass start, so every task clones the ORIGINAL board before the
first win lands. Two of upstream's own comments are thereby false: "new tasks will copy the
updated board" (there are no new tasks after pass start) and "just enough tasks to keep
workers busy in order not to exhaust JVM memory" (everything is scheduled up front, one full
board clone each). As implemented, GREEDY ≈ GLOBAL_OPTIMAL with different bookkeeping:
zero intra-pass compounding, by construction.

**Proposed fix (slice 2 centrepiece): move the deepCopy from the constructor to `run()`,**
cloning the current master under the optimizer's monitor. Later tasks then build on earlier
wins — per-item-style compounding, parallel — and live clones drop from O(item count) to
O(pool width). ~10 lines. It also makes both upstream comments true.

### 2. Memory: the pressure upstream feared is real, undefended, and unmeasured by us

The constructor-clone design held ~245 full board clones alive simultaneously on the probe
board; the engine logged 862 GB of total allocation churn in one job. Upstream's comment
shows they KNEW — the defence just does not work. And our own probes never measured memory:
wall and CPU% only. Two consequences:

- **Every future MT measurement carries memory columns**: max RSS (`/usr/bin/time %M`) and
  the engine's own allocation totals. Churn and peak, both, from the first slice-2 run.
- **Proposed feature — a numeric memory budget.** The app should size itself like a good
  citizen: default ~60% of cores AND a numeric memory cap (settable; default derived from
  the machine, stated in absolute GB, never a silent percentage). At the cap it degrades —
  fewer concurrent clones — it does not exhaust its JVM or the host. The deferred-clone fix
  is a precondition: with O(pool) clones, capping concurrency IS capping memory.

### 3. PRIORITIZED ordering — keep it, and add its mirror as a separate strategy

`compareTo` ranks by after-state: fewest incomplete, then fewest vias first — i.e. it
prioritises items that are already good. Ruling: this stays as-is and gets measured as its
own strategy — the spirit is "push the best toward perfect". A second, flipped strategy —
most-to-gain first — is a genuinely different bet ("rescue the worst") and is added as a
separate selectable value, measured separately. Until defect 31 was fixed both would have
ordered by poisoned results; only now is either measurable at all.

### 4. HYBRID alternation — alive, currently measuring noise

Alternates update strategies per pass by the `N:M` ratio; parse errors are handled loudly.
Structurally sound, but both halves inherit defect 31's history and GREEDY's false
compounding, so any existing impression of HYBRID's value predates a working substrate.
Re-measured only AFTER fixes 1 lands.

### 5. Net-level recombination — BENCHED, decision recorded

The full "tiled cache" form — merging improved nets from concurrent results into a combined
board — is the theoretical ceiling for cooperative optimisation, and it is genuine
rebuild-from-the-ground-up territory: it needs net-level board surgery, conflict detection
between overlapping improvements, and a correctness story for clearance interactions
between merged nets. Ruling: NOT pursued. Deferred-clone compounding captures most of the
benefit inside the existing architecture. Recorded here so the idea is not re-litigated
from scratch next quarter.

## Testing plan for this phase (for comment)

Instrumentation baseline for every run below: wall, CPU% (`/usr/bin/time`), **max RSS
(`%M`)**, engine allocation totals, delivered vias/length/unrouted from the .ses, and log
evidence of which code path ran. No self-reported number stands alone.

- **Phase A — deferred clone.** Mechanism probe re-run on xESC2 (existing gate): vias must
  close from 100 toward ST's 91; wall must stay under ST; live-clone memory must drop
  O(items) → O(pool). Same board, same budgets, 3 reps.
- **Phase B — strategy matrix.** {GREEDY, GLOBAL_OPTIMAL, HYBRID 1:1} × {best-first,
  most-to-gain-first, SEQUENTIAL, RANDOM} on 2–3 boards of different sizes, 3 reps,
  counterbalanced order. Bounded at ~2 boards × 12 cells if wall cost explodes; dropped
  cells named, never silent.
- **Phase C — memory budget.** Big board (power-class) with the cap set deliberately low:
  the run must complete, degrade concurrency, log the degradation, and never exceed the
  cap. Then the same board uncapped for the cost-of-citizenship delta.
- **Phase D — racing** (separately scoped): width honesty, then the soundness question —
  why N× compute delivers a worse board than one attempt. Not before A lands; racing
  measurements on a broken-compounding substrate would date instantly.

Ordering rationale: A unblocks honest measurement of everything else; B is worthless before
A; C rides A's clone model; D is independent but cheaper to interpret once the optimiser
side is stable.


## The width curve (measured 2026-08-11, deferred-clone build, xESC2, rounds=400, 3 reps)

18 runs, one batch, interleaved by repeat, rep 1 flagged warm-up, per-thread sampling on
every run. Wall/CPU/RSS external (`/usr/bin/time`), quality from the delivered `.ses`.

| width | wall (median) | CPU | busy/requested | vias (band) | length |
|---|---|---|---|---|---|
| 2 | 234 s | 224% | 2.5/2 | **88-91** | **12.23-12.30 M** |
| 4 | 182 s | 341% | 4.0/4 | 92-93 | 12.42-12.55 M |
| 6 | 155 s | 432% | 5.2/6 | 91-92 | 12.59-12.77 M |
| 8 | 155 s | 550% | 6.1/8 | 91-92 | 12.57-12.59 M |
| 12 | 154 s | 766% | 8.7/12 | 90-92 | 12.85-13.05 M |
| 16 | 151 s | 914% | 11/16 | **94-95** | **13.12-13.13 M** |
| ST reference | 481 s | 105% | — | 91 | 12.31 M |

Independent grading anchors the connectivity end: width 2 = 7 unconnected (beats ST's
10, tight 7/7/7); width 4 = 11-12.

**Three conclusions:**

1. **Wall scaling saturates at width 6-8** — a hard ~152 s floor. Widths 12 and 16 buy
   nothing in time while burning double the CPU. The per-thread samples show both causes:
   workers go increasingly idle (11 of 16 busy at w16) AND per-busy-thread efficiency
   decays — pass-tail starvation, win-lock contention, and clone memory bandwidth. The
   engine cannot feed 16 workers.

2. **Quality degrades monotonically with width — compounding vs width is a measured
   trade, not a theory.** Length climbs from 12.25 M (w2) toward 13.12 M (w16), heading
   back to the old no-compounding 13.31 M; more width means more first-generation work.
   Independent unconnected confirms the same slope (7 at w2, 11-12 at w4).

3. **The 60%-of-cores auto-default is dead.** Optimiser width is **2 by default** (beats
   ST on every metric at half its wall), **6-8 as the opt-in speed cap** ("fast" mode:
   ~3.2x ST at ST-band vias with a stated length cost), and it must **never scale with
   core count** — on a 32-core machine, width 31 is strictly worse than width 2 in every
   column except CPU burned. Core auto-detection still matters for the CAP (never exceed
   cores), not for the target.

## Second opinion (an independent reviewer, 2026-08-11) — adopted, with one item held for the maintainer

Their review keyed to the sections above; adoptions are binding for execution:

**Added to every phase:**
- **Output SHA-256 per run.** Summary metrics could not distinguish geometrically different
  boards in their hands; only hashing caught it. Deferred-clone makes results
  scheduling-order-dependent BY DESIGN, so same-settings repeats will legitimately
  hash-differ — the hash measures the SPREAD. Three reps therefore give quality BANDS per
  cell, not points; a strategy whose band overlaps another's is not better, it is noise.
- **Independently-graded columns per cell** (their standing one-command offer). Instrument
  warning, measured on their side: grader DRC ERROR counts drift ±7 on identical input
  (hole_clearance the only drifting type); UNCONNECTED is run-stable everywhere. So
  independent unconnected is safe as a point; error-count deltas inside the drift band are
  not differences. This applies equally to the published comparison tables.
- **Phase-split walls** — route wall and optimise wall separately. The router core is ST,
  so total-wall speedups Amdahl-saturate and under-sell the optimiser; "optimise phase
  2.1x at N threads" is the defensible number.
- **Warm-up discard per configuration.** They measured 3x cold-start on a rust binary; JVM
  warm-up is worse. First run per cell discarded or flagged.

**Reordered: C (memory budget) runs BEFORE B (strategy matrix).** A matrix cell that
silently degraded concurrency under memory pressure measures the budget, not the strategy.
C establishes the behaviour; B then runs with the budget disabled or provably unhit.

**Memory hard rules (their container-doctrine additions):**
- The degradation event is a **first-class banked column** on every run, not a log line —
  a silently-degraded row is a different experiment.
- A budget below the single-task floor **refuses at start with the numbers named**
  ("budget 512 MB < one clone at ~730 MB on this board") — never degrades to zero workers
  and hangs. Refusal beats mystery.
- Degradation is deterministic: cap-hit drops the NEWEST pending tasks (LIFO), never lets
  GC pressure pick victims.
- Phase C runs one cell with the **JVM heap capped below the app's own budget**: the app
  must honour the OUTER limit gracefully too, because in containers the outer limit is the
  one that kills you.
- max-RSS + allocation columns are permanent on every run, not phase-C-only.

**Held for the maintainer — racing descope.** Their recommendation: the measured verdict
("worse board than routing once, at 2.7x wall, ignoring its width setting") already
answers redundant same-strategy racing; fix ONLY the width-honesty defect (respect the
setting or refuse loudly) and BENCH the soundness investigation, since the only defensible
future racing is DIVERSITY racing — different strategies competing — which phase B's
matrix explores better anyway. This narrows the maintainer's stated 1.1.1 scope (both fixes)
and therefore needs their ruling, not mine.

**Phase A bonus, accepted:** xESC2 is in their strand cohort with banked independent
baselines — phase A outputs go to them for free before/after grading.
