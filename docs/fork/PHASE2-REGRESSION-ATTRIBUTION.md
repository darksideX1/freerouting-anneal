# Phase 2 — upstream regression attribution

What is actually still broken in `docs/issues/`, verified against the code rather than
read off the dossiers.

## Method, and why it mattered

Every status below was checked in the source. **Five of ten dossiers were stale** — they
describe defects that are already fixed. A dossier is a claim about the past; the code is
the present. Two costly mistakes were avoided by checking:

- **A green regression test can encode the defect.** `Dac2020Bm05RoutingTest` asserts
  `maxIncompleteConnections(51)` and `(44)`, while its own issue doc says the board
  *"should complete with 0 unrouted connections"*. The suite is green and the board is
  broken. A passing test is not evidence a bug is fixed unless you read what it asserts.
- **`./gradlew build` does not rebuild the shipped jar.** `executableJar` is a separately
  registered task that `build` does not depend on. A full green build left the executable
  jar byte-identical to the previous one. Caught only by checksumming the artifact.

## Status

| # | Regression | Verified state | Evidence |
|---|---|---|---|
| 652 | Linux ZIP contains only the licence | **CLOSED** upstream | build scripts fixed |
| 684 | `BoardHistory` grows unboundedly → OOM | **CLOSED** upstream | `MAX_HISTORY_SIZE = 30` + worst-score eviction in `BoardHistory` |
| 676 | Layer count 0 when autorouting from GUI | **CLOSED** upstream | `applyBoardSpecificOptimizationsIfNeeded(routingBoard)`, `BoardToolbar:197`; the destructive bare assignment is gone |
| 653 | KiCad plugin fails on paths with spaces | **CLOSED** upstream | `tempfile.mkdtemp` + `routing_dir` + `_cleanup_routing_dir` in `plugin.py` |
| 558 | Copper-to-edge clearance not respected | **HALF CLOSED** | DSN + CLI override works (`copperToEdgeClearanceUm`, `HeadlessBoardManager:390`). JSON/API path still ignores it — see below |
| 420 | Optimizer OOM on large boards | **UNRESOLVED, UNMEASURED** | our run never reached the optimizer: fanout alone took 6m40s over 5 passes on `Issue420-contribution-board.dsn` at `-Xmx2g`, no `OutOfMemoryError`, peak RSS 1.45 GB |
| 152 | Copper pour / plane awareness | **OPEN — worse than documented** | `Issue093-interf_u.dsn` routes to **192 violations, 19 unrouted** (dossier says ~62) |
| 508 | Dense all-SMD `bm05` not fully routed | **OPEN** | should be 0 unrouted; test asserts 44–51 |
| 383 | Star ground routing | out of scope — enhancement, not a regression | |
| 613 | Custom URL protocol | out of scope — declined upstream | |

**None of the five closures are ours.** Our Phase 0/1 work contributed one thing here:
`PassOutcome` removed the internal error that Issue 152 reported inside `autoroute_pass`,
so the failure is now diagnosed properly. The clearance defect itself is untouched.

## Issue 558 — the remaining half, and the cheap way to do it

The dossier prescribes creating a `board_edge` clearance class inside `KiCadJsonReader`,
duplicating the logic in `HeadlessBoardManager.applyCopperToEdgeClearanceOverride()`. That
means manipulating the clearance matrix during board construction — fiddly, and a second
copy of a rule that already exists.

**`HeadlessBoardManager:723` calls `KiCadJsonReader.readBoard(...)`.** The JSON path
already flows through the class that owns the override. So the smaller, lower-risk change
is to have the JSON path *populate the setting* — surface `outline.clearance` into
`routerSettings.copperToEdgeClearanceUm` when the user has not set it explicitly — and let
the existing, already-tested machinery apply it.

Both ends still need touching, and neither alone changes anything:

1. `board_json_helpers.py:161` hardcodes `"outline": {"corners": [], "clearance": 0.5}`.
   It must emit the real `m_CopperEdgeClearance` from KiCad's design settings.
2. The Java side must stop ignoring the value (`KiCadJsonReader:288`,
   `outlineClearanceNo = 1`).

Precedence rule to preserve: an explicit CLI override must still win over the board's own
value, or users lose the escape hatch that is currently the *only* fix for this issue.

### Resolved design — where the change goes, and why placement is the whole problem

