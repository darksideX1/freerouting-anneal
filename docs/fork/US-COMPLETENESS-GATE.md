# User Stories Completeness Gate — US1–US54

Companion: `FS-FAULT-STORIES.md` (what happens when things go wrong) and
`FS-SEVERITY-TAXONOMY.md` (how loudly it says so). Defect evidence:
`DEFECT-REGISTER.md`.

Format borrowed from the LegalEye experience gate, including its status
vocabulary and its governing rule:

> **A row moves to BUILT only with an observation behind it. "It should work" is
> not a status.**

That rule is the whole value of the document, so it is applied strictly here: a
row is BUILT only if something was *run* in this fork and the result recorded.
Reading the code and concluding it must work does not qualify — this session
began with three defects that a careful code reading had already pronounced
fixed.

## Status vocabulary

| Tag | Means |
|---|---|
| **BUILT** | Observed in this fork, with the evidence named inline |
| **PARTIAL** | Exercised but fails one element, or observed only on some surfaces |
| **GAP** | Should work and does not; **the gap is the backlog item** |
| **BLOCKED** | Deliberately deferred; no amount of testing closes it, only a decision |
| **UNWALKED** | *Lane addition, declared not smuggled.* Exists in code, never exercised here, status genuinely unknown. Distinct from PARTIAL, which implies something was tried. Most of the GUI is here, and pretending otherwise would be the exact failure this gate exists to prevent |

## Why a Surface column and not a hardware tier

LegalEye's gate carries a hardware tier, because a story can exist on one class
of machine and be honestly unavailable on another. Freerouting's equivalent axis
is the **surface**: the same capability can be present in one entry point and
missing from another, and nobody notices because each surface has different users.

Not theoretical. On 2026-08-08 a wall-clock-bounded run wrote a good `.ses`
through the CLI and returned **HTTP 400 "has no valid output"** for the same job
over REST, while all three SSE streams for it never closed. One job, four
surfaces, three different answers. Every row states the surfaces it is claimed
for, so "works" can never again mean "works where I happened to look".

| Surface | Meaning |
|---|---|
| `GUI` | The Swing desktop application |
| `CLI` | Headless `-de` / `-do` batch invocation |
| `API` | REST server (`/v1/jobs/…`) and its SSE streams |
| `ALL` | Claimed everywhere — and therefore must be checked everywhere |

## Personas

| ID | Who | Needs most |
|---|---|---|
| **P1** | KiCad user routing their own board in the GUI | To be told what happened, in words they can act on |
| **P2** | Engineer running boards headless, often in CI | A run that terminates, a signal, and an artifact |
| **P3** | Someone comparing engines or settings (this fork; an independent reviewer) | That what they selected is what ran |
| **P4** | Someone driving it over HTTP from another program | The same answers the CLI gives |
| **P5** | Whoever diagnoses a bad run afterwards | A log naming the board, net and item |

---

## A. Get it and run it (US1–US6)

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US1 | P1,P2 | I can run this build without first discovering which Java it needs | ALL | **PARTIAL** — documented, but the handout's `~/jdk25/bin/java` is one machine's path; on the routing machine it does not exist and the toolchain JDK had to be found instead. The requirement is right, the instruction is not portable |
| US2 | P2,P3 | I can tell exactly which build I am running when I report a result | ALL | **BUILT** — *docs(fork): version provenance -- v2.2.4 has no fanout phase; second nondeterminism source bounded to 544 commits*; identity is a commit, not a version string. "freerouting v2" provably names more than one engine |
| US3 | P1 | A first run does not require me to read documentation | GUI | **UNWALKED** |
| US4 | P2 | A headless run needs no display and no window manager | CLI | **BUILT** — exercised on every measurement run in this fork, 2026-08-08 |
| US5 | P2,P3 | The build I am given is not silently older than the fixes I was told about | ALL | **BLOCKED** — the distributed jar *fix(guard): board/ is in the logging guard, and the comment no longer contradicts it* trails HEAD by 12+ commits and contains defects since found, including a CLI hang on timeout. Re-cut deliberately withheld pending sign-off |
| US6 | P2 | I can rebuild it myself and get the same artifact | CLI | **PARTIAL** — `executableJar` is a separate task `build` does not depend on; a silently stale jar was caught only by checksum |

