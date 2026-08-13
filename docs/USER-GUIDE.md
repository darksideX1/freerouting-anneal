# User guide — routing your first board

For someone who has a board to route, probably exported from KiCad, and has not used
Freerouting before. It is organised by what you are trying to do, not by flag.

The program carries its own manual: `--helpful` prints how the stages work, how long to
give it and how to read the ending, from inside the jar, so it cannot drift away from the
build you are holding. This page is the walkthrough around it and points at it rather than
repeating it.

---

## 1. Install and check

You need a **Java 25 runtime** ([Adoptium Temurin](https://adoptium.net/temurin/releases/))
and the jar from the
[Releases page](https://github.com/darksideX1/freerouting-anneal/releases). Nothing else is
installed and nothing is written outside your user directory.

Check both at once:

```bash
java -jar freerouting-anneal-1.1.1.jar --help
```

The first line names the version and the commit the jar was built from. Quote that line in
any bug report; it identifies the build unambiguously where a version number does not.

**macOS:** launch from the Terminal. Starting from Finder is not supported.

---

## 2. Route a board

```bash
java -jar freerouting-anneal-1.1.1.jar -de MyBoard.dsn -do MyBoard.ses
```

`-de` is the Specctra design you exported from your EDA tool; `-do` is the session file to
write, which you import back into it. **The default budget is fifteen minutes for the whole
run**, and most boards finish well inside it.

Three stages run in order, and the log names each as it starts:

1. **Fanout** escapes pins out of fine-pitch packages, so routing has pads it can reach. It
   is skipped, with a line saying so, on a board with no SMD pins.
2. **Auto-routing** connects the nets. This is usually seconds.
3. **Optimisation** rips up and re-routes individual items one at a time, keeping only
   improvements. This is where nearly all of the time goes, and it is what turns a
   connected board into a good one.

You can watch that happen — progress lines carry the elapsed time against the budget, like
`[routing 0:00 of 15:00]`.

### The graphical interface

1. **File → Open…** and select your `.dsn`.
2. Click the **Magic Wand** to start routing.
3. Watch the board editor and the footer.
4. **File → Save as…** to write the `.ses`.

The time budget is at **Settings → Auto-router**, in the window titled **Auto-router
Settings**, field **Timeout:** — three boxes for hours, minutes and seconds. It is the same
budget as the CLI's `--router.job_timeout`. The same window is where **Algorithm** lives,
offering `Current (default)` against `v1.9 (legacy)`.

---

## 3. Read the end of the run

A finished run tells you what it did and what to do about it. On a board that came out
clean, the last lines are:

```
Pass finished. No further improvements found. A longer --router.job_timeout will not
change this result.
Run report: .../logs/ecc83-pp_20260812-094541_final-report.txt
```

The first of those two lines is the one that decides your next move. There are four
endings, and they mean different things:

| ending | what it means |
|---|---|
| `Pass finished. No further improvements found.` | as good as this board gets. A longer timeout changes nothing. A longer timeout will not change this result. (Runs are not bit-for-bit reproducible, so a re-run may differ in detail — but not in the ending.) |
| `Ran out of time.` | it was still improving when the clock stopped it. Give it a longer budget and re-run. |
| `Stopped on request.` | you ended it; the board routed so far is written. |
| `Cancelled on request. No output file was written.` | you cancelled; nothing was saved. |

Before this distinction existed, a user looking at unrouted nets had no way to tell whether
to raise the timeout or stop trying — which is the only decision available at that point.

**These lines are printed by the command-line interface.** If you are routing in the GUI
and cannot find them, you are not missing a setting; run the same board from the CLI. The
run report in the next section is written by both.

---

## 4. Read the finishing report

Every run that produces a board writes a plain-text report and prints its path on the last
line. It lands next to the log, and it is named for your board plus a timestamp — so
running the same board again with a longer budget produces a sibling file rather than an
overwrite, and you can compare two attempts.

```
<boardname>_<YYYYMMDD-HHMMSS>_final-report.txt
```

It opens with the ending and the counts:

```
Pass finished. No further improvements found. ...
Elapsed:    0:00:11
Routed:     104 of 113 connections
Unrouted:   9
Violations: 0
```

and then — this is the part worth keeping — it lists every connection the router did not
make, by net, with the two pins that should have been joined:

```
Unrouted connections, by net (pin -> the other end it should reach):
  Net '/ampli_ht_horizontal/PIEZO_OUT' (1 unrouted connection):
    - R307-1  ->  R306-1
  Net 'Net-(U102-CAP-)' (1 unrouted connection):
    - U102-4  ->  C105-2
```

That is the output that says *where* the router gave up, which is what you take back to
your layout. A cancelled run writes no report, because cancelling means discarding the
work.

**Violations** counts clearance problems on the delivered board. The summary also flags
tracks laid below your board's declared minimum width, if any: the fanout stage necks down
at pin exits to escape fine-pitch packages, and where it goes below the board minimum every
such track is named by net and counted, rather than the run reporting a clean board. A
board carrying one may not be manufacturable as routed.

---

## 5. Give it more time

If the run reported `Ran out of time.`, raise the budget and run it again:

```bash
java -jar freerouting-anneal-1.1.1.jar -de MyBoard.dsn -do MyBoard.ses \
  --router.job_timeout=01:00:00
```

The format is `HH:MM:SS`. **Use `--flag=value`, never `--flag value`** — the
space-separated form is refused at ERROR with the flag named, and the run continues without
that setting applied.

Three things about how the budget behaves:

- **It is one budget for the whole job, not a per-stage allowance.** All three stages
  derive their deadline from it, and each finishes the pass it is in rather than being cut
  mid-write, so the board handed on is whole. There is no way to give fanout ten minutes
  and optimisation an hour.
- **Raising it only helps the endings that say so.** `No further improvements found` means
  the optimiser stopped because it was finished, not because a timer fired; another hour
  buys nothing.
- **The curve is front-loaded.** Measured on a four-layer board with declared planes: with
  the optimiser disabled, 17 unrouted; with about 149 s of optimiser, 13; with about 449 s,
  12. Most of the gain arrives early and the tail is long and flat.

### When a board is going to take hours

**Net count does not predict difficulty**, so do not size your timeout by it. One 111-net
board routes in under a second; one 95-net board has run for over fifty minutes.

The reliable signal is the run itself, and `--helpful` states the rule: if routing pass #1
is still going at minute five, this is an hours-board — stop it, set a budget in hours, and
let it run. The board is saved at the wall either way.

If the board comes back unfinished, raise the timeout **once**. If the unrouted count does
not move and the run reports no further improvements, the board is telling you something
about placement rather than about the router.

---

## 6. Trade quality for speed

Defaults are the measured optimum. Everything below is a real trade, not an upgrade:

```bash
--router.optimizer.enabled=false     # routing only: 2-3x faster where routing is quick
--router.optimizer.max_threads=4     # faster, best trace length, a few vias more
--router.optimizer.max_threads=6     # the speed setting
```

The optimiser is multi-threaded and **two threads wide by default** — the measured quality
point, giving slightly better boards than single-threaded at roughly half the wall clock.
Four is the balanced setting; six is where wall clock saturates; beyond eight, time stops
improving and quality degrades. **Your core count is a ceiling, not a target**: asking for
more than the machine has is clamped and the run says so —

```
WARN   Optimizer width 64 clamped to 16: this machine has 16 logical processors.
```

Routing itself is single-threaded, and so is fanout. `--router.optimizer.enabled=false`
gives you the routing-only board, which is also what every build of this program produced
before the optimiser was fixed.

Two other flags worth knowing:

- `-inc GND,VCC` — ignore these net classes.
- `--router.scoring.via_costs=100` — the via-lean profile: fewer vias per connection, at
  the cost of about one completed connection on a dense board. Use it where there are vias
  to remove; small boards already sit at their via floor, where it moves nothing and only
  costs length.

`--help` is the flag reference (the full settings list is `docs/command_line_arguments.md`), and `docs/command_line_arguments.md` explains each one.

---

## 7. Stop a run without losing it

| how | result |
|---|---|
| **Stop** button (GUI) | current pass finishes, board is kept |
| **`s`** key (CLI, while running) | current pass finishes, board is written |
| **`c`** key (CLI, while running) | run ends, no output file |
| `SIGTERM`, `Ctrl-C`, container stop | jobs are asked to stop, up to 10 s to finish, board written |
| closing the console window on Windows | may lose the board — press Stop first |
| `kill -9` | nothing is saved |

The CLI prints the keys under the progress line while a route is running. An interrupted
run used to exit with nothing at all; it now keeps what it routed.

The Windows case is the one exception and it is not fixable from inside the program:
closing a console window gives the process a few seconds by the system's own schedule, and
saving a board can need longer. If a long route matters, press Stop, let it finish, then
close whatever you like.

---

## 8. Where the log lives

The log is on by default and its path is the very first line the program prints:

```
INFO   Full log: /home/you/.local/share/freerouting/logs/freerouting.log
```

It lives in your platform's application-data directory, alongside `freerouting.json` and
the run reports:

| platform | location |
|---|---|
| Windows | `%APPDATA%\freerouting` |
| Linux | `~/.local/share/freerouting` |
| macOS | `~/Library/Application Support/freerouting` |

It is capped at 20 MB and rolls, so it cannot fill a disk. `--logging.file.location=` moves
just the log; `--user_data_path=` moves the whole directory. There is a second, far more
verbose stream at `logging.debug.file.location`, worth turning on when a board behaves in a
way the normal log does not explain.

If your board did something unexpected, the log is also where to check that your flags were
applied: a line reading `Unknown settings property` means a setting did not take, and the
run will still exit 0.

---

## 9. If you script the DSN export from KiCad

Not a defect in this program, and it will stop a batch run dead, so it is written down
rather than left to be found.

Exporting a DSN from a Python script (`pcbnew.ExportSpecctraDSN`) opens **modal dialogs** on
Windows for things that are only warnings — a substituted font, a malformed board outline —
and each waits for a human to click OK. What you see is a script that appears to hang, or
boards that appear to fail to export, when the processes are alive and blocked behind a
dialog nobody is watching.

`wx.DisableAsserts()` does not prevent it; that suppresses wx *assertions*, and these are
log calls that wx routes to dialogs. What works, with the order being the load-bearing
part:

```python
import wx
_app = wx.App(False)                     # pcbnew expects an app object to exist
wx.Log.EnableLogging(False)              # stop routing log output to dialogs
wx.Log.SetActiveTarget(wx.LogStderr())   # anything that slips through goes to stderr
import pcbnew                            # only now
```

The font lookup fires *during* the `pcbnew` import, so importing it first queues the
dialogs before you have disabled anything. Run one board per subprocess with a timeout too,
so a board that still blocks fails that board rather than the run.

With this snippet the warnings are not lost — they land on stderr. Capture each
subprocess's stderr and search it for `outline` before trusting that board's export.

**Do not silence the outline warning without reading it.** `Board outline is malformed`
means the Edge.Cuts layer does not close, so the board's extents are unreliable and any
router working from that DSN is working from a guess. The font substitution genuinely is
cosmetic; that one is not.

None of this applies if you export interactively from KiCad's GUI.

---

## 10. Two things to expect, so they are not surprises

**Your boards will come out different from previous runs, and different between runs of
this build.** The optimisation stage in upstream 2.3.1 never executed a useful pass, so
every board that version produced was routing-only output; here it runs, and the result
changes. Separately, the same jar, board and settings can produce different output run to
run. That is a known and documented property of the current engine, deliberately not chased
to zero because fixing it means changing when the algorithm stops. If you need
reproducibility more than quality, the 1.9 engine is deterministic on every board tested
and is selectable — `--router.algorithm=freerouting-router-v19`, or **Algorithm** in the
GUI's Auto-router Settings.

**An incomplete board is a normal result, not a failure.** By default the exit status is 0
if a result was produced and 1 if not, so a board with unrouted connections exits 0. If you
are driving this from CI and want to branch on the outcome, opt in with
`--outcome_exit_codes=true`:

| Code | Meaning | What it is telling you |
|---|---|---|
| `0` | COMPLETE | routed, nothing left, no clearance violations |
| `3` | INCOMPLETE | the router finished and handed work back — move components, re-route |
| `4` | STOPPED_EARLY | the clock or an abort cut it short — a longer budget may improve it |
| `1` | FAILED | no result produced (bad input, unwritable output) |

`3` and `4` are deliberately different: a board the router *finished* with wants your
attention on the layout, and a board it was *interrupted* on may just want more time.

---

## Where to go next

- `--helpful` — the operating manual inside the jar.
- `--help` — every flag.
- [`docs/command_line_arguments.md`](command_line_arguments.md) — each flag explained.
- [`docs/settings.md`](settings.md) — the settings file and where a value can come from.
- [`README.md`](../README.md) — known limitations, and what this fork changed.
