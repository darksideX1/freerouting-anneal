---
type: design
status: draft
lifecycle: active
created: 2026-08-08
last_validated: 2026-08-08
owner: fork maintainer
---

# Parallelism by quality contest

## Why this document exists

Phase 3 stream 3.1 set out to use the idle cores. During scoping, a symbol named
`racing` was found in the upstream tree whose shape resembled the intended design, and
it was wired up and measured instead of the intended design being built. That was a
scope error: **a matching name is not a matching algorithm.** This document specifies
the algorithm that was actually intended, and states plainly where it differs from the
code that was found.

What the measurement bought is not nothing. On its one uncrashed run, upstream's
best-of-N over shuffled orderings produced **53 unrouted against the single-threaded
56**. That is evidence that a contest between parallel attempts can beat one attempt.
It is not evidence for upstream's implementation, which crashed on two runs in three.

## What upstream's `racing` does, and why it is not this

| | upstream `racing` | this design |
|---|---|---|
| Unit of work | the whole pass, over the whole board | a **slice**: a stage, a frozen area, a sub-network |
| Waiting | joins **every** thread, then scores | **first acceptable result wins**; losers are discarded mid-flight |
| Quality rule | best score of all finishers | an **acceptance threshold** for that slice; anything over it is good enough |
| Slow stream | waited for | abandoned, even if it would have routed better |
| Failure to route | scored like any other board | triggers a **judgement**: is more time likely to buy anything? |
| Next iteration | next pass, same whole board | winner becomes the **baseline** for the next slice |

The difference that matters is the third and fourth rows. Upstream pays the cost of the
slowest thread on every pass. The point of this design is to *not* pay it: if four
streams attempt a slice and one comes back acceptable while another is three times
slower, the slow one is killed unfinished. Its potentially better answer is deliberately
given up, because the precursors are already in hand and the next slice can start now.

## The better primary scheme: spatial slice-and-dice

Contesting the same work N ways is redundant work — N workers to produce one answer.
Cutting the board into disjoint regions is *divided* work: N workers producing N answers
that compose. The second is the stronger lever, and it has a property the contest can
never have.

**Slicing can be deterministic and parallel at the same time.** A fixed partition, a
fixed region-to-worker assignment and a fixed merge order give the same output every run,
N times faster. The contest cannot: it pays precisely *because* the engine is
nondeterministic, so it is building on the nondeterminism rather than removing it, and
its result depends on who finished first. That makes slicing primary and the contest an
optional layer to add only where cores are spare and reproducibility is being traded away
on purpose.

It also side-steps the defect measured above, at least in principle. Disjoint regions
touch disjoint geometry, so workers stop competing over the same search tree — which is
what the crash is made of.

### Shape

1. Bisect the board into regions — horizontally and vertically, recursively.
2. Each region goes to a worker, which routes **only connections wholly inside its own
   boundary**. Anything crossing a boundary is out of scope for that worker.
3. Merge one level up: route the seams between each adjacent pair (verticals first, then
   the pairs of verticals joined horizontally).
4. Repeat up the tree; whatever is still unrouted at the top is routed against the whole
   board.

### The two numbers that decide whether this works

**Minimum slice size, and it should not be in millimetres.** A fixed 10 × 20 mm floor does
not adapt — the same square is dense on one board and empty on another. The quantity that
governs whether a cut is worth making is how many connections lie *wholly inside* the
resulting region. Cut too fine and almost every net becomes a boundary crosser, the seam
passes inherit all the work, and the top-level parallelism buys nothing while merge cost
grows. Make the floor **"a region must hold at least N fully-interior connections"**, and
measure N. Slice count then becomes `min(available threads, what this board supports)`.

**Cross-boundary fraction, which decides the whole scheme.** Before any of this is built,
one cheap experiment answers whether it is viable: partition a real board 4, 8 and 16 ways
and simply **count** interior versus crossing connections. If 16-way leaves most nets
crossing, the hierarchical merge is doing the real routing and the parallelism at the top
is theatre. This is a counting exercise over an existing board — no engine change, no
worker plumbing, no isolation fix needed first. **It should be done before anything else
in this document.**

### What it still needs

Region disjointness in *geometry* is not automatically disjointness in *state*. Two
workers on different regions still reach one `ShapeSearchTree` unless each holds its own
board copy — so the isolation work below is a prerequisite here too, just a less severe
one, because correct slicing removes the reason two workers would ever touch the same
shapes.

## The contest algorithm (optional layer)