## B. Tell it what to do (US7–US14)

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US7 | P3 | A setting I pass is either applied or refused — never silently ignored | CLI | **BUILT 2026-08-08** — *fix(cli): refuse a flag the program cannot apply instead of dropping it silently*, *fix(settings): refuse an argument the program cannot apply, by name*. Observed live: `--router.jobTimeoutString=…` refused by name at ERROR |
| US8 | P3 | If I mistype a flag I find out at startup, not from a wrong result days later | CLI | **BUILT 2026-08-08** — four distinct silent-ignore defects had been found in a single day before this landed |
| US9 | P3 | The space-separated form of a long option does not silently vanish | CLI | **BUILT 2026-08-08** — `--router.fanout.enabled false` consumed neither token; now refused with the working form named |
| US10 | P2 | An option the program legitimately owns at startup is not reported as broken | CLI | **BUILT 2026-08-08** — *fix(settings): a startup-owned option is not a rejected one*; a false alarm on `--logging.file.pattern` teaches people to ignore the real ones |
| US11 | P3 | Selecting an engine actually runs that engine | ALL | **PARTIAL** — *fix(router): honour the algorithm setting instead of hardcoding the router* + selector tests; v1.9 observed dispatching 2026-08-08. Before it, anyone comparing v1.9 by flag compared v2 against itself. Not walked on GUI or API |
| US12 | P1 | I can change the important settings without editing a file or learning flag names | GUI | **UNWALKED** — the autoroute-parameter window exists; whether the *important* settings are reachable is unknown |
| US13 | P2 | A stored setting that has gone stale is reported, not silently used | ALL | **PARTIAL** — observed for the log location; the general case unassessed |
| US14 | P2 | I can bound a run by wall clock | ALL | **BUILT 2026-08-08** (CLI, observed) / **UNWALKED** (GUI fields exist). `--router.job_timeout=00:15:00`. **Default is 12 h**, which is no bound at all unattended |

## C. Route a board (US15–US21)

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US15 | P1,P2 | I give it a board and get a routed board back | CLI **BUILT** / GUI,API **UNWALKED** | Exercised continuously headless in this fork |
| US16 | P2 | A run that produces a result always writes it where I asked | CLI | **BUILT** — *fix(scheduler): save the routed board instead of relying on a progress event*; a router emitting no progress events used to report success and write a **0-byte** `.ses` |
| US17 | P2,P4 | A run stopped by its deadline still hands me the board it had | CLI **BUILT 2026-08-08** (471,351 bytes observed) / API **PARTIAL** (*fix(api): a time-boxed board is served, not called invalid*, not driven over HTTP) / GUI **UNWALKED** | |
| US18 | P1 | A pre-routing stage does not consume the whole budget without saying so | ALL | **PARTIAL** — fanout timeout reported in the finish line; per-pin 10 s budgeting remains a root cause of speed-sensitive results (defect 19). Observed: fanout consumed 27 s of a 20 s job |
| US19 | P1 | Routing does not hang forever on a pathological board | ALL | **PARTIAL** — `normalize_traces` caps at 2,000 iterations, logs and stops; observed firing on three nets. Other loops unassessed |
| US20 | P3 | The same board, jar and settings produce the same output twice | ALL | **GAP** — defect 20. Board-dependent; bounded to a 544-commit range, not chased to zero. Observed stable on Issue413 at n=3, which proves one board and nothing more |
| US21 | P1 | Routing never silently draws a trace through copper it forgot about | ALL | **GAP** — defect 23, and the most serious row here. A concurrent search-tree mutation throws; the "fix" that skipped the stale entry was **reverted because it traded a crash for a silent clearance violation** |

## D. Know what happened (US22–US29)

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US22 | P1,P2 | The finish line says what was produced, not merely that the run stopped | ALL | **BUILT 2026-08-08** — *feat(reporting): the finish line says what was produced, not just that the job stopped*, observed. `COMPLETED` was previously true of a perfect board, a board with 19 unrouted and 192 violations, and a run out of clock |
| US23 | P2 | A run that ran out of clock says so, in words | ALL | **BUILT 2026-08-08** — *fix(scheduler): a run that ran out of clock is not a run that finished*; observed `TIMED_OUT (stopped at the job time limit of 00:00:20 — the result is partial)` |
| US24 | P1 | I am told **which** nets did not route, not just how many | ALL | **BUILT 2026-08-08** — *feat(reporting): name the nets that did not route*, observed. Capped at 25 with the cap stated, because a silently truncated list reads as a complete one |
| US25 | P2 | A script can tell a clean run from a dirty one without parsing the log | CLI | **BUILT 2026-08-08** — *feat(cli): a caller can tell a clean run from a dirty one without reading the log*; `--outcome_exit_codes=true` gives 0/3/4/1. Opt-in, because an incomplete board is normal and the default must not start failing CI on it. All four observed on real runs |
| US26 | P1 | A number I am shown means the same thing wherever it appears | ALL | **UNWALKED** |
| US27 | P2 | Progress is visible during a long run, not only at the end | ALL | **PARTIAL** — per-pass and per-stage lines observed; no per-board-region analogue |
| US28 | P1 | Clearance violations are reported distinctly from unrouted connections | ALL | **BUILT 2026-08-08** — both counts observed separately (`270 unrouted, 28 clearance violations`) |
| US29 | P5 | The claim in the summary matches what is in the output file | ALL | **PARTIAL** — *fix(scheduler): an aborted routing pass does not get optimized or written out* stopped an aborted pass being optimized and written out; the general invariant is unproven and deserves a dedicated walk |

