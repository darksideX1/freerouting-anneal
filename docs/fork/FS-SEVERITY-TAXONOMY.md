# Fault stories — severity taxonomy

**Status: specified, not yet implemented.** Refines the two-level fatal/non-fatal split in
`ERROR-HANDLING-POLICY.md` into the four levels a user can actually act on differently.

Two levels were not enough. "The router threw an NPE and carried on" and "your board has
19 unrouted connections" are both non-fatal, and treating them alike is how a real defect
hides behind a normal-looking result.

## The four levels

Severity answers one question: **what should the person on the other end do?**

| | Meaning | What the user must do | Automated behaviour | Interactive behaviour |
|---|---|---|---|---|
| **CRIT** | Routing cannot continue, or the result is invalid | Stop and investigate. Do not use the output. | Stop the router. Non-zero exit. Message on stderr and in the log. Any partial artifact written and **marked partial**. | Stop, report prominently, keep the board on screen. |
| **ALARM** | It ran, but the result is not what was asked for — unrouted nets, DRC violations, a stage that hit its time limit | Look at the result before trusting it. It is not "done". | Complete, but say so in the summary and use a **distinct exit code** from a clean run. | Complete, and surface it in the end-of-run summary. |
| **WARN** | Something was ignored, degraded, or fell back — a setting that could not be applied, a plane misdeclared, a default substituted | Check whether the run measured what you meant | Log, continue, appear in the summary with a count | Same |
| **INFO** | Normal progress | Nothing | Log only | Log only |

Two rules that are not negotiable, because they are the ones that erode first:

- **Nothing below CRIT ever interrupts.** No modal, no prompt, no blocking wait — not in
  automated mode, and not in the GUI. One summary at the end, never a sequence of
  interruptions during.
- **Nothing at CRIT is ever silent.** If the router stops, the reason is on stderr, in the
  log, and in the exit code. "It stopped and I do not know why" is the same defect as "it
  popped up and I clicked it away".

## Classifying what we already know about

Assigning real events, because a taxonomy nobody can apply is decoration:

| Event | Level | Why |
|---|---|---|
| A routing pass aborts on an exception (`PassOutcome.ABORTED`) | **CRIT** | The board is partially routed and no longer reflects a completed algorithm |
| `ItemSearchTreesInfo` / `SearchTreeObject` NPEs | **CRIT** | Currently caught and displayed but never diagnosed. Until someone shows they are benign, an unexplained NPE in the search tree is not a warning |
| Board has unrouted connections at the end | **ALARM** | The commonest real outcome. Today it is invisible in the exit code |
| DRC violations in the result | **ALARM** | e.g. 192 on `Issue093` — a result the user must not ship unexamined |
| A stage hit its wall-clock limit | **ALARM** | The router stopped, it did not finish. See below |
| A setting could not be applied (`--flag value`, a `-D` that is overwritten, an unknown property) | **WARN**, arguably CRIT | Four instances found in one day. Every one silently changed what was measured |
| A plane layer declared `(type signal)` | **WARN** | Produces plane slicing that a naive score rewards |
| Fanout/optimizer stage progress | **INFO** | |

## "COMPLETED" is currently a lie, and this is where it gets fixed

The job state today says `COMPLETED` whether the router finished, ran out of clock, or gave
up making progress. Those are three different things and only one of them means "done".

An ALARM-level outcome must be distinguishable **without reading the log**:

- a distinct exit code for "ran, but not clean"
- a one-line summary naming the counts: `COMPLETED (19 unrouted, 192 DRC violations, 3 warnings)`
- **the unrouted list itself** — per net and pin, in both GUI and CLI. A count tells the
  user they have a problem; the list tells them where it is.

## Why this needs the logging work first

Every level above is a claim about something being recorded. Before this week the two NPEs
were written nowhere — console only, and dismissed with a click. A severity that cannot be
counted after the run cannot be asserted on by a test, which means the fault stories would
be unverifiable by construction.

The log now rotates and separates debug from normal output, so a scenario can assert on
counts per level. That is the prerequisite, and it is done.
