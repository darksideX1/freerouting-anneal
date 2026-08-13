# Phase 3 measurements

Every number here is a real run on `build c40ee50f` (banner recorded per run), on a board
whose repeatability was established first, reported as median + range. Nothing is inferred.

**Board:** `fixtures/Issue508-DAC2020_bm01.dsn` — the DAC2020 benchmark board upstream's
own table cites. Fanout OFF for every arm (the reproducibility dial; it costs some quality
and buys stability, and it must be identical across arms or the dial is measured instead of
the change).

**Repeatability, established before anything was trusted:** 1 distinct `.ses` across 3 full
runs. bm01 with fanout off is a usable canary. Some boards are not (defect 20, not being
fixed); on those a 1-of-N outlier is the engine, not the change.

---

## US-2 — "it finishes in reasonable time" — **PASS**

Target: wall-clock ≤ v1.9 on the same box. Engine is the only variable, arms interleaved
(not batched) so drift cannot masquerade as effect. n=3, 9-minute deadline.

| engine | median | range | spread | outcome | unrouted | violations |
|---|---|---|---|---|---|---|
| v1.9 | 543 s | 543–543 | 0 s | hit the deadline, all 3 runs | 5 | 0 |
| **v2 (ours)** | **90 s** | 90–90 | 0 s | **completed, all 3 runs** | 6 | 0 |

**v2 is at least 6× faster and finishes where v1.9 does not.**

Two things this does NOT say, stated because the numbers invite both errors:

- **543 s is a floor, not v1.9's time.** All three v1.9 runs were cut off by the deadline.
  Its true completion time is unknown and larger.
- **"5 vs 6 unrouted" is not a quality comparison.** v1.9's 5 comes from a truncated run
  that never finished. Comparing quality needs v1.9 allowed to complete.

This comparison was **not measurable before this fork**: `--router.algorithm` was parsed,
logged "Unknown", and ignored (defect 4), so anyone who ran it was measuring v2 against v2.

## US-2 against the ACTUAL original 1.9.0 — and the original is not deterministic

Everything before this compared v2 against our own v19 mode, which is this fork's
reimplementation of the v1.9 algorithm and not the program anybody else ran. This is the
real thing: `freerouting-1.9.0.jar` from the upstream v1.9.0 release (2023-10-30, sha256
`9084a488…3102`), n=3, arms interleaved, 1-minute load average captured before every run.

Our arm: `build 9c82ba6d`. Board: a rules-stripped four-layer board from an independent bench.

| | wall median | range | distinct `.ses` over 3 runs |
|---|---|---|---|
| ours (v2) | **11 s** | 10–12 | **1** |
| original 1.9.0 | **815 s** | 792–842 | **2** |

**74× is the number the USER experiences, and it stands.** They drop a board, press the
button, and wait: one tool returns in 11 seconds, the other in 13.5 minutes. The user
cannot see that twelve of those minutes are an optimiser — they see a progress display
moving things about and assume it is working. End-to-end button-press-to-result is what
describes their experience.

An earlier revision of this section withdrew the figure outright, replacing a product
claim with an engineering one and calling the product claim wrong. That was an
over-correction. Both numbers are true and they answer different questions, so both are
recorded here rather than one being allowed to stand in for the other:

| question | answer |
|---|---|
| how long does the user wait? | **74×** — 11 s vs 815 s |
| how much faster is the routing engine? | **4.3×** — 6.98 s vs 30.30 s |
| is the resulting board as good? | **no — measurably worse**: 17 vias vs 14, and 9% more trace length |

The third has now been measured, and "comparable quality" was doing work it had not
earned:

| | wires | vias | segments | trace length |
|---|---|---|---|---|
| ours — 11 s | 56 | **17** | 131 | **2.50** |
| original — 815 s | 88 | **14** | 169 | **2.27** |
| original, run 3 | 60 | **14** | 143 | **2.27** |

The original's twelve minutes buy **18% fewer vias and 9% less trace length** — the two
quantities that cost money to manufacture and affect signal integrity. The boards are
close, but theirs is genuinely better and this fork's is not "comparable" in the sense the
word implies.

**This makes defect 25 the highest-value item in the phase.** Our optimiser declined, in
0.65 s and with a self-reported 0.0000%, improvement worth ~9% trace length and three vias.
An optimiser that captured most of that in ten seconds would deliver the original's quality
at roughly 20 s against its 815 s — which is a better product story than either speed
figure on its own, and a far better one than shipping a worse board quickly.

Decomposed:

| phase | ours | original 1.9.0 |
|---|---|---|
| routing | **6.98 s** | **30.30 s** |
| optimising | **0.65 s** | **762 s** (12 m 42 s) |
| optimiser verdict | *"improvement 0.0000%, below 1.00% threshold — stopping"* | *"improved the design by ~48.66%"* |
| final vias | 17 | **14** |

**~4.3× is the routing-engine comparison** — 6.98 s against 30.30 s, same board, same box.
The remaining gap is our optimiser declining twelve minutes of work the original does, and
that work is not nothing: it is why the original's board carries 14 vias against our 17.

