<h1 align="center">Freerouting-Anneal</h1>
<h5 align="center">A PCB autorouter for any tool that speaks Specctra DSN. A one-shot fork of <a href="https://github.com/freerouting/freerouting">Freerouting</a> 2.3.1, in which the optimiser runs.</h5>

<p align="center">
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License"/></a>
    <img src="https://img.shields.io/badge/version-1.1.1-informational" alt="Version"/>
    <img src="https://img.shields.io/badge/status-unmaintained%20snapshot-lightgrey" alt="Status"/>
</p>

> **This is a snapshot, not a project.** It was forked from Freerouting 2.3.1, fixed,
> documented, and released once. Nobody is maintaining it and there is no one to ask.
> Everything you need is in this repository — that was the design goal, and
> [`MAINTAINING.md`](MAINTAINING.md) is where the awkward parts are written down.
>
> It came out of a one-shot pass: an agent harness was pointed at Freerouting 2.3.1 for
> about two and a half days, told to fix what was broken without changing what the program
> decides, and then stopped. **The routing algorithm is untouched** — what changed is the
> code around it that stopped working code from running.
>
> **Scope: the router, the command line and the graphical interface.** Those were fixed,
> measured and documented. Freerouting's other surfaces — a REST API server, an MCP
> server, the KiCad plugin's API mode — exist, are off by default, and were not verified
> in this pass; they are listed under [known limitations](#known-limitations).

## What this is

A PCB autorouter. You give it a Specctra `.dsn` exported from your EDA tool, it connects
the nets, and it writes a `.ses` session file you import back. It is a fork of
[Freerouting](https://github.com/freerouting/freerouting) 2.3.1 and it routes with that
project's algorithm, unchanged.

What this fork changed is everything around the algorithm: the optimisation stage now
actually runs (in 2.3.1 it could not), it runs multi-threaded, runs stop when they stop
improving rather than when a timer expires, every run writes a report saying what it did
and what it could not finish, the log lives somewhere durable, and failures say what
happened instead of ending quietly. If you want the itemised version with commits and
measurements, that is [`RELEASE-NOTES.md`](RELEASE-NOTES.md) and
[`WHAT-CHANGED.md`](WHAT-CHANGED.md) — but you do not need either to use the program.

## Using it

Install a [Java 25 JRE](https://adoptium.net/temurin/releases/), download the jar from
the [Releases page](https://github.com/darksideX1/freerouting-anneal/releases), and run:

```bash
java -jar freerouting-anneal-1.1.1.jar -de MyBoard.dsn -do MyBoard.ses
```

That is the whole command for a normal board. Without `-de`/`-do` the graphical interface
opens instead, where the same run is the **Autoroute** button and the settings below live
under *Settings*. (**macOS:** launch from the Terminal; starting from Finder is not
supported.)

### The two settings worth knowing on day one

**How long it may take.** `--router.job_timeout=00:15:00` is the default: fifteen minutes
for the whole job. It is a budget, not a target — most boards finish early and say so.
Hours-scale values are accepted (`24:00:00` works) for boards that need them.

**How many threads.** Routing is single-threaded; the optimiser that runs after it is
multi-threaded, **two wide by default**, which is the setting that produced the best
boards in our measurements. `--router.optimizer.max_threads=4` is faster and very
slightly worse; `=6` is the practical ceiling; `=1` is single-threaded. Measured at widths 8, 12 and 16, the
clock stopped improving while quality kept degrading, so 6 is where the useful part ends. There is also an optional redundant-routing mode
("racing", `--router.racing_enabled=true`) which runs several identical attempts with
different orderings and keeps the best; it is off by default and measured no better than
one attempt.

### How long a board takes

On a current mid-range machine, at the defaults, measured across 36 boards:

| Board | Typical total run |
|---|---|
| a few dozen connections | seconds |
| around a hundred connections | tens of seconds |
| two to three hundred connections | a few minutes |
| a few thousand connections, dense | hours — and the biggest want tens of hours |

Connection count, not net count, is what predicts this, and only loosely: routing
difficulty is a property of the layout. The honest test is the run itself — if routing
is still in its first pass after a few minutes, this is an hours-board, so stop it and
give it a budget in hours.

### What you get at the end

Every run that is not cancelled writes a **run report** next to the log, named for the
board and the time, and prints its path as the last line (the graphical interface offers
to open it). It states how the run ended, how long it took, how many connections were
routed of how many, how many clearance violations there are, and — if anything is
unfinished — every unrouted connection by net, naming both ends:

```
Net 'GND' (1 unrouted connection):
    - J2-A3  ->  U1-4
```

That list is the point: a board that comes back with a handful of gaps can be finished by
hand from the report, pad to pad, without hunting for what is missing. Two runs of the
same board produce two reports, so attempts can be compared.

The **log** is on by default and lives in your user directory —
`%APPDATA%\freerouting\logs\` on Windows, `~/.local/share/freerouting/logs/` on Linux,
`~/Library/Application Support/freerouting/logs/` on macOS. The first line of every run
prints the full path, and the graphical interface shows it in the status bar while a run
is going, next to the elapsed clock.

### The last line tells you what to do next

Every run that produces output ends with one of three sentences (a cancelled run has its
own, below): *Pass finished. No further improvements
found.* (as good as this board gets — a longer budget changes nothing), *Ran out of
time.* (still improving when the clock stopped it — re-run with a longer budget), or
*Stopped on request.* (whatever it had is written).

### Everything else

```bash
java -jar freerouting-anneal-1.1.1.jar --help       # the flag reference
java -jar freerouting-anneal-1.1.1.jar --helpful    # the operating manual, in the jar
```

`--helpful` is the fuller version of this section and ships inside the jar, so it cannot
drift from the build you are holding. The complete settings list, including everything
below, is [`docs/command_line_arguments.md`](docs/command_line_arguments.md); the
task-shaped walkthrough is [`docs/USER-GUIDE.md`](docs/USER-GUIDE.md).

## If you are coming from Freerouting

**Your boards will come out different, and that is the point.**

In upstream 2.3.1 the optimisation stage never executed a useful pass: its re-route was
gated on the flag whose purpose is to hand control *to* it, so every candidate route was
ripped up, judged worse than nothing, and undone. Every board that version produced was
routing-only output.

That is fixed here. The optimiser now does the work that turns a connected board into a
good one, so the same input produces a different — and by our measurements better —
result. If you are comparing against a previous run, expect the difference. If you were
relying on byte-identical output, you no longer have it.

The second change you will notice: **the default time budget is 15 minutes**, not 12
hours. Most boards finish well inside it and say so. Boards that do not will tell you they
ran out of time, which is your cue to give them more.

## Details on the above

### The endings, in full

| ending | what it means |
|---|---|
| `Pass finished. No further improvements found.` | the optimiser stopped finding improvements at these settings. More wall clock alone is not expected to help; a different profile or width still might. |
| `Ran out of time.` | still improving when the clock stopped it. Give it a longer budget and re-run — CLI `--router.job_timeout`, GUI **Settings — Auto-router — Timeout**. |
| `Stopped on request.` | you ended it; the board routed so far is written. |
| `Cancelled on request. No output file was written.` | you cancelled; nothing was saved, and no report is written either. |

Both interfaces tell you this. The command line prints the ending as its last lines, with
the path of the run report; the graphical interface shows a dialog when the run finishes,
with the same ending, the routed-of-total counts and a button that opens the report. The
per-net list of unrouted connections — the output that says *where* the router gave up
— is in that report in both cases.

### Stopping a run without losing it

| how | result |
|---|---|
| **Stop** button (GUI) | current pass finishes, board is kept |
| **`s`** key (CLI, while running) | current pass finishes, board is written |
| **`c`** key (CLI, while running) | run ends, no output file |
| `SIGTERM`, `Ctrl-C`, container stop | jobs are asked to stop, up to 10 s to finish, board written |
| closing the console window on Windows | may lose the board — Windows terminates on its own schedule; press Stop first |
| `kill -9` | nothing is saved |

An interrupted run used to exit with nothing at all. It now keeps what it routed.

### Common flags

- `-de [file]` — load a Specctra design (`.dsn`)
- `-do [file]` — write a Specctra session (`.ses`)
- `--router.job_timeout=01:00:00` — give a stubborn board more time
- `--router.optimizer.enabled=false` — routing only: 2-3x faster overall on
  boards that route quickly, but it saves nothing on a board whose routing stage is
  itself the slow part
- `--router.optimizer.max_threads=4` — trade a little quality for speed (default 2 is the
  measured quality point; 6 is the speed setting; your core count is a ceiling, not a target)
- `-inc GND,VCC` — ignore these net classes
- `-l de` — set the language

Full list: [CLI documentation](docs/command_line_arguments.md).

### Graphical interface

1. **File → Open…** and select your `.dsn`.
2. Click the **auto-router button** in the toolbar (the wand icon; its tooltip reads
   "Start the auto-router and route optimizer") to start routing.
3. Watch progress in the board editor and the footer.
4. Wait. Most boards finish inside the 15-minute default; the run tells you how it ended.
5. **File → Save as…** to write the `.ses`.

Press **Stop** at any point to end the run and keep the board routed so far.

**To change the time budget:** `Settings → Auto-router`. The window is titled
**Auto-router Settings** and the field you want is **Timeout:** — three boxes for hours,
minutes and seconds, with a preview of the total beside them.

```
┌─ Auto-router Settings ──────────────────────────┐
│  Algorithm        [ Current (default) ▾ ]       │
│                     Current (default)           │
│                     v1.9 (legacy)               │
│  ...                                            │
│  Timeout:         [ 00 ] h [ 15 ] m [ 00 ] s    │
└─────────────────────────────────────────────────┘
```

Labels above are verbatim from the program; the arrangement is indicative. `Timeout:` is
the same budget as the CLI's `--router.job_timeout`, so when a run reports that it ran out
of time, that is the box to raise.

The same window is where **Algorithm** lives — `Current (default)` against `v1.9 (legacy)`.
That selector genuinely dispatches in this release; in upstream 2.3.1 it was parsed,
reported as unknown, and ignored, so anyone who compared the two engines through it was
comparing the current engine against itself.

## If you script the DSN export from KiCad

Not our bug, and we cannot fix it from here — but it will stop a batch run dead, so it is
written down rather than left for you to find.

Exporting a DSN from a Python script (`pcbnew.ExportSpecctraDSN`) opens **modal dialogs**
on Windows for things that are only warnings: `Font 'X' not found; substituting 'Y'`,
`Warning: Board outline is malformed`. Each one waits for a human to click OK. Over a
directory of boards, what you see is a script that appears to hang, or a set of boards
that appear to fail to export — when in fact the processes are alive and blocked behind a
dialog nobody is watching. It cost one run here twenty boards that were logged as export
failures and were nothing of the kind.

`wx.DisableAsserts()` does **not** prevent this. That suppresses wx *assertions*; these are
`wxLogMessage` and `wxLogWarning` calls, which wx routes to dialogs by default. Different
mechanism, so the obvious guard looks like it does not work.

What does work, with the order being the load-bearing part:

```python
import wx
_app = wx.App(False)                     # pcbnew expects an app object to exist
wx.Log.EnableLogging(False)              # stop routing log output to dialogs
wx.Log.SetActiveTarget(wx.LogStderr())   # anything that slips through goes to stderr
import pcbnew                            # only now
```

The font lookup fires *during* the `pcbnew` import, so importing it first means the dialogs
are already queued before you disable anything. Run one board per subprocess with a
timeout, too: a board that still manages to block should fail that board, not the run.

**Do not silence the outline warning without reading it.** `Board outline is malformed`
means the Edge.Cuts layer does not close — gaps, overlaps, or zero-length segments — so the
board's extents are unreliable and any router working from that DSN is working from a
guess. The font substitution genuinely is cosmetic. That one is not.

None of this applies if you export interactively from KiCad's GUI, where a dialog is just a
dialog.

## How it works, and what that means for running it

Three stages, in order, each stopping inside the job's time budget:

1. **Fanout** escapes pins out of fine-pitch packages, so routing has pads it can reach.
2. **Auto-routing** connects the nets.
3. **Optimisation** rips up and re-routes individual items, keeping only improvements, and
   stops when a pass stops finding them.

All three derive their deadline from one shared rule (`StageDeadline`): the job's budget
minus a grace period. A stage finishes the pass it is in rather than being cut mid-write,
so the board handed to the next stage is whole.

Time is spent almost entirely in optimisation. Routing is usually seconds; optimisation is
where a board goes from connected to good. `--router.optimizer.enabled=false` gives you
the routing-only result in a fraction of the time, which is also what every build of this
program produced before the optimiser was fixed.

### One budget, not three

`--router.job_timeout` is a budget for **the whole job**, not a per-stage allowance. All
three stages derive their deadline from it. Raising it does not enlarge one stage's slot;
it gives the pipeline more room, and where that room goes depends on what the board needs.

There is no way to give fanout ten minutes and optimisation an hour. That is deliberate —
three independent clocks was the previous arrangement and it is why a stage could be cut
mid-write while another sat idle.

### The optimiser stops on work, not on the clock

It ends when a pass stops finding improvements — not when a timer expires. That is the
difference between this and a timeboxed pass: the stage reports that it is finished
because it *is* finished, and the run says so (`Pass finished. No further improvements
found.`). A board that ends this way gains nothing from a longer budget, which is
information rather than a guess.

The clock is the outer bound, not the stopping rule. It only decides the outcome when the
work has not converged first, and then the run says that instead (`Ran out of time.`).

"Stops finding improvements" is measured in work, not in seconds: a window of items
examined (or, multi-threaded, tasks completed) with nothing accepted ends the stage. A
wall-clock window would stop early on a loaded machine and late on a fast one; a work
window gives the same stopping behaviour on any machine under any load, and that was
verified by running the same configuration on two different machines to identical via
counts. If you want a fixed amount of work instead, `--router.optimizer.rounds=N`
switches the guard off — measured: it does not change the delivered quality.
(The work window governs the default `greedy` strategy; `global_optimal` and the global
phases of `hybrid` publish only at pass end, so they run the full scheduled item set and
are bounded by the job budget and `rounds` instead.)

### The optimiser is multi-threaded, two wide

The optimiser runs multi-threaded by default. Two threads is the measured quality point —
slightly better boards than single-threaded at roughly half the wall clock — and wider is
a trade, not an upgrade: 4 is faster with the best trace length per connection and a few
more vias, 6 is the practical ceiling: the curve saturates between 6 and 8, so beyond 6 the
clock stops improving while quality keeps degrading. Your core count acts as a ceiling only; asking for more than the
machine has is clamped, loudly, and one core runs the single-threaded path honestly. The
optimiser also lives inside a memory budget (60 % of the JVM heap by default,
`--router.optimizer.memory_budget_mb` to set one): width is reduced to fit the budget,
and every reduction is a stated warning, never a silent change.

Three profiles sit on the measured Pareto border — pick by what you want most:

| You want | Use | What you get |
|---|---|---|
| **Best achievable quality** | the default (champion, width 2) | the best boards we measured, at about half the single-threaded wall |
| **Best balance of speed and quality** | `--router.optimizer.max_threads=4` | noticeably faster, best trace length per connection, a few vias more |
| **As fast as possible** | `--router.optimizer.max_threads=6` | the speed ceiling — wall-clock stops improving here |

One measured exception: boards that give the optimiser a large field of individually
cheap improvements run FASTEST single-threaded. The test costs one run: if the
optimisation stage crawls, try `--router.optimizer.max_threads=1` on that board and keep
whichever was faster — the result is the same on this class.

Wider than 6 does not buy speed — it spends the extra cores making the result slightly
worse. The via-lean objective (`--router.scoring.via_costs=100`, for dense boards where
vias cost money) is independent of all of this: it changes what the router optimises
toward, not how many threads chase it, and works identically at any width including
single-threaded.

Routing itself is single-threaded. The only multi-core routing mode is **racing** — and
racing is not parallel work-sharing: it runs N redundant attempts with identical
settings, the only difference being the order items are tried (seeded per thread, so a
race reproduces). The best attempt wins, which may or may not beat a single attempt —
non-deterministically — at N times the memory and no speedup. Measured on every board
tried: the same or a worse result than a single attempt. Opt-in:
`--router.racing_enabled=true` with `--router.max_threads=N`. This fork fixed two
correctness defects in it (deterministic per-thread ordering seeds, memory-bounded
copies) but did not tune the algorithm — an option on the record, not a
recommendation.

### Logging: two streams, and neither is off

Both are **on by default at `INFO`**:

- `logging.console.enabled` / `logging.console.level` — what you see in the terminal
- `logging.file.enabled` / `logging.file.level` / `logging.file.location` — the same at
  file level. **On by default at `INFO`**, written to `logs` inside the user-data
  directory — which now defaults to platform app-data (`%APPDATA%\freerouting` on
  Windows, `~/.local/share/freerouting` on Linux, `~/Library/Application Support/freerouting`
  on macOS), not the temp directory: a log the docs point at must survive a reboot.
  Rolling, capped (20 MB ring), so it cannot fill a disk. `--user_data_path=` moves the
  whole home; `--logging.file.location=` moves just the log.
- `logging.debug.file.location` — a separate, far more verbose stream, useful when a board
  behaves in a way the normal log does not explain.

Raise `logging.file.level` to `DEBUG` and point the debug location somewhere before
investigating anything unusual. The per-net list of unrouted connections is written at
`INFO`, so it is already in the normal stream — it is simply not surfaced anywhere else.

## Known limitations

Stated here rather than discovered by you. Fuller detail in [`MAINTAINING.md`](MAINTAINING.md).

- **Runs are not bit-for-bit reproducible.** The same jar, board and settings can produce
  different output. This is a property of the 2.x engine, not of this fork's changes; it is
  diagnosed and bounded, and deliberately not chased to zero.
- **Fanout can neck down below the declared minimum track width**, on fanout-on runs, which
  is the default. The run now names every such track by net and counts it in the summary, so
  a board that may not be manufacturable says so rather than reporting zero violations.
- **Net count does not predict routing time.** One 111-net board routes in under a second;
  one 95-net board has run for over fifty minutes. Do not size your timeout by net count.
- **`SessionManagerTest` fails on a fresh clone.** It is a pre-existing test-isolation
  problem, it is unowned, and you did not break it.
- **Four surfaces were outside this pass and are unverified here.** They are present,
  they carry their own tests, and they sit on top of the same engine, so a job submitted
  through the API should route with the same defaults and guard as one started from the
  command line. We did not run them, so that is an expectation from reading the code, not
  a measurement — and nothing in this repository is a claim about them:
  - the **REST API server** (off by default) — a job queue with sessions, streamed
    output and per-session logs, which is how you would run this as a service;
  - the **MCP server** (off by default), which exposes that API to LLM tooling;
  - the **KiCad plugin's JSON/API mode**, which drives the API instead of exporting a DSN
    (the classic DSN path is the one this release exercised);
  - the **Eagle, EasyEDA and Target3001! integration notes**, inherited as-is.
- **The graphical interface is the less-walked path.** Its finishing dialog, the report
  and the status-bar log path are all present and tested, but far more of this release was
  exercised headless than through the window. If something behaves oddly there, that is
  where to look first.

## What this fork changed

[`RELEASE-NOTES.md`](RELEASE-NOTES.md) states each change, its effect, and how it was
measured. The full evidence base — methodology, defect register, measurements,
architecture-as-found, the maintainer handover — lives in [`docs/fork/`](docs/fork/). [`WHAT-CHANGED.md`](WHAT-CHANGED.md) maps each fix to the commit that made it and
the test that proves it — written for anyone who wants to take individual fixes rather than
the whole fork.

## Upstream services — theirs, not ours

These belong to the Freerouting project and are listed so you know where they are, not
because this fork provides them:

- **Hosted API** at `https://api.freerouting.app/v1` — upstream's service. This fork is not
  connected to it and is not covered by it. See [their docs](docs/API/API_v1.md).
- **MCP server** — `npx -y @freerouting/freerouting-mcp-server` publishes upstream's
  releases, not this fork's. See the [MCP guide](docs/API/MCP.md).
- **Docker images** at `ghcr.io/freerouting/freerouting` — upstream's builds.
- **Integrations** with [KiCad](https://www.kicad.org/),
  [EAGLE](http://eagle.autodesk.com/), [Target3001!](https://ibfriedrich.com/en/index.html),
  [EasyEDA](https://www.easyeda.com/), [tscircuit](https://tscircuit.com/) and
  [pcb-rnd](https://www.pcb-rnd.com/) were built for upstream. The KiCad plugin in this
  repository is built against this fork's jar; the others are not.



## Running the API yourself

The hosted service above is not ours, but the API server in this source is: you can
[self-host it](docs/self-hosting.md) and route through your own instance, with no traffic
leaving your machine.

## Building it yourself

```bash
./gradlew executableJar      # Linux, macOS, Git Bash
gradlew.bat executableJar    # Windows
```

Full instructions, including the tests and their known failure, are in
[`docs/developer.md`](docs/developer.md).

## Licence and credit

GPL-3.0, same as upstream. Freerouting was written by Alfons Wirtz and developed since by
Andras Fuchs, Michael Hoffer, Andrey Belomutskiy and contributors. This fork is their work
plus a set of fixes; the copyright and the credit are theirs.

**If you want to support the people who wrote this program**,
[sponsor the upstream project](https://github.com/sponsors/andrasfuchs). Nothing in this
fork asks for anything.


Checkout. Ekim Dark.
