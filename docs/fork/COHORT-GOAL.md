# Goal: a routing attempt for every board in the cohort

**Status at time of writing: NOT STARTED.** Nothing is zeroized, no cohort run exists.
Stated plainly because this is blocking and a vague "in progress" would be worse than a no.

## The goal

Every board available is zeroized, routed by Freerouting-Anneal, and ends in one of two
states: **routed**, or **not routed after 60 minutes** — with no board left in "we did not
get to it".

Then the wider bar, which is the real one: **everything measurable about this router on
these boards, in this configuration, has been measured.** The run is finished when no
further test can be devised — not when the clock runs out or the list is ticked. If a
question about the router can be answered with the boards and the machine available, it
gets answered. Reaching the end of the ladder is the beginning of that, not the end.

## The damaged sub-cohort, kept separate

Some boards are already defective as inputs — the malformed-outline warning is the known
case, and there may be more; an independent reviewer has reported on this and should be asked before the
run rather than after.

Those boards are routed and reported, but **in a separate sub-cohort**, never mixed into the
main numbers. A board whose Edge.Cuts does not close has unreliable extents, so the router
is working from a guess, and a failure there says something about the input rather than
about the router. Averaged into a headline figure it would quietly make the router look
worse than it is — which is the same error as making it look better, and just as
publishable-by-accident.

Classify at zeroization time, not after seeing the results. Deciding a board was "damaged"
because it routed badly is how a cohort gets curated into whatever answer was wanted.

## Why the escalation ladder exists

A board that reports `Ran out of time.` was still improving when the clock stopped it, so
its result says nothing about whether the board is routable — only that the budget was too
small. A board that reports `Pass finished. No further improvements found.` has told us
the opposite: more time buys nothing.

So the ladder escalates **only** on the timeout ending:

| attempt | budget | escalate when |
|---|---|---|
| 1 | 15 min | `Ran out of time.` |
| 2 | 30 min | `Ran out of time.` |
| 3 | 45 min | `Ran out of time.` |
| 4 | 60 min | — stop regardless |

Any other ending is final for that board. `No further improvements found` means done, and
re-running it at 60 minutes would burn an hour to learn nothing — which is precisely the
distinction the run-ending work exists to make, and this is its first real use.

Worst case is bounded: 68 boards × (15+30+45+60) = 170 hours if every board escalates the
whole way. It will not, but the ladder must run per board rather than per cohort, and
boards that finish at 15 minutes must not wait behind boards climbing to 60.

## The blocker, and where each step has to run

**the routing machine has no `pcbnew`.** Zeroization needs KiCad's Python, which is installed only on the
laptop. That is not a preference; the module does not exist on the routing machine.

| step | where | why |
|---|---|---|
| 1. zeroize `.kicad_pcb` → stripped board + DSN | **laptop** | KiCad Python only exists there |
| 2. transfer DSNs | → the routing machine | small files |
| 3. route with the ladder | **the routing machine** | 16 threads, and it is not the maintainer's desktop |

Step 1 runs on the maintainer's machine. With the wx logging fix applied it is silent — no
dialogs — but it is still their machine, so: one subprocess per board, a timeout on each,
and no interactive prompts.

## Scope: every board available, not a chosen subset

Two sources, both in scope, enumerated before anything runs so the denominator is a fact
rather than an estimate:

- the KiCad demo boards already in the repository fixtures
- `pcb_boards_smart` — open-source hardware projects, publicly licensed

Counts have been reported inconsistently (62 vs 68 for the second set, depending on whether
the sweep recurses; the demo set has not been counted properly at all). **Count first,
publish the sweep that produced the count, and reconcile before routing anything.** A
denominator nobody can reproduce is not a denominator.

Boards already zeroized are not re-zeroized. Boards not yet zeroized are, to the standard
below.

## The damaged sub-cohort, named before the run

Supplied by an independent reviewer ahead of the run, so classification is not contaminated by results.

**No outline at all (1)** — not a routing question in any sense: `haptic` (no outline
bounding box, no pads).

**Outline will not close (3)** — Edge.Cuts cannot be chained into a ring, so extents are a
guess and the router works from one: `doorman`, `plain60-c`, `Multipro-txV2-3d`.

**DSN export fails after the L1 strip (15 reported, 16 observed here)**:
`DongleHiderPlus_PCB`, `OSO-BOOK-B1`, `OSO-BOOK-C1`, `PSLab`, `RP2040_base`,
`RP2040_debugger`, `Watchy`, `beepy`, `bitaxeUltra`, `doorman`, `homebrew_486`,
`initial-v`, `linht-hw`, `plain60-c`, `user_interface`.

Our own run put the figure at 16 of 68. **Reconcile the two lists before publishing
either** — one board is unaccounted for and an off-by-one in a denominator is exactly the
kind of thing that survives into a table.

Note that the export-failure set and the bad-outline set **overlap by only two**. Do not
treat them as the same population: eleven boards fail export for reasons nobody has
diagnosed.

## A falsification test, offered against its author