What was actually wrong in the first write-up was the phrase "at comparable quality", which
asserted something never measured. The speed figure was fine.

### The finding that matters more: our optimiser gives up where 1.9.0 finds ~49%

On this board our optimiser ran ONE pass, measured **0.0000%** improvement, and stopped
against its 1.00% threshold. The original extracted **48.66%** from the same starting
board over twelve minutes.

That is not a tuning difference, and it cuts both ways — which is why it is worth
resolving rather than assuming:

- if our optimiser has genuinely **converged**, then twelve minutes for three vias is a bad
  trade and 11 seconds is simply the better product
- if our optimiser is **broken**, `0.0000%` is a lie and there is quality available to us in
  SECONDS that the original spends twelve minutes buying

Either answer is worth having, and a scoring function reporting *exactly* 0.0000% while
roughly half the available improvement is on the table makes the second more likely.
Registered as defect 25.

It also reframes the phase. US-2 asks for speed without changing what is produced; an
optimiser that exits immediately is fast precisely BECAUSE it produces less. Any future
wall-clock comparison against 1.9.0 must report the optimiser's own verdict alongside the
time, or it is comparing a full run against a partial one.

### The original 1.9.0 is NOT deterministic

**Two distinct outputs across three runs.** Run 3 produced 60 wires where runs 1 and 2
produced 88. Ours produced one hash, three times, on the same board and the same box.

**Narrowed, on measurement:** those differing runs carry **identical via counts (14) and
identical total trace length (2.27)**. The original is not producing different-quality
boards run to run — it is segmenting the same electrical result differently. "1.9.0 is
nondeterministic" is therefore true of its output bytes and NOT of its result quality, and
the weaker claim is the one that should be repeated. Byte-level nondeterminism still
defeats using it as a canary, which is the property this fork cares about.

This changes who owns the problem. Engine nondeterminism (defect 20) has been discussed in
this fork as our engine's failing; the 2023 original has it too, so it is inherited rather
than introduced. It also means any single-run 1.9.0 reference figure — including the ones
this fork has been comparing itself against — is one sample from a program that does not
give the same answer twice. an independent bench labelled theirs REPORTED STATE rather than measurements,
which was more correct than either of us realised at the time.

### Conditions, recorded rather than claimed

Load average before each run: ours 0.94 / 5.75 / 2.72, original 1.18 / 5.67 / 2.92. The box
is shared and also hosts the CI runner, so runs were interleaved specifically to make any
drift hit both arms rather than one. The 1.9.0 arm's 50 s spread across 792–842 s is small
relative to the 74× gap; no reading here depends on the box having been quiet.

### The original cannot run headless at all

Established separately and worth keeping next to the number: with
`-Djava.awt.headless=true` the original throws `HeadlessException`, throws again inside its
own exception handler, and writes no `.ses`. It only produced these numbers under
`xvfb-run`. **US-5 "no display required" is therefore demonstrated against the original
rather than asserted** — and any 1.9.0 figure produced on a headless machine did not come
from this program.

## The allocation ceiling: ~5-8%, so US-3 should be CLOSED rather than deferred

Before choosing between a geometry representation rewrite and object reuse, the question
worth answering is how much either could possibly win. Measured rather than estimated,
bm01, n=3 per arm.

| heap | wall median | range | GC total |
|---|---|---|---|
| `-Xmx2g` | 96.6 s | 96.6-97.5 | 1429 ms |
| `-Xmx6g` | 93.0 s | 92.5-102.1 | 825 ms |
| `-Xmx24g` | **91.5 s** | 91.5-92.5 | **626 ms** |

Twelvefold more heap collapses collection frequency (573 collections at 6g, 419 at 24g)
and buys **5.1 s, or 5.3%**. GC itself is under 1.5% of the run at any heap size.

Independently: this machine sustains **30.6 GB/s** single-threaded writes, so merely
ZEROING the 180 GB allocated costs at least **5.9 s** -- about 6.4% of a 92 s run. That is
a floor no allocation strategy can go below while the same objects are still created.

**Everything allocation-related is worth ~5-8% of wall-clock.** That is the entire prize.

### What follows

- A geometry **representation rewrite** would risk the exact-arithmetic invariant and the
  byte-identical-output property -- the two properties this fork's credibility rests on --
  to compete for at most 8%. The goal closes it, and the measurement says the goal is right.
- **Object reuse** (the defect-22 shape) chases the same 8% and can plausibly go NEGATIVE:
  pooling defeats escape analysis, and turning short-lived objects into long-lived ones
  promotes them to old gen, which makes collection worse rather than better.

Recommendation: **close US-3 as done**, recording that the remaining 180 GB is geometry
churn worth at most ~8%, and that both routes to it cost more than they can return. That
turns a deferred item into a decided one with a number behind it.

The prize is elsewhere: defect 25, where the optimiser reports 0.0000% improvement and
stops on a board from which 1.9.0 extracts 48.66%. That is not an 8% question.

## US-3 re-measured after the logging work: it bought NOTHING

