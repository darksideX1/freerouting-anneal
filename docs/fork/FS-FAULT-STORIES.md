# Fault & Environmental Story Block — FS1–FS14

Companion to `US-COMPLETENESS-GATE.md`. Severity levels are defined in
`FS-SEVERITY-TAXONOMY.md` (CRIT / ALARM / WARN / INFO); this document says what
each fault must *do*, not how loudly.

Every fault is stated from **four points of view**:

| POV | Asks |
|---|---|
| **U** | What does the user see, and what can they do about it? |
| **O** | What happens to the **output board**? Is a good board ever replaced by a worse one? |
| **X** | What can a *program* branch on — exit status, job state, HTTP code? |
| **L** | What does the log record, and at what severity? |

**The governing principle:** *a partial board is fine; a partial board presented
as a complete one is not.*

The shape is the **same** as LegalEye's, not a departure from it. That tool
pseudonymizes what its engine can reach and surfaces the rest verbatim to the
human — "I redacted what I could; what I could not, I flagged." This one routes
what it can and hands back the rest. Neither claims to finish the job alone.

What differs is what comes **after**. LegalEye's harness is built end to end: the
leftovers land in a review stage, a ledger, a certificate — infrastructure that
re-checks the claim. Here there is none. The report is the terminal artifact, and
nothing downstream will catch it being wrong.

So the invariant is narrower and sharper: **the report must be true and it must
reconcile.** That is this program's equivalent of "never a false SAFE", and it is
load-bearing for the same reason — a `.ses` is fabricated into copper. A run that
stops early and says so costs an hour. A run that stops early and reports success,
or lists half the unrouted nets as though they were all of them, costs a board.

**POV-O is a design constraint, not a report.** If a row's POV-O cannot promise
that the previous good board survives, the fix belongs in the persistence model —
not in the error message.

---

## Process and platform

### FS1 — Process killed mid-run (SIGKILL, CI timeout, closed laptop)
- **U:** Nothing; the process is gone. On re-run, no confusing state is inherited.
- **O:** Whatever was last written stays written. A killed run must never leave a
  truncated `.ses` that parses as a complete one.
- **X:** The shell's kill status. Nothing from us.
- **L:** Whatever reached disk before the kill — hence `immediateFlush` on both
  appenders.
- **Status:** **UNWALKED.** No resume model exists and none is claimed; unlike
  LegalEye there is no ledger and no chunk commit. The honest position is that a
  killed run is simply lost, and the only invariant worth asserting is that the
  *previous* output is not corrupted. **Untested.**

### FS2 — JVM crash (SIGSEGV in the JIT)
- **U:** The process dies. Debris lands where the JVM was told to put it.
- **O:** Same as FS1 — last written board survives.
- **X:** JVM crash status.
- **L:** `hs_err_pid*.log`, possibly `replay_pid*.log` and a core dump.
- **Status:** **PARTIAL.** The reproducible one — C2 compiling
  `MazeSearchAlgo::expand_to_room_doors`, roughly 1 run in 5 — is fixed
  (0 in 20 runs) as a side effect of shrinking that method. Debris paths are now
  documented (*docs(fork): say where a JVM crash leaves its debris*) because they cannot be set from inside a running JVM.
  Defect 6 / defect 18.

### FS3 — Out of memory
- **U:** An `OutOfMemoryError`, probably as a crash.
- **O:** Undefined. Not assessed.
- **X:** Nonzero, indistinguishable from other failures — see FS-X below.
- **L:** Unassessed.
- **Status:** **UNWALKED.** Allocation was reduced from 1,631 GB to ~210 GB on the
  benchmark board (defects 1, 2, 9, 15), so headroom improved substantially — but
  *improved headroom is not a handled fault*. Nobody has run this to OOM on
  purpose and watched what happens.

---

## Time and termination

### FS4 — Wall-clock deadline reached
- **U:** `TIMED_OUT (stopped at the job time limit of 00:15:00 — the result is
  partial)` plus the unrouted and violation counts.
- **O:** **The partial board is written.** A time-boxed run is a request for the
  best board at the deadline; refusing to write it makes the option useless.
- **X:** Job state `TIMED_OUT`; over HTTP the output downloads normally. **Exit
  status is still binary** — this is the open hole, see FS-X.
