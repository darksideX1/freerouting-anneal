# Freerouting — how it actually works

Reverse-engineered while stabilising the engine. Upstream's `docs/architecture.md`
describes the intended shape; this describes the shape we *found*, including the parts
that do not do what their names suggest.

If you are picking this up cold, read §2 (the traps) before §1. Several of them cost us
hours each and none of them are visible from the source without running it.

---

## 1. The pipeline, as it really runs

```
.dsn ──▶ io.specctra parser ──▶ board model ──▶ [FANOUT] ──▶ [ROUTE] ──▶ [OPTIMIZE] ──▶ .ses
                                                    │            │             │
                                              BatchFanout  BatchAutorouter  BatchOptimizer
```

**Entry**: `Freerouting.main` → `RoutingJobScheduler` → `RoutingJobSchedulerActionThread`.
That last class is where the router is chosen and where the result is saved; both were
defective (§2.1, §2.2).

**Two engines exist.** `BatchAutorouter` (v2, default) and `BatchAutorouterV19` (the
v1.9 algorithm, kept in-tree for comparison). Both extend `NamedAlgorithm` and now both
implement `BatchRoutingAlgorithm`, which is the four-method contract the scheduler
actually drives: `runBatchLoop`, `isFanoutTimedOut`, `getSessionStartTime`,
`getInitialUnroutedCount`.

**The routing core** is a shape-based any-angle maze router, not a grid router. Free
space is decomposed on the fly into *expansion rooms* (tile shapes) joined by *doors*;
`MazeSearchAlgo` expands room→door→room with cost-weighted search, push-and-shove
(`MazeShoveTraceAlgo`), rip-up with escalating costs, and vias via *drill pages*.
`BatchAutorouter.autoroute_pass` drives one rip-up pass over all incomplete connections;
`runBatchLoop` drives passes until done, stopped, or out of budget.

**Geometry** (`geometry.planar`) is exact-arithmetic: `IntPoint`, `IntOctagon`, `Line`,
`Simplex`, with `BigInteger` rationals behind `RationalPoint`/`RationalVector` for cases
that overflow. Everything is immutable, so every intersection, projection and normalise
allocates. This is the dominant remaining allocation source and it is *inherent to the
design*, not waste.

---

## 2. Traps — read this section first

### 2.1 A setting can be accepted and ignored
`--router.algorithm=freerouting-router-v19` was parsed, logged as *"Unknown router
algorithm … using default"*, and then ignored: the scheduler compared only against
`ALGORITHM_CURRENT` and constructed `new BatchAutorouter(job)` unconditionally, with a
comment saying "Always use standard BatchAutorouter". **Anyone who selected the v1.9
engine by flag was running v2 and comparing it against itself.** Fixed via `RouterFactory`.

Generalise this: **check the log for "Unknown"/"Failed to apply" before trusting any
CLI flag.** A second instance exists — stock v2.2.4 does not know
`--router.fanout.enabled` at all and rejects it outright, so a fanout A/B on a stock jar
silently runs fanout-off in *both* arms.

### 2.2 Saving the result was a side effect of a progress event
The routed board was written only from inside a `BoardUpdatedEvent` listener. A router
that completes without emitting progress events produced a **zero-byte `.ses` while
reporting success**. `BatchAutorouterV19` fires no such events, so v1.9 routed correctly
and wrote nothing. Fixed: the board is saved unconditionally after the batch loop.

### 2.3 The engine is not deterministic
Same jar, same board, same settings can produce different routed output. Measured: a
short configuration split 4/4 across two distinct results over 8 runs. **Any single run
is one sample, not an answer.** Report median and range over n≥5, and require a
between-arm delta to exceed the within-arm spread before calling it a difference.

Two contributing mechanisms are known:
- **Wall-clock budgets.** `TimeLimit` (`datastructures/TimeLimit.java`) aborts search on
  elapsed milliseconds, not work done, and gates `AutorouteEngine`, `MoveDrillItemAlgo`,
  `PullTightAlgo` and `ShoveTraceAlgo`. `BatchFanout` gives **each individual pin** its own
  budget (`maxMillisecondsPerPin`, default 10 s). Anything that changes speed can change
  the result.
- **A second, unidentified source.** Disabling fanout does *not* restore determinism in
  general: with fanout off, v2 still produced 3 distinct outputs on two of three boards
  while v1.9 was deterministic on all three. **This is a v2 property, not a freerouting
  property.**

Consequence for benchmarking: freerouting's own published benchmark table is single-run
numbers off a variance-heavy process.

### 2.4 The tree traversal is order-sensitive
`ShapeSearchTree` carries the comment *"the non-deterministic order of tree traversal
causes different room partitioning"*. Changes that shift allocation or iteration order
can therefore shift routing output without changing any logic. Verify behaviour by
**routed-outcome distribution**, not by a single output hash.

### 2.5 It needs JDK 25 to *run*, not just to build
Master targets a Java 25 toolchain; the jar is class-file version 69 and dies with
`UnsupportedClassVersionError` on Java 21. Gradle downloads a JDK 25 to compile but the
system runtime is whatever you have.