The question was how much the guard removal and trace deferral cut allocation. Measured,
n=3 per arm, bm01, fanout off, full uncapped runs, both arms built from committed trees.

| arm | total allocation | wall |
|---|---|---|
| *fix(logging): the root level made every guard in the codebase a no-op* before the F-work | **180.2 GB** (179.8–187.1) | 104 s (103–106) |
| *fix: four codex findings on #7 -- two of them reverse decisions I defended* after F1/F3/F4/F13 | **179.6 GB** (179.5–187.8) | 100 s (98–101) |

**0.6 GB apart — 0.3% — against a within-arm spread of 7–8 GB.** The difference is an
order of magnitude smaller than the noise. There is no measurable allocation improvement.
Wall-clock moved ~4%, which is at the edge of its own spread and not worth defending.

`.ses` was `ab3d7a01` on both arms across all six runs, so the work is behaviour-preserving
— which was the other thing worth knowing.

### Why, and it should have been predicted

The root-logger fix at *fix(logging): the root level made every guard in the codebase a no-op* had ALREADY taken the win. Once the level is genuinely
off, the old guards were already preventing the string building; deleting them changed
what happens when logging is ON, not when it is off. The F4 DRC recomputation likewise sat
behind a guard that was already false, so moving it into a supplier is better engineering
that buys nothing in the default configuration.

The review report said exactly this before any of it was written: *"steps 4 and 6 are
maintenance-and-correctness changes wearing a performance costume, and should be sold that
way."* It was right and the expectation of movement was wrong. Recorded because the
prediction was available in advance and ignored anyway.

### The metric, corrected

Two JFR metrics were tried and they disagree by three orders of magnitude:

- **`jdk.ObjectAllocationSample` weight sum** — 180.16 GB, reproducing the recorded
  179.4 GB. This is the metric behind the baseline and it is sound.
- **`jdk.ThreadAllocationStatistics`** — 0.20 GB, and wrong for this purpose. The event is
  emitted per chunk, and every capture here was timestamped ~5 s into a 105 s run, so it
  reports "allocation so far at startup" rather than a total.

Anyone re-deriving these numbers should use the sample-weight sum, and should not be
reassured by the exact-sounding name of the other event.

### Where the 180 GB actually is — and it is not strings

Top allocating classes on the current build, 1 pass:

| | class | samples |
|---|---|---|
| 1 | `IntPoint` | 340 |
| 2 | `IntOctagon` | 233 |
| 3 | `Object[]` | 164 |
| 4 | `TreeMap$Entry` | 147 |
| 5 | `FloatPoint` | 133 |
| 6 | `byte[]` | 129 |
| 8 | `Line` | 125 |
| 9 | `IntDirection` | 103 |
| 11 | `BigInteger` | 90 |

`byte[]`+`String` is 6.1–7.3% on the new build against 5.9–6.9% on the old — unchanged
within noise. The remainder is **geometry object churn**.

### Consequence for the phase

**Logging is exhausted as an allocation lever.** F2's remaining ~129 guards will buy
nothing either, and should be scheduled as maintenance rather than sold as performance.

The only remaining US-3 headroom is geometry churn, and it splits into two things the goal
treats differently: a geometry REPRESENTATION rewrite is closed, but OBJECT REUSE for
`IntPoint` / `IntOctagon` / `FloatPoint` is not a representation change — same types, same
exact arithmetic, fewer instances. That is the only live path to a real US-3 number, and it
is an operator decision rather than something to start unasked.

## US-2 on the merged build, on somebody else's boards

Build *Merge pull request #8 from darksideX1/fix/mcp-bridge-unbounded-wait*, banner `Freerouting v2.3.1-SNAPSHOT (build fa354ce1)`. an independent bench's three
the fixture set as supplied. Fanout off, optimizer on, `max_passes=500`, n=3, arms interleaved.

**Repeatability was established before any timing was taken from these boards**: one
distinct `.ses` hash across three fanout-off runs, per board. They are usable as canaries.

| board | v19 mode | v2 (current) | US-2 |
|---|---|---|---|
| power-b2_4i | 103 s (100–108) | **12 s** (11–12) | **PASS** — 8.6× |
| logic planes-declared | 102 s (99–105) | **41 s** (41–43) | **PASS** — 2.5× |
| logic as-kicad-exports | 61 s (61–63) | **22 s** (21–22) | **PASS** — 2.8× |

### What the v19 arm is, and is NOT

`--router.algorithm=freerouting-router-v19` selects **this fork's reimplementation of the
v1.9 algorithm, inside this codebase**. It is **not** the original freerouting 1.9.0
application, and nothing here may be reported as "versus v1.9" without that qualifier.
Running both engines from one jar controls for the jar, the JVM, the machine and the
board — it does not make the v19 arm equal to the program anybody else ran, and it does
not insulate either arm from whatever this codebase does wrong to both.

An earlier version of this fork's reporting attached an independent bench's 1.9.0 figure to the v19 arm
as independent corroboration. That was unsound and has been retracted to them.

### Both engines are deterministic with fanout off