## E. Automate it (US30–US36)

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US30 | P2 | A headless run terminates | CLI | **BUILT 2026-08-08** — *fix(cli): the headless run exits when the job does, and keeps the partial board*. It did **not**: a timed-out job span at 500 ms forever and was killed from outside. Observed before `real 6m40s` (external kill) → after `real 0m32.7s` |
| US31 | P2 | A cancelled headless run also terminates | CLI | **PARTIAL** — same fix covers it by construction; the cancel path itself was not walked |
| US32 | P2 | A run cannot outlive any bound I can express | CLI | **BUILT 2026-08-08** — deadline + 30 s grace observed, plus a hard 25-hour iteration cap as defence in depth |
| US33 | P2 | Process overhead beyond the routing job is small and known | CLI | **BUILT 2026-08-08** — measured ≈3 s (JVM start, parse, `.ses` write) |
| US34 | P2 | A crashed run does not litter my working directory | CLI | **PARTIAL** — *docs(fork): say where a JVM crash leaves its debris*; cannot be fixed in-process, so JVM flags and OS core-dump settings are documented. Documentation, not a walk |
| US35 | P2 | Two concurrent runs do not confuse each other's logs | ALL | **UNWALKED** — per-process tag exists in error references; log-file collision untested |
| US36 | P2 | I can run this in CI without a display, a prompt, or a modal dialog | CLI | **BUILT 2026-08-08** — `--gui.enabled=false` now applies; before, modal NPE dialogs blocked unattended runs |

## F. Drive it over HTTP (US37–US43)

**Walked 2026-08-08** over real HTTP — 10 checks, 10 passed. These were claims until
the walk existed; two of them turned out to be right and one of my own test's
assumptions turned out to be wrong, which is the point of walking rather than reasoning.

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US37 | P4 | I can submit a board and retrieve its result over HTTP | API | **BUILT 2026-08-08** — walked: session create → enqueue → upload (412 KB DSN) → start → poll → download. Note `start` is `PUT`, not `POST`; a wrong method surfaces as a **500 wrapping "HTTP 405"**, which cost me a diagnosis cycle and is worth fixing separately |
| US38 | P4 | The API gives me the same answer the CLI does for the same job | API | **BUILT 2026-08-08** — walked. A time-boxed job returned **400 "has no valid output"** before *fix(api): a time-boxed board is served, not called invalid*; it now returns **HTTP 200 with 445,220 bytes** for the same board the CLI writes to disk |
| US39 | P4 | A stream I open eventually closes | API | **BUILT 2026-08-08** — walked, all three. Output and JSON streams close in **0.2 s / 0.3 s**; the logs stream needed a second fix (it only evaluated the terminal state when a new log entry arrived, so a client subscribing to a finished job waited forever) and now closes in **0.0 s** |
| US40 | P4 | An in-progress job gives me partial output or an honest 204, not an error | API | **UNWALKED** |
| US41 | P4 | A failed job tells me so with a status I can branch on | API | **BUILT 2026-08-08** — walked: a deadline-stopped job reports `TIMED_OUT` through `GET /v1/jobs/{id}`, distinct from `COMPLETED` |
| US42 | P4 | The API and CLI cannot drift apart again on what a job state means | API | **BUILT 2026-08-08** — *fix(api): a time-boxed board is served, not called invalid*; the predicate lives on `RoutingJobState` and a growth guard asserts the terminal count, so a new state fails a test instead of five call sites. *This row is BUILT because the guard was observed failing and then passing* |
| US43 | P4 | A client that disconnects does not leave work running forever | API | **UNWALKED** — see FS10 |