Related: the JVM's C2 compiler **crashes** compiling
`MazeSearchAlgo::expand_to_room_doors` (1,250 bytes of bytecode) — SIGSEGV in
`PhaseRenumberLive::update_embedded_ids`, observed on Temurin 25.0.4+7. The method is
large enough to be pathological for the optimiser. Decomposing it is the root fix;
excluding it from C2 is the workaround.

### 2.6 Invocation facts that cost hours
- `-Djava.awt.headless=true` is required for CLI use, or GUI dialogs open.
- **Always cap `--router.max_passes`.** The `.ses` is written only after all passes
  complete; an uncapped stall yields *no output at all*.
- v1.9 (`1.9.0` standalone jar) needs a real display and `-dct 0`; the in-tree V19 engine
  via `--router.algorithm` does not.

### 2.7 Inputs can misdescribe the design
KiCad exports **every** copper layer as `(type signal)`, including planes. Freerouting
believes it and routes across them, slicing the planes — and connectivity metrics
*reward* that, because cutting a plane buys connections. Measured on one board: 0.7079
connectivity with planes cut versus 0.5843 with planes declared correctly. Fix the DSN
(`(type signal)` → `(type power)` per plane) before drawing any conclusion from a
multi-layer board.

---

## 3. Where the time and memory go

Allocation is dominated by the geometry layer once waste is removed: `IntPoint`,
`IntOctagon`, `Line`, `Simplex` — immutable value objects created inside the maze search.
The profile is **flat** (no single site above ~9%), which means it cannot be fixed
site-by-site the way one oversized data structure could. Reducing it further requires
object reuse or a floating-point filter with exact fallback, i.e. changing the shape of
the computation.

Two measurement cautions learned the hard way:
- **JFR frame attribution misleads on hot inlined methods.** `ClearanceMatrix.get_value`
  appears as the top allocator; it is small, already guarded, and allocates nothing. It
  is hot enough to be inlined everywhere, so neighbouring allocations are charged to it.
  Trust the *class* breakdown over the frame breakdown.
- `byte[]` is what backs `String` on modern JVMs. A large `byte[]` share usually means
  **string building**, not I/O.

---

## 4. Diagnostics, and why they were expensive

`FRLogger.trace/debug` take already-built Strings, so any concatenation in the argument
list runs *before* the level is checked. At the default INFO level none of it is emitted.
There was no user-facing log-level setting either, so it could not be configured away.
`FRLogger.isTraceEnabled()` existed and was almost unused; hot-path calls are now guarded
and a build-failing test keeps them that way.

Also note `FRLogger.trace(method, operation, message, impactedItems, points)` returns a
boolean and calls `DebugControl.check(...)` **unconditionally** — logging that performs
debug-control logic. It is inert unless single-step debugging is configured.

`BasicBoard.get_hash()` serialises every trace, via and item to produce an MD5 for log
lines and event payloads. It is called from 27 sites. It is not a hot-path problem at the
scale we measured (~36 calls per run) but it is expensive per call; do not add call sites
casually. Caching it against `revision` **does not work** — `revision` does not track
every mutation, and a cache keyed on it goes stale (verified by comparing hash sequences
across a run).

---

## 5. Building, running, measuring

```bash
./gradlew executableJar          # build/libs/freerouting-current-executable.jar
./gradlew executableV19Jar       # the standalone v1.9 engine (GUI main class)
./gradlew test                   # full suite

java -Djava.awt.headless=true -jar freerouting-current-executable.jar \
  -de board.dsn -do board.ses \
  --gui.enabled=false --api_server.enabled=false --mcp_server.enabled=false \
  --router.max_passes=100 --router.job_timeout=00:05:00
```

Add `--router.algorithm=freerouting-router-v19` to run the v1.9 engine in-process.

**Measuring anything**: pin the jar (copy it, do not reference a build path that will be
rebuilt), pin the board and its hash, use the same JVM for both arms, run n≥5, and report
median and range. If a number moves, **check the raw artifact before believing the
metric** — three times during this work a metric moved while the underlying `.ses` was
byte-identical, and the artifact was what caught it each time.

---

## 6. Architectural guards in the test suite

These fail the build rather than living in a style guide:

| Test | Prevents |
|---|---|
| `HotPathLoggingArchTest` | log messages built before the level check, in hot packages |
| `NoDebugScaffoldingArchTest` | board-specific debug scaffolding (hardcoded net numbers, reference designators) |
| `InterruptHandlingArchTest` | commented-out interrupt-status restoration |
| `NoEmptyArrayAllocationArchTest` | allocating empty arrays instead of the shared constant |
| `ArrayStackAllocationTest` | a capacity hint being paid for up front |
| `PassOutcomeTest` | conflating "crashed" with "finished" in a pass result |

`NoDebugScaffoldingArchTest` carries a **frozen allowlist** of scaffolding that already
existed. The set may only shrink. New scaffolding fails the build; retiring an entry is a
separate, behaviour-free change.