One distinct `.ses` hash per arm per board, six arms. Nobody had established that for the
**v19 mode** before — it was assumed. Now measured.

### The result that goes against v2

| board | v19 mode unrouted | v2 unrouted |
|---|---|---|
| power-b2_4i | 11 | 11 |
| logic planes-declared | **15** | **21** |
| logic as-kicad-exports | 2 | 2 |

On `planes-declared` the v2 engine leaves **six more connections unrouted**, at n=3 with
identical hashes inside each arm and tight timing — same jar, same board, engine the only
variable. v2 buys 2.5× wall-clock there and pays six connections for it. Parity on the
other two boards.

This is a real quality regression in the engine this phase exists to improve, and it was
found by another lane's fixture rather than by our bench. It belongs in any honest reading
of "faster without changing what it produces": on two of three boards that holds, on the
third it does not.

### Peak heap: no claim either way

| board | v19 mode | v2 |
|---|---|---|
| power-b2_4i | 188.2 MB | 205.5 MB |
| logic planes-declared | 140.6 MB | 203.7 MB |
| logic as-kicad-exports | 220.9 MB | 121.9 MB |

v2 is worse on two and better on one, **and the within-arm variance swamps the difference**
— three fanout-off runs of the same board earlier reported 218.1, 73.2 and 157.5 MB. These
medians do not support a claim in either direction and are recorded only so nobody
re-derives them and believes them.

### Caveats recorded rather than discovered later

- **Another lane was running a routing job on this box during these runs.** The within-arm
  ranges stayed tight (100–108, 11–12, 61–63), so contention did not visibly destabilise
  the arms, but the numbers were not taken on a quiet machine and that is stated rather
  than assumed away.
- The summary table printed by `us2.sh` still labels its first column `v1.9`; the correct
  label is `v19 mode`. The script header and this document are right, that one `printf`
  is not.

### The original 1.9.0 cannot run headless at all

Checked directly, because a proxy is not a baseline. `freerouting-1.9.0.jar` from the
upstream v1.9.0 release (2023-10-30, sha256 `9084a488…3102`):

```
java -Djava.awt.headless=true -jar freerouting-1.9.0.jar -de board.dsn -do out.ses -mp 500
  -> java.awt.HeadlessException, thrown again inside its own exception handler
  -> no .ses written
```

Under `xvfb-run` it routes, reporting "Auto-routing was completed in 31.76 seconds" on
power-b2 and stopping itself at ~200 passes on its own low-change criterion rather than on
a pass limit. It then enters a long separate optimisation phase.

Two consequences. First, **US-5 "no display required" is now demonstrated against the
original rather than asserted** — the original genuinely cannot, this build can. Second,
any 1.9.0 reference number produced without a display did not come from this program, and
one produced with a display came from a different mode with a different stop criterion —
which is why the comparison above is deliberately labelled against our v19 mode and not
against 1.9.0.

## Racing mode — measured twice; the first number was wrong

**The earlier "51× slower" figure is WITHDRAWN.** It was measured on an
`acef24b0-dirty` jar built from a working tree that predated the wiring commit, and it
does not reproduce. This is exactly the failure the banner marker exists to catch, and
it caught it — but only because the confirmation run was actually run rather than
waived on the grounds that the deltas looked too big to be a build artefact. They
weren't too big. Recorded here rather than quietly replaced.

### What racing actually does, on a committed build

Build *docs(fork): racing measured — it loses by 51x, stays off*, clean tree, bm01, 1 pass, fanout and optimizer off, n=3 interleaved,
`-Xmx8g`, 8-minute deadline.

| arm | wall median | range | unrouted median | range | outcome |
|---|---|---|---|---|---|
| racing off | 9 s | 9–9 | **56** | 56–56 | 3/3 clean |
| racing on | 10 s | 7–13 | 195 | **53–195** | **2/3 NullPointerException**, 1/3 completed |

Racing is not slow. It **crashes**, two runs in three:

```
NullPointerException: Item.shape_layer(int) -- "curr_item" is null
NullPointerException: ShapeTree$Storable.get_tree_shape(...) -- "tmp_entry.leaf.object" is null
```

A null item and a null tree leaf are the signature of shared mutable state. The first
guess was that `deepCopy()` was lossy — it serialises the board, and serialisation drops
`transient` fields, of which `Item.board` and `Item.search_trees_info` are two. **That
guess was wrong, and it was tested rather than argued.**

`BoardCopyIntegrityTest` copies a real board and asserts, single-threaded, that every
item on the copy has a non-null `board` pointing at *that* copy, and that every item can
answer the tree-shape lookups routing performs. **Both pass.** A lossy copy would have
failed here with no threads involved at all.

So the copy is sound, and the corruption comes from something shared **outside** the
copied object graph — statics, or per-item state reached by a path the copy does not
own. `Item.get_precalculated_tree_shapes` already carries a comment describing this exact
`NullPointerException` arising from a non-volatile transient field "that
BatchOptimizerMultiThreaded shares between threads", so the pattern is known to exist in
this codebase; what is not yet established is which shared thing bites *this* path, with
the optimizer off. Seeding the orderings could never have made it reproducible: it is a
race, not an ordering.