## G. Compare and measure (US44–US48)

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US44 | P3 | What I selected is what ran, and I can prove it afterwards | ALL | **PARTIAL** — selection fixed and logged; provenance documented. No end-to-end proof artifact |
| US45 | P3 | A measurement is not silently invalidated by a flag that did nothing | CLI | **BUILT 2026-08-08** — the whole B block. A fanout 2×2 once ran fanout-off in all four cells |
| US46 | P3 | I can establish a board is deterministic before relying on it as a canary | ALL | **BUILT 2026-08-08** — `run-report.sh` (lane tool, see below) runs a board N times and reports repeatability, refusing to judge it when the runs were truncated by the deadline — differing output is then the clock, not the engine |
| US46b | P1,P2 | One command tells me how the run ended, what is left for me, and what was refused | ALL | **BUILT 2026-08-08** — same tool. The data always existed; it was spread across a log nobody reads and an exit status that said nothing |

**`run-report.sh` lives OUTSIDE this repository**, at
a run-report script kept with the measurement tooling. The hygiene guard refused it in
the tree and was right to: it presumes our jar path and our JDK, which makes it a
lane tool rather than something to offer upstream. Whether a cleaned-up version
belongs in the contribution is a scope decision for the owner, not a side effect
of writing it.
| US47 | P3 | Removing diagnostics does not change routing behaviour | ALL | **BUILT 2026-08-08** — *refactor(autoroute): remove every board-specific debug scaffold*; `.ses` byte-identical at n=3 across a 17.2 KB removal |
| US48 | P3 | Nothing in the shipped router is tuned to one specific board | ALL | **BUILT 2026-08-08** — *refactor(autoroute): remove every board-specific debug scaffold* removed all of it: hardcoded nets 33/66/67/84/98, `"U27-"` designators, and a room anchor pinned to four literal coordinates. Zero hits remain under `src/main/java` |

## H. Diagnose it (US49–US54)

| ID | Persona | Story | Surface | Status |
|---|---|---|---|---|
| US49 | P5 | There is a log, I know where it is, and it does not fill the disk | ALL | **BUILT 2026-08-08** — *feat(logging): rotate at 20MB in a 4-file ring, and split DEBUG/TRACE into their own log*; 20 MB × 4-file ring, rotation observed by rolling it |
| US50 | P5 | Routine output and debug detail do not drown each other | ALL | **BUILT 2026-08-08** — *feat(logging): rotate at 20MB in a 4-file ring, and split DEBUG/TRACE into their own log* + *fix(logging): the debug file honours the level the user configured*; observed 8,153 DEBUG / **0 TRACE** in the debug file, INFO+ only in the main log |
| US51 | P5 | Turning logging up is my decision, and the default does not change under me | ALL | **BUILT 2026-08-08** — default deliberately unchanged; level honoured rather than pinned to TRACE |
| US52 | P1 | An error I am shown is something I can act on and quote | GUI | **PARTIAL** — *fix(gui): a raw exception is never what the user is shown* + *fix(gui): an error reference identifies exactly one error*; uniqueness observed over 10,000 references, but the dialog itself was never seen in a running GUI in this fork |
| US53 | P5 | An exception names the board, net or item it happened to | ALL | **PARTIAL** — *fix(autoroute): an item that crashed says which item it was*; "Error during routing passes" identified nothing on a 500-net board. Not yet observed firing |
| US54 | P1 | Dismissing an error does not let a broken run continue as though nothing happened | GUI | **UNWALKED** — the dialog no longer implies dismissibility; whether the run stops is unknown |

---

## Where this gate is weakest

Stated plainly, because a gate that flatters itself is worse than none.

1. **The GUI is barely assessed.** Seven rows UNWALKED, on the surface with the
   known live defect history. Everything walked in this fork was walked headless,
   because that is what a terminal can measure — which is a statement about my
   reach, not about the GUI's quality.
2. **The API block is now walked** (10/10 over real HTTP, 2026-08-08), and both
   defects fixed there are confirmed. What the walk also showed: my first attempt
   passed a check on a job still sitting in `QUEUED`, because the assertion only
   tested "state is not null". A test that cannot fail is worse than no test, and
   it was mine. US43 (client disconnect) remains unwalked.
3. **US20 and US21 are both GAP and they are the two that matter most.** A router
   that is nondeterministic and can transiently forget an obstacle is one whose
   output requires human checking before manufacture. Everything else here is
   comfort by comparison.
4. **BUILT rows cluster in D, E and H** — reporting, automation, diagnosis. That
   is exactly where a terminal-driven lane can observe things, and it should be
   read as sampling bias, not as those areas being sound and the others not.

## What would close the most ground fastest

1. An **API walk** — one script that submits a board, times it out, downloads the
   partial result and watches a stream close. Converts seven claims into facts.
2. A **GUI smoke walk** on the two known-bad paths (start-up NPE, error dialog).
3. A **determinism harness** for US46, so canary selection stops being a manual
   ritual that only works when someone remembers to do it.