an independent reviewer's cut measure says eight boards are **provably oversubscribed** — no router
completes them as placed — with peak ratios: `BadgeMagic` 4.58, `rpi-rm0` 1.41,
`GB-BRK-CART` 1.21, `GB-BRK-M-XS` 1.21, `GB-CART256K-A` 1.13, `GB-CART32K-A` 1.09,
`GB-LIVE32` 1.05, `GB-MBCTEST` 1.05.

They state plainly that this is a claim against themselves: **if this router completes any
of the eight, their impossibility proof is unsound.** That makes it the highest-value
single experiment in the cohort, and it must run.

Run these eight first, before the bulk, and at the full ladder. The ones at 1.05 are the
honest test — a ratio that close is where rounding, and their axis-aligned treatment of
rotated pads, could manufacture an impossibility. A completion at 4.58 would be
extraordinary and should be distrusted before it is believed.

Their caveat, applied: the ratios assume a 0.25/0.20 track class because per-board
netclasses were not known. A board using finer geometry has a **lower** true ratio than
quoted, which makes completion more likely rather than less.

## Parallelism: four at a time

The routing machine has 8 cores / 16 threads and 30 GB. **Four concurrent routing jobs, fixed.**

Not eight, and no ramp test overnight. The constraint is memory rather than CPU: observed
peak heap on a single board reached ~500 MB, and heap is where a bad board hurts. Four
leaves a wide margin on a machine running unattended for hours, and the goal tonight is a
complete run rather than the fastest possible one. An unattended ramp that finds the ceiling
finds it by swapping, and every measurement taken while it swaps is worthless.

Each board carries its own ladder. A board that finishes at 15 minutes must not wait behind
a board climbing to 60 — the pool takes the next board, not the next rung.

## Zeroization: what is a solution, and what is physics

**Remove the solution.** Tracks and vias. That is what a router is being asked to produce,
so leaving it in measures re-examination rather than routing.

**Never remove or move anything the physical world fixes.** These are constraints, not
solutions, and a board without them is easier than the board that exists:

- **Connectors, ports, external interfaces.** Their position is set by the enclosure and by
  whatever plugs into them.
- **Antennas, and any component carrying a keepout.** Do not touch these at all — not even
  to move or rotate them. A keepout attached to a component is a *property of that
  component*: rotate the chip and the exclusion rotates with it. We do not know the rotation
  rules, so we do not get to move the chip.
- **Keepouts and rule areas** generally, including the ones nested inside footprints, which
  a board-level zone sweep does not see.
- **Mounting holes, fixed placements, the board outline.**

Treat all of the above exactly as the board shape is treated: given. The zeroizer removes
copper, nothing else.

**One interaction to check before trusting the output:** the zeroizer removes footprints
that sit outside the board outline, on the reasoning that a pad out there is an airwire no
router can close. That is correct for parked probes. It would be wrong for an edge connector
that legitimately overhangs. Verify per board that nothing removed was a connector, and log
what was removed either way.

## Method

Scientific, and the standard is the one already applied to the three-engine comparison:

- **Count and state the denominator** before running; report boards that fail to zeroize or
  export separately rather than dropping them.
- **Verify the strip did what it claims** — per board, assert that track count went to zero
  and that keepout and rule-area counts are unchanged, including nested ones. A zeroizer
  that quietly removed constraints produces optimistic numbers with nothing in the output
  saying so.
- **One variable.** Same jar, same machine, same settings; only the board and the budget
  change.
- **Escalate only on the timeout ending**, per the ladder above.
- **Record everything per board**: budget reached, ending, unrouted, violations, wall clock,
  and what the zeroizer removed.
- **Verify any number that is not directly observed.** If a figure is derived, say how; if
  it cannot be measured to standard, say that instead of estimating.

## What to zeroize with

**`zeroize.py` from `project_jalo/hardware/tools/experiments/`.** Do not write a new one.
It strips tracks and vias and then re-fills zones, so keepouts and rule areas survive by
construction — which is the whole correctness question, and the trap that the obvious
implementation (`zones = []`) walks into.

Two changes needed, in a copy rather than in an independent bench's tree:

1. The wx logging fix, or it wedges behind modal dialogs on Windows.
2. Nothing else. Its off-board-part removal is deliberate — a pad outside the outline is an
   airwire no router can close, and leaving it in poisons the completion count.

Permission to use it has been requested and not yet answered.

## What "done" looks like

A table, one row per board: board, nets, final budget reached, ending, unrouted count,
violations, wall clock. Plus an explicit list of boards that were **not routed even at 60
minutes**, because that list is the interesting result — it is the set this router cannot
solve, and it is what a comparison against other engines should be built on.

Boards that fail to zeroize or fail to export are reported as such, separately. A board
missing from the table for an unexamined reason is a defect in the run, not a gap in the
data.

## What must not happen

- **No silent truncation.** If the run is stopped early, say which boards never got an
  attempt. A partial cohort presented as a whole is the failure this project has spent all
  day documenting elsewhere.
- **No escalation on a non-timeout ending.** It wastes hours and misreads the result.
- **No zeroizer that removes constraints.** If keepouts do not survive, every number is
  optimistic and nothing in the output says so.