### Dose-response: the crash does not scale with worker count

Build *test(board): the copy is sound -- the racing crash is a race, proven not argued*, bm01, 1 pass, n=5 per arm.

| workers | crashes | racing pass won | unrouted |
|---|---|---|---|
| 1 (control — racing never runs) | 0/5 | 0/5 | 56, 56, 56, 56, 56 |
| 2 | **3/5** | 2/5 | 99, 106, 195, 195, 195 |
| 4 | **2/5** | 3/5 | 57, 195, 56, 195, 57 |
| 8 | **3/5** | 3/5 | 195, ?, 195, 54, 57 |

~50–60% at two workers, four and eight alike. **Not contention pressure that more threads
make worse** — something structural that two trip as readily as eight.

Caveat on the design of this experiment: the `threads=1` row is not "one racing worker",
it is racing *disabled*, because `shouldRace` requires more than one thread. It is a
control, not a dose point. What it does establish is that the single-threaded path is
exactly repeatable — 56 unrouted, five times, no spread.

Where it throws, across all runs:

| frame | hits |
|---|---|
| `ShapeSearchTree.overlapping_tree_entries_with_clearance` | 12 |
| `Item.clearance_violations` (Item.java:378) | 6 |
| `ShapeTree$Leaf.compareTo` | 2 |

Search-tree traversal, finding null leaf objects (`curr_item`, `curr_object`,
`this.object`, `tmp_entry.leaf.object`). The flat rate across worker counts points away
from workers racing *each other* and toward a worker racing something that is always
present — the main thread, or the board-updated listener registered on thread 0, both of
which run while workers route. That is a hypothesis with a cheap test, not a finding, and
it is written down as such because reading the code has already produced two confident
wrong answers on this bug.

**The one run that completed scored 53 unrouted against the single-threaded 56.** The
idea works. A best-of-N over different orderings found a better board on its one
uncrashed attempt. What does not work is this implementation of it.

### Two findings that matter more than the wall-clock

**A pass aborted by an exception still reports `COMPLETED`.** The two crashed runs wrote
a 4140-byte `.ses` (against 49562 for a clean run) containing 195 unrouted items — that
is the input board with nothing routed — and the job's final state was `COMPLETED`,
which the exit-code contract maps to **exit 0**. A script sees success and an empty
board. Registered as defect 24; it is a hole in the exit contract this fork added, not
an upstream one.

**The memory probe never fired.** No run logged a thread-count reduction, so the count
was never actually bounded on this box. The bound is written and unit-tested; it is not
demonstrated on real hardware, and should not be described as if it were.

### Ruling

Racing stays **off**, and the phase does not adopt it. Upstream's implementation joins
every thread, scores them all after the fact, has no quality threshold and no early
accept — it is not the parallelisation this phase set out to build, and it does not
survive its own data race. Wiring it produced one useful thing: proof that a best-of-N
over orderings can beat the single-threaded board (53 vs 56), which is evidence *for*
the design in `PARALLELISM-DESIGN.md`, not for this code.

**Scope note, recorded deliberately.** A symbol named `racing` was found whose shape
resembled the intended design, and it was enabled and measured instead of the intended
design being built. A matching name is not a matching algorithm. The work that landed
here — flag, seed, memory bound, single selection rule, `PassOutcome` — is real, and it
is scaffolding around someone else's half-finished feature.

## US-3 — "it doesn't eat my RAM" — allocation re-baselined

Full uncapped runs, n=3.

| | median | range | spread |
|---|---|---|---|
| Total allocation | **179.4 GB** | 179.3–186.4 | 7.1 GB |
| Wall-clock | 92 s | 91–93 | 2 s |

The stale **~210 GB** figure re-measures at **~179 GB** on the current build.

Independent confirmation of the peak-heap half, from an independent bench on their own 4-layer board and
their own checker: **peak heap 140.7 MB against stock ~1165 MB** — an 8× reduction, measured
by a lane that is not ours.

### Where the allocation actually is

JFR **class** breakdown, not frame breakdown. `ClearanceMatrix.get_value` shows as the top
*frame* while allocating nothing — it is small, guarded, and inlined everywhere, so its
neighbours are charged to it. `ARCHITECTURE-AS-FOUND` records this trap and it is real.

| | before (*fix(geometry): the exactness invariant is observable when it breaks*) | after (*fix(logging): the root level made every guard in the codebase a no-op*) |
|---|---|---|
| `byte[]` + `String` | **20.8%** (median 25.37%, n=3) | **4.5%** (median 6.74%, n=3) |
| top allocator | `byte[]` | `IntPoint` |

18.6 pp against a worst-case within-arm spread of 2.55 pp — roughly 7× the noise. `.ses`
**byte-identical across all six runs and both arms**, so the change is behaviour-free rather
than believed to be.

Cause: the root logger was built at `Level.ALL` (this config filters per appender-ref), so
`isTraceEnabled()` answered true forever and every level guard in the codebase was a no-op.
Hot paths built diagnostic strings that were never emitted. Fixed in *fix(logging): the root level made every guard in the codebase a no-op*.