- **L:** ALARM. The run did what was asked and the result is incomplete by design.
- **Status:** **BUILT 2026-08-08** on CLI (observed: `real 0m32.7s`, `out.ses`
  471,351 bytes, correctly labelled). **PARTIAL on API** — *fix(api): a time-boxed board is served, not called invalid* fixed a
  400 "has no valid output" for exactly this case but has not been driven over
  HTTP. Three defects met here: the label lied, the CLI hung, and the API refused
  the board.

### FS5 — The deadline passes but the router does not stop promptly
- **U:** Same as FS4, ~30 s later.
- **O:** Same.
- **X:** Same.
- **L:** ALARM.
- **Status:** **BUILT 2026-08-08.** The 30 s grace period exists so the router
  stops tidily rather than mid-write. Note the perverse history: the *better* the
  router behaved at its deadline, the more likely the old code mislabelled the run
  `COMPLETED` — the race is why `finalStateFor()` consults the deadline and not
  just the state.

### FS6 — A run that never terminates
- **U:** Nothing. It sits there.
- **O:** No output ever written.
- **X:** None — the caller waits forever.
- **L:** Silence, which is the tell.
- **Status:** **BUILT 2026-08-08** — and this was *real*, not hypothetical. The
  CLI wait loop tested two of the four terminal states, so a timed-out job span at
  500 ms indefinitely and had to be killed externally. Now every terminal state
  ends the wait, under a hard 25-hour iteration cap. **A loop whose only exit is a
  predicate someone else sets must not be the last line of defence** — that is the
  generalisable lesson.

---

## Routing faults

### FS7 — A routing pass throws mid-pass
- **U:** An ERROR naming the failure; the run continues or stops honestly.
- **O:** **The last good board on disk is not overwritten with the partial one.**
- **X:** Job state reflects the abort.
- **L:** CRIT — a defect, not a board property.
- **Status:** **PARTIAL.** `PassOutcome` tri-state built for `autoroute_pass`
  (*fix(autoroute): a pass has three outcomes, not two*) and for v1.9 (*fix(autoroute): a crashed v1.9 pass is not a finished v1.9 pass*); persistence protection in
  `shouldPersistFinalBoard`; and *fix(scheduler): an aborted routing pass does not get optimized or written out* stopped the optimizer re-writing the
  partial board *immediately after* that protection fired. Never walked with an
  induced crash — the whole chain is reasoned, not observed.

### FS8 — A single item fails to route because of an exception
- **U:** The item is reported unrouted, like any other unrouted item.
- **O:** Unaffected; the board is valid, just less complete.
- **X:** Counted in the unrouted total — **deliberately**, so the board is never
  reported cleaner than it is.
- **L:** ERROR naming the item and net (`J2-A3`, net 41).
- **Status:** **PARTIAL** (*fix(autoroute): an item that crashed says which item it was*). The conflation with ordinary congestion is
  *known and left open with its price recorded*: a distinct ABORTED state needs 18
  comparison sites audited across 5 files, and one missed site would drop the item
  out of the unrouted count — reporting a cleaner board than exists, which is
  worse than the conflation. Defect 17.

### FS9 — The search tree is mutated while it is being traversed
- **U:** An intermittent `NullPointerException`, on GUI and headless alike.
- **O:** The run stops. **No board is produced from a tree that forgot an obstacle.**
- **X:** Failure.
- **L:** CRIT.
- **Status:** **GAP — defect 23, and the most serious row in this document.**
  `MinAreaTree.remove_leaf` nulls a leaf's object *before* the replacement is
  inserted, and `Leaf.compareTo` dereferences it unguarded. A fix that skipped
  stale entries was **reverted**: skipping omits real copper, so the router draws
  through it — converting a loud crash into a silent clearance violation in a
  board somebody fabricates. **The crash is the safer failure and is deliberately
  restored.** The real fix synchronises traversal against mutation, which changes
  the tree's concurrency contract.

### FS10 — Two runs of the same board disagree
- **U:** Nothing tells them. They find out by diffing.
- **O:** Both boards are valid; they are simply not the same.
- **X:** Nothing.
- **L:** Nothing.
- **Status:** **GAP — defect 20.** Board-dependent, bounded to a 544-commit range,
  not chased to zero. Fanout's per-pin stopwatch is *a* source, not the only one.
  This is the fault with **no detection at all**: every other row here at least
  says something. A comparison lane that does not first establish determinism will
  attribute noise to whatever it changed.

---

## Input and output