```
baseline = current board
for each slice in slicing(board):
    dispatch N workers, each attempting `slice` from `baseline`
       with a different ordering / strategy / seed
    as each worker reports:
        if result meets acceptance(slice):
            accept it; kill the remaining workers; break
        else:
            record why it fell short
    if nothing acceptable:
        judgement: extend the budget, or accept the best-so-far,
                   or hand the slice back unrouted
    baseline = accepted result
```

Three parameters, and each is a real decision rather than a tuning knob:

**`slicing`** — what a slice is. Candidates, cheapest first: a routing *stage* (already a
boundary in the pipeline); a *frozen area* of the board that no other worker may touch;
a *sub-network* of connections that share no geometry. The correctness requirement is
that two workers on different slices cannot affect each other's result. Sub-network
slicing is the most parallel and the hardest to prove disjoint.

**`acceptance`** — what "good enough for this node" means. Not the global score.
Plausibly: all connections in the slice routed, zero clearance violations, and length
within some factor of the best seen. A threshold that is too tight degenerates to
"wait for everyone"; too loose and the first worker always wins and the parallelism buys
nothing.

**`judgement`** — what to do when nobody clears the bar. This is where the maintainer's
"is more time going to buy you more?" lives. A cheap first version: if the best result
improved between the first and second worker to report, extend once; if the workers
agree it cannot be routed, stop and hand it back.

## The blocking prerequisite

**Boards handed to parallel workers are not currently isolated.** The confirmation run
on *docs(fork): racing measured — it loses by 51x, stays off* crashed two runs in three:

```
NullPointerException: Item.shape_layer(int) -- "curr_item" is null
NullPointerException: ShapeTree$Storable.get_tree_shape(...) -- "tmp_entry.leaf.object" is null
```

The copy itself is **not** the problem, which was established by test rather than by
reading: `BoardCopyIntegrityTest` asserts single-threaded that every item on a copied
board has a correct back-reference to that copy and can answer routing's tree-shape
lookups, and it passes. A lossy copy would have failed there with no threads involved.

The corruption therefore comes from state shared **outside** the copied graph. Until that
state is identified and isolated, no parallel scheme is safe — this blocks the design
below just as it blocks upstream's, and it is the first piece of work. The next step is a
dose-response over worker counts (1, 2, 4, 8) against crash rate, which distinguishes
"any concurrency breaks it" from "contention above N breaks it".

Nothing in this design should be built before boards are provably independent, because
every result it produces would be suspect.

## The determinism question — needs a decision

**A contest decided by which worker reports first is decided by wall-clock, and is
therefore not reproducible.** Two runs of the same board on the same machine can accept
different slices and produce different output. That is a real cost, and it is a
different thing from the engine nondeterminism already on the register (defect 20,
closed by decision): this would be *newly introduced* nondeterminism, in service of
speed.

Two ways out, and they are not equivalent:

**(a) Deterministic budget.** Measure a worker's budget in work units — expansion rooms
opened, items attempted, iterations — rather than milliseconds, and accept in a fixed
worker order once the threshold is met. Same board, same result, every time, regardless
of machine load. Costs some of the speed, because a worker that is slow in wall-clock
but cheap in work units still gets waited for.

**(b) Wall-clock accept, declared nondeterministic.** Take the real speed win, put it
behind a flag that is off by default, and state in the handout that output is not
reproducible run to run.

**Recommendation: (a).** The lane's whole argument for this fork is that its output can
be trusted and compared; a mode whose result depends on machine load undermines the
measurement protocol that everything else here rests on. (b) can be added later as an
explicit opt-in if (a) proves too slow to be worth having. This is the maintainer's call,
not the implementer's.

## Order of work

1. **Isolate the board.** Failing test from the NPE reproducer; make `deepCopy` (or a
   purpose-built `forkForWorker`) produce genuinely independent boards. Nothing else
   starts until this is green.
2. **Decide determinism** (above).
3. **One slice type, N workers, no threshold** — prove the plumbing on stage slicing,
   accepting only when all workers finish. Should reproduce single-threaded output
   exactly.
4. **Add acceptance + early kill.** This is where the speed appears, and where the
   measurement matters: median and range, against the single-threaded arm, on a board
   established repeatable first.
5. **Add the judgement path** for slices nobody can route.

## What this design does not claim

It does not claim to be faster. Steps 1–3 will be *slower* than single-threaded and are
worth doing anyway, because they are what makes step 4 measurable. The only number that
counts is the one from step 4, and until it exists this document is a plan, not a result.