### The same fix, in wall-clock

Fixed work (1 pass) so the arms are comparable, n=3:

| arm | median | range |
|---|---|---|
| *fix(geometry): the exactness invariant is observable when it breaks* | 12 s | 12–13 |
| *fix(logging): the root level made every guard in the codebase a no-op* | **10 s** | 10–11 |

~17% faster; delta 2 s against 1 s spread. **Real but modest.** A hypothesis that this
explained upstream's "5min+" claim was tested here and is **not supported** — the per-item
DRC-for-logging is not dominating at this scale.

### What the allocation numbers do NOT justify

- **GC is not the cost.** 580 ms of pause over a 60 s run — under 1%. Reducing allocation
  volume is a heap-pressure and mutator-work lever, not a collection-time one.
- **`BigInteger` is 3.0% of allocation.** The `long` fast path everyone reaches for targets
  three percent. The exact-arithmetic guardrail protects a door that barely needs opening.
- **The program's own "GB total allocated" is unusable** — it reported 16.24 GB, 0.01 GB and
  0.00 GB for the same board across runs. Use JFR.

---

## Method notes worth keeping

- **Fix the work, not the clock.** `--router.max_passes=N` makes arms comparable; a deadline
  truncates them at different points and makes repeatability unassessable — `run-report.sh`
  refuses to judge repeatability on a truncated run for exactly this reason.
- **Record the banner, not the filename.** Every run above names its build. A `-dirty`
  suffix means the jar was built from a modified tree and cannot be reproduced from that
  sha — one measurement round here was invalidated by exactly that and re-run.
- **Interleave arms.** Batched arms let machine drift look like effect.
- **Delta must exceed within-arm spread.** Stated for every claim above.

## Allocation baseline, re-measured on the current build (2026-08-09)

Every "US-3 moved N%" figure so far divided by a ~210 GB number taken on an older build
whose configuration was never written down. A percentage of an unrecorded denominator is not
a result, so this replaces it.

**Build:** jar self-reports `Freerouting v2.3.1-SNAPSHOT (build 58e44964)`, sha `f526aee9a41b`.
**Board:** `b2_power.cb3a1a0.z1`, `--router.max_passes=6`, optimizer enabled, `-Xmx3g`, headless.
**Method:** JFR `settings=profile`, `jdk.ObjectAllocationSample` **weight-summed** — each
sample carries the bytes it represents. `ThreadAllocationStatistics` is deliberately not used;
it reports per-chunk deltas and reading it as a total was an earlier error in this fork.
**n=3.** All three runs produced board hash `0d2c0f549f`.

| | r1 | r2 | r3 | median (range) |
|---|---|---|---|---|
| total allocated | 68.3 GB | 68.1 GB | 67.5 GB | **68.1 GB** (67.5–68.3) |
| peak heap used | 220 MB | 220 MB | 220 MB | **220 MB** (flat) |

**This is not comparable to the old ~210 GB.** That figure's board, pass count and optimizer
state were not recorded, and this run caps passes at 6. No delta is computed from it, and
none should be quoted. This table is the new baseline; future numbers compare to it.

**Peak heap is bounded at 220 MB against a 3 GB ceiling**, dead flat across three runs. US-3
asks for a bounded peak heap and this is the first number it has ever had. Note what it means
together with the row above: 68 GB flows through a 220 MB working set, so this is allocation
*churn*, not retention. Nothing is accumulating; objects are being created and dropped.

### Class breakdown (r1, top items)

| bytes | share | class |
|---|---|---|
| 30.84 GB | 45.1% | `IntOctagon` |
| 8.82 GB | 12.9% | `TreeMap$Entry` |
| 3.00 GB | 4.4% | `IntPoint` |
| 2.71 GB | 4.0% | `int[]` |
| 2.35 GB | 3.4% | `TreeMap` |
| 1.82 GB | 2.7% | `BigInteger` |
| 1.71 GB | 2.5% | `TreeSet` |
| 1.63 GB | 2.4% | `ShapeTree$TreeEntry` |

### Who asks for it

`IntOctagon` (30.84 GB) — `offset()` 52.7%, `intersection()` 32.0%, `union()` 5.8%. This is
real geometry: enlarging shapes by the clearance and intersecting them is the clearance test.

`TreeMap$Entry` (7.97 GB attributed) — `MinAreaTree.overlaps()` 42.1%,
`ShapeSearchTree.overlapping_objects()` 22.9%, `Trace.get_normal_contacts()` 21.2%.
This is **not** geometry. It is sorted-container bookkeeping around tree queries, and with
`TreeMap` and `TreeSet` alongside it accounts for ~19% of all allocation.

### Correcting the record

This fork has repeatedly stated that the remaining churn after the US-3 sites was "geometric
work rather than waste". For the `IntOctagon` half that is true. For the ~19% sitting in
sorted containers it is false, and the reason the error survived is worth keeping:

The `AllocationCensus` built earlier instruments **geometry constructors only** — `IntPoint`,
`IntVector`, `FloatPoint`, `Line`, `IntOctagon`. By construction it can only ever attribute
allocation to geometry. Reading "it is all geometry" off an instrument that cannot see
anything else is a property of the instrument, not of the code. JFR sees every class, which
is why it was specified in the goal and why it should have been run first.

Defect 27 was one instance of this species — a `TreeSet` used as a sorter, on a path where
it could silently drop a candidate. The table above says there is roughly 8 GB more of the
same shape, in `MinAreaTree.overlaps()` above all. Whether those containers are also sorters
that never deduplicate is the next thing to establish, and it is a question about code, not a
judgement call.

### US-3 result: MinAreaTree.overlaps no longer builds a tree to hold its results

Same board, same flags, same JFR settings as the baseline above, n=3, so the two tables are
directly comparable. Board hash `0d2c0f549f` on every run of both.

| | baseline | after | delta |
|---|---|---|---|
| total allocated, median (range) | 68.1 GB (67.5–68.3) | **64.0 GB** (63.9–64.8) | **−4.1 GB, −6.0%** |
| `TreeMap$Entry` | 8.82 GB (12.9%) | 5.04 GB (7.8%) | −3.78 GB |
| peak heap used | 220 MB | 221 MB (220–222) | unchanged |

The ranges do not overlap — the worst run after (64.8 GB) is below the best run before
(67.5 GB) — so this is a real effect rather than run-to-run spread.

The −3.78 GB on `TreeMap$Entry` lands on the 3.36 GB that caller attribution predicted for
`MinAreaTree.overlaps`, plus the `TreeSet` wrappers that went with it. The mechanism is
confirmed, not merely the total, which matters: a total that moved for an unexplained reason
would be a coincidence until proven otherwise.

Peak heap did not move, and should not have. 64 GB still flows through a ~220 MB working
set; this was always churn rather than retention, and removing churn does not lower a bound
that churn was never setting.

**Scope of the claim:** one board (`b2_power.cb3a1a0.z1`), `max_passes=6`, optimizer on. It
is not a general −6% and should not be quoted as one until a second board is measured.

**Reproducing it:** the JFR parsers are kept with the measurement tooling,
deliberately outside the repo — they are lane instrumentation, not part of the fork.

### Second board: no allocation claim, because the board is not repeatable under a deadline

The −6.0% above was scoped to one board on purpose. The attempt to widen it to `b3_logic`
did not produce a number, and the reason it did not is worth more than the number would
have been.

Three runs of the **same jar**, same board, same flags produced **three different boards**:
`607b8af5dc`, `5a4cdc57ad`, `66bdb7165f`. Repeatability is a precondition for any allocation
or speed claim, so nothing is claimed here. The −6.0% remains a `b2_power` result.

**This is not engine nondeterminism, and defect 20 stays closed.** The run logs say
`Optimization stage completed with timeout` after 668 s against a 12:00 job deadline. Every
run was cut off by the clock rather than finishing, so each stopped at whatever point it had
reached when time expired, and wall-clock jitter between runs moves that point. CPU time
tracked wall time at roughly 1.0, so threading is not involved either.

To measure this board the deadline has to stop binding — cap the work by passes, or give it
a budget it does not reach. A truncated run cannot be compared with another truncated run.

### What the same logs say about where allocation actually lives

On `b3_logic`, from the run's own accounting:

| stage | allocated | peak heap |
|---|---|---|
| auto-routing (6 passes, 52 s) | 84 GB | 261 MB |
| optimization (668 s, deadline-truncated) | **1,162 GB** | 260 MB |

The optimiser allocates roughly fourteen times what routing does on this board, and it does
so at a steady rate for as long as it is allowed to run — the figure is a function of its
time budget, not a fixed property of the board.

Every US-3 site addressed so far (`ArrayStack` reuse, `IntOctagon` border lines,
`difference_by`, `MinAreaTree.overlaps`) is in the **search** path. On a board like this one
that is the small half. This does not invalidate the −6.0%, which was measured where it was
measured, but it does say the next allocation work should start from an optimiser-stage
profile rather than from the search-path census.

Note also the peak heap: 260 MB while 1.16 TB flows through it. Same conclusion as `b2_power`
at a different scale — this is churn, and no bound is being threatened.

### Correction: the remaining TreeMap$Entry is NOT more of the same

After `MinAreaTree.overlaps` I wrote that the other 5.04 GB of `TreeMap$Entry` was "the same
species" and decision-free. That was a guess dressed as a finding, and checking it says no.

The two remaining callers both need Set semantics:

- **`ShapeSearchTree.overlapping_objects_with_clearance`** — one item owns several tree
  shapes, so it yields several `TreeEntry` values and `p_obstacles.add(entry.object)` is
  handed the same object repeatedly. The Set is what turns entries back into items.
- **`Trace.get_normal_contacts`** — merges the contacts at the start corner with those at the
  end corner. An item touching both corners appears in both, and should be reported once.

`MinAreaTree.overlaps` was removable for a specific reason, not a general one: it walks a
**tree**, every node has exactly one parent, so a duplicate cannot arise and the Set was
provably doing nothing. Defect 27's `TreeSet` was removable for the same kind of reason — the
comparator was a total order, so it never deduplicated while ids were unique.