### FS11 — The output file cannot be written (disk full, permissions, locked)
- **U:** Must be told, unmistakably. A run that routed for an hour and could not
  save is a catastrophe, not a warning.
- **O:** Nothing to protect — but a previous good file must not be truncated by a
  failed write.
- **X:** Must be a failure.
- **L:** CRIT.
- **Status:** **PARTIAL.** `setJobOutput` now *reports* whether it stored data
  rather than assuming (*fix(scheduler): an aborted routing pass does not get optimized or written out*), and a false SES-writer result is logged
  instead of vanishing. But the disk-full and read-only cases have **never been
  induced**, and the CLI's final `Files.write` is a separate path from the job
  output object. **This row deserves the next walk in this block.**

### FS12 — A malformed or unsupported input file
- **U:** Told what is wrong with the file, and whether it was partially understood.
- **O:** No output.
- **X:** Failure.
- **L:** WARN or CRIT depending on whether anything was salvaged.
- **Status:** **PARTIAL** — observed: an old-KiCad DSN loads with 4 warnings and a
  clear message naming the version problem, and malformed JSON settings degrade to
  defaults with an explanation. Good behaviour, but sampled incidentally rather
  than walked.

### FS13 — A flag the program cannot apply
- **U:** An ERROR naming the argument and, where known, the working form.
- **O:** Unaffected — but the run is **not** doing what was asked, which is the
  point.
- **X:** Recorded in `getRejectedArguments()`; the run currently continues.
- **L:** ERROR.
- **Status:** **BUILT 2026-08-08** — observed live when `--router.jobTimeoutString`
  was refused by name. Note the residual judgement: a rejected argument does not
  *abort* the run. For a measurement lane that is arguably wrong — a run
  configured differently from what was asked is a run whose numbers mean something
  else. Left as a decision, not an oversight.

### FS14 — A client disconnects mid-stream
- **U:** Their stream ends.
- **O:** Unaffected.
- **X:** N/A.
- **L:** Unassessed.
- **Status:** **UNWALKED.** All three SSE streams now close on any terminal state
  (*fix(api): a time-boxed board is served, not called invalid*) — before, a timed-out or errored job streamed the board every 500 ms
  for as long as the client stayed connected. Whether an *abandoned* client leaves
  the executor running is a separate and untested question.

---

## FS-X — the cross-cutting hole

**A program cannot tell a clean run from a dirty one by exit status.**

`System.exit(0)` on success, `1` on failure, and "success" includes a board with
270 unrouted connections and 28 clearance violations. FS4, FS7, FS8, FS10 and
FS13 are all invisible to a caller that does not parse the log — and parsing logs
for control flow is exactly what a taxonomy is supposed to make unnecessary.

**CLOSED 2026-08-08** (*feat(cli): a caller can tell a clean run from a dirty one without reading the log*). `InitializeCLI` now returns a `CliOutcome`
and the exit status carries it:

| | | |
|---|---|---|
| `0` | COMPLETE | routed, nothing left, no violations |
| `3` | INCOMPLETE | work remains for a human — **not** a failure |
| `4` | STOPPED_EARLY | deadline or abort; a truncated attempt, not a conclusion |
| `1` | FAILED | no result produced |

Opt-in via `--outcome_exit_codes=true`, and opt-in *because* an incomplete board
is normal: making the codes default would fail every CI job that tests
`$? -eq 0` on boards behaving exactly as expected. Legacy policy is bit-for-bit
unchanged and asserted by test.

`INCOMPLETE` and `STOPPED_EARLY` are kept apart because they tell the reader to do
different things: a board the router finished with wants components moved, while a
board it was interrupted on may just want a longer budget.

Verified on real runs — legacy/incomplete → 0, outcome/incomplete → 3, deadline →
4, unreadable input → 1.

---

## Honest summary of this block

| | Count |
|---|---|
| BUILT (observed in this fork) | 4 |
| PARTIAL (reasoned, or observed on one surface only) | 6 |
| GAP (should work, does not) | 2 |
| UNWALKED (never exercised) | 3 |

Four of fourteen. The two GAPs — FS9 and FS10 — are the two that decide whether
this router's output can be trusted without a human checking it, and neither has
a fix that fits in a patch. Everything BUILT here concerns *telling the truth
about a run*; nothing BUILT here concerns *surviving a fault*, because no fault
has yet been deliberately induced. **That is the honest state: this fork has made
the program candid, not yet robust.**