Three approaches were considered. Two are traps:

- **Create the clearance class at `KiCadJsonReader:288`** (where `outlineClearanceNo` is
  set) — *wrong*. At that point `boardRules.create_default_net_class()` has not run yet
  (it happens at step 8, after `new RoutingBoard(...)`). Appending a class there means the
  values against every later-added class are never set, giving a clearance class that is
  silently incomplete.
- **Thread the value through `BoardReadResult` / `BoardMetadata`** — *disproportionate*.
  Both are records; `BoardReadResult` is a sealed interface whose `Success` component list
  is pattern-matched at every call site, and `BoardMetadata` has 2 production plus 8 test
  construction sites. A ten-site mechanical change to carry one double.

**Correct approach:** `KiCadJsonReader` creates the `board_edge` clearance class *itself,
after step 8*, once the matrix is fully populated — mirroring the retrofit idiom in
`HeadlessBoardManager.applyCopperToEdgeClearanceOverride()` (append class, set values both
ways across all layers and classes, assign to the outline, `clear_derived_data()`, and
re-insert into `search_tree_manager` if present). Self-contained in one file, no record
changes, no cross-file plumbing.

**Precedence then falls out for free and must not be re-implemented.** The existing
override already contains:

```java
boolean usesFallbackOutlineClass  = outline.clearance_class_no() == defaultAreaClassNo;
boolean usesDefaultEdgeClearanceValue = |configured - DEFAULT_COPPER_TO_EDGE_CLEARANCE_UM| < 1e-9;
if (usesDefaultEdgeClearanceValue && !usesFallbackOutlineClass) return;  // keep explicit class
```

So once the JSON reader assigns a real outline class, a user who passed no CLI value
(setting still at the default) leaves the board's own value intact, and a user who passed
one overrides it. That is exactly the required precedence, already written and tested —
adding a second precedence check would create two rules that can disagree.

Unit conversion: `outline.clearance` is millimetres; board units use the reader's existing
`scaleFactor`, the same factor applied to outline corner points.

**Status: designed, not implemented.** Stopping here deliberately rather than half-applying
a change to clearance geometry — this touches how *every* item on the board is spaced from
the edge, on an engine whose output cannot be compared byte-for-byte. It wants its own
session with a fixture test (`"outline": {"clearance": 0.5}` → correct class) and a routed
before/after on a board with a known edge constraint.

## Issues 152 and 508 — why these are not "regressions" in the sense we agreed

Both are **open, real, and reproducible**. Both are also, on inspection, upstream feature
work wearing regression clothing. Recorded here rather than attempted.

### 152 — copper pour / plane awareness (192 violations)

The router does not model a copper pour as a conductive plane belonging to a net. It sees
a large filled area and routes pads to it while violating clearance against it. Fixing
this is not repairing a broken code path; it is **teaching the engine a concept it does
not have** — plane-aware clearance and stub handling, with new user-controllable plane via
costs (the dossier's own acceptance criteria list exactly that as item 5).

Cross-project note: this is the same defect an independent bench hit independently from the other side —
their board scored *better* with 115 tracks slicing two planes than with the planes
intact, because the metric rewarded the damage. Their plane-integrity guard and this issue
are one defect seen twice.

### 508 — dense all-SMD board (bm05)

The fanout pre-pass exists now, so the originally reported gap is closed. What remains is
that on dense all-SMD boards the maze search still cannot find escape routes: occupied
regions around SMD pads block every via site. Closing it means **improving the routing
algorithm**, not fixing a defect — plus the regression test currently encodes the broken
state as its expectation, so any real fix must also rewrite its own acceptance criteria.

### The judgement

The rule for this fork was: fix the bugs and regressions, add tested
enhancements, then move on. **152 and 508 fail the first half of that test.** They are
open upstream problems requiring engine capability that does not exist, on a codebase we
forked to stabilise rather than to extend. Attempting either would be the largest change
in the fork by an order of magnitude, in the part of the engine we understand least, on an
engine whose output we cannot compare byte-for-byte because it is nondeterministic.

**Recommendation: document, do not fix.** Both are recorded here with reproductions and
measurements so the next person starts from evidence instead of a dossier. The 192-figure
in particular is worth publishing — it is materially worse than upstream's own ~62 and was
measured in eleven seconds.