**The rule is not "a TreeSet in a hot path is waste".** It is: *establish whether a duplicate
can reach this collection.* Where one cannot, the Set is dead weight and removing it is free.
Where one can, the Set is the algorithm, and replacing it silently changes results — which on
the clearance path means obstacles going unchecked.

Swapping `TreeSet` for `HashSet` is not an escape either: it allocates a node per element
just the same, and it changes iteration order, which this engine's determinism depends on.

So the remaining `TreeMap$Entry` is largely legitimate, and US-3's next move is not here.

## Session results, 2026-08-09

Every figure below is a real run on the box named, with the jar self-reporting its commit.

### US-2 — routing is faster; the earlier "fail" was a bad comparison

US-2 was reported failed for most of a day on the strength of 179 s against 1.9.0's 68 s.
That compared unlike work. From the original's own log on `planes-declared`:

| stage | 1.9.0 | ours |
|---|---|---|
| startup / fanout | 21.7 s | 2.8 s |
| auto-routing | 44.78 s | 26.6 s |
| optimisation | **1.63 s** | 39.8–149 s |
| total | 68.2 s | 70–179 s |

Their optimiser runs 1.63 s and stops — the same shape defect 25 gave this fork before it was
fixed. So 68 s is routing plus a no-op, and the comparison was measuring a difference in
work, not in speed.

Three comparisons, all on the same board and box:

- **routing stage:** 26.6 s vs 44.78 s — **1.66× faster**
- **equal budget (~70 s cap):** 75 vias vs 80
- **strict (65 s cap):** **64 s and 63 s wall** vs 68.2 s, repeatable, 13 unrouted from 17

### US-3 — −9.0% cumulative, board byte-identical

| | |
|---|---|
| re-measured baseline | 68.1 GB (67.5–68.3), n=3, peak heap 220 MB |
| after `MinAreaTree.overlaps` | 64.0 GB (63.9–64.8) — −6.0% |
| after enlargement memo | **62.0 GB** (61.9–62.0) — −3.1% |
| board hash | `0d2c0f549f` throughout |

Ranges disjoint at each step. The stale ~210 GB figure it replaced was taken on an unrecorded
configuration and no delta against it is computed.

The memo's 3.1% is well short of what a 300× reuse rate suggested (6,081,227 calls, 20,271
distinct results). Only the obstacle side is memoised, and the memo pays for itself in map
structure. **A reuse ratio is not a saving.**

### The optimiser stops itself — the 3-minute default was hiding it

With a 30-minute budget so the clock cannot bind, on `planes-declared`:

```
failures=50   optimisation 438.29 s   2 passes   12 unrouted   board 8e6adb848a
failures=7    optimisation 438.12 s   2 passes   12 unrouted   board 8e6adb848a

pass #1: examined 131, improved 96, 272.03 s
pass #2: examined 126, improved 102, 165.84 s
Stopping optimizer because the improvement in this pass (0.0002%) is below the threshold (1.00%).
```

The pass-level diminishing-returns stop **already exists and works**.
`max_consecutive_failures` is inert at any value because the threshold wins first — note pass
#2 improved 102 items and moved the score 0.0002%. Item improvements and score improvement
are different quantities, which is why an item counter can never be the right stop.

At the 3-minute default the stage is cut at 149 s with 13 unrouted; left alone it runs 438 s
and ends at 12. The default trades a net for five minutes.

An earlier version of this experiment used the 3-minute default and concluded the setting did
nothing. That experiment could not observe what it claimed to measure.

### KiCad demo boards — publishable, and a different weight class

The cohort used so far is a third party's and its numbers cannot be published. KiCad ships 19
demo boards which can. They are zeroized (every track and via removed, footprints/pads/zones/
netlist/rules untouched) and exported through the `pcbnew` Python API — `kicad-cli` has no
Specctra export.

| board | nets | result |
|---|---|---|
| `pic_programmer` | 111 | **fully routed in 4 s wall** — fanout 0.13 s, routing 0.92 s, optimisation 0.01 s, 0 unrouted, 1 violation, self-terminated on score |

Larger demos (`video` 5.9 MB, `tinytapeout` 4.6 MB, `vme-wren` 71 MB, `jetson-agx-thor` 88 MB)
export slowly through `pcbnew` and are not yet measured.

### US-5 / US-6 — verified rather than assumed

`DISPLAY` removed from the environment and the headless flag deliberately **not** passed, so
nothing could mask a display dependency: exit 0, `.ses` written, 32 log lines of which 22 are
progress, zero AWT/X11 errors. These had been carried as passing all session on the strength
of earlier work; this is the run.

### Racing — closed

Correct now (a racing pass read a board another thread was still writing; defect 27's
`TreeSet` could silently drop a real obstacle), off by default, documented as not recommended.
Best-of-N orderings did not beat one good attempt, and the case weakened as the
single-threaded path got faster. Board partitioning scoped and deliberately not attempted.
