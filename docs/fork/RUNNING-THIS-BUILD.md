# Running this build

This page is the operating guide for the build itself: how to run it, what it prints, and how to tell one build from another.

## Identity — the build tells you which build it is

The first line of any run names the commit it was built from:

```
INFO  Freerouting v1.1.1 (build 4fcb7e68, build-date: 2026-08-12)
```

`build a960ca58` is the commit. Quote that in any bug report and it is unambiguous.
Get it without routing anything:

```bash
$FR_JAVA -jar $FR_JAR --help | head -1
```

**A `-dirty` suffix means the tree was modified when that jar was built.** It is not
the commit it names — nobody can reproduce it from that sha, including us. Treat a
`-dirty` jar as unidentified: fine for your own experiment, never for a result anyone
else has to trust.

Builds without git (a source tarball, or upstream's own releases) print the plain
`v1.1.1 (build-date: …)` exactly as before.

> This used to be your job. Earlier revisions of this document asked you to record the
> sha256 and the commit by hand, because the version string was meaningless for
> provenance — two builds 544 commits apart print the same thing, and three
> byte-different jars were cut here in a single day all claiming `v2.3.1-SNAPSHOT`.
> Asking a human to carry information the program already has is a documentation
> workaround for a product defect, so the program now carries it.
> `VERSION-PROVENANCE.md` has the incident that produced the rule.

Still worth recording the sha256 of what you were handed, since that identifies the
FILE rather than the source it was built from:

```bash
sha256sum $FR_JAR
```

**Only the current cut is distributed.** Superseded jars are deleted from the drop
directory rather than left sitting there with a warning attached, because a warning in a
document does not stop somebody reaching for the file that is in front of them —
*fix(guard): board/ is in the logging guard, and the comment no longer contradicts it* in particular could hang on a job timeout and write nothing.

Nothing is lost by that. Every cut is named by its commit and the tree is committed, so
any earlier build can be reproduced exactly:

```bash
git checkout <sha> && ./gradlew clean executableJar
```

`CHANGELOG.md` records what each one changed. If you are holding a jar that is no longer
in the drop directory, its banner tells you which one it is — and you should replace it
with the current cut.

## Why use this instead of the stock v2.2.4 jar

Not a preference — the stock jar has defects that silently corrupt measurements:

| Defect in stock | What it does to your results |
|---|---|
| `--router.algorithm=...-v19` is parsed, logged "Unknown", then **ignored** | You believe you compared v1.9 against v2. You compared v2 against v2. |
| Routed board saved only from a progress-event listener | A router that emits no progress reports **success** and writes a **0-byte `.ses`** |
| JVM SIGSEGV, roughly **1 run in 5** | Runs vanish; if you retry silently, your sample is biased toward the runs that survived |
| A pass that crashes returns the same value as a pass that finished | A `NullPointerException` mid-route presents as a completed board |
| No fanout phase at all in the v2 engine | `router.fanout.enabled` is rejected; a fanout 2×2 runs fanout-free in all four cells |

Measured on `Issue508-DAC2020_bm01.dsn` (195 nets), medians of n=3, against upstream
`4a0ae4b7`: crash rate ~1-in-5 → **0 in 20**; memory churn 1,599 → **210 GB**; peak
heap 1,165 → **~200 MB**; wall clock 3m32s → **1m47s**. Routing quality same or
better (5 unrouted both; 2 DRC violations → 0).

**Honest caveat:** this build is 544 commits ahead of stock v2.2.4. Differences you
observe against that jar are *our fixes plus 544 commits of upstream change*, not our
fixes alone. If you need to separate those, ask me for the clean-master arm — the
pristine `4a0ae4b7` checkout already stands on the routing machine.

## Requirement: a Java 25 runtime

**This will not run on Java 21.** Not a packaging choice — the upstream codebase uses
Java 22+ unnamed variables (`_`), at 74 sites. Compiling for 21 fails with 74 errors;
I tested it rather than assuming.

You do **not** need to replace your system Java. Drop a Temurin 25 tarball next to it
and point at it for this run only:

```bash
# one-time, no admin, does not touch your existing Java 21
curl -L -o /tmp/jdk25.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse'
mkdir -p ~/jdk25
tar xzf /tmp/jdk25.tar.gz -C ~/jdk25 --strip-components=1

# per run -- set this once per shell and every example below works
export FR_JAVA=~/jdk25/bin/java
export FR_JAR=freerouting-anneal-1.1.1.jar
$FR_JAVA -jar $FR_JAR ...
```

Verify before your first real run: `$FR_JAVA -version` must print 25.x.

`~/jdk25` is only where THIS document happens to put it. If you already have a
Java 25 anywhere, point `FR_JAVA` at that instead — nothing here depends on the
path. (On our own the routing machine the tarball is not at `~/jdk25` at all; following this
literally sends you to a path that does not exist on that box.)

## The program documents its own flags

```bash
$FR_JAVA -jar $FR_JAR --help
```

Prints the full parameter list. Worth running before you ask anyone anything — this
document covers what is specific to THIS build, not the whole flag surface.

Terminal noise you may see, so you do not think something is wrong:

- `WARN Couldn't get screen resolution` appears whenever there is no display. Harmless,
  and it goes away with `-Djava.awt.headless=true`, which you want anyway.

**In *merge: land the stabilization lane onto lane-work* and earlier only** — both fixed after that cut, so expect them on the
jar you were handed and expect them gone on the next one:

- `INFO New version available: v2.3.0` while the build reports itself `v2.3.1-SNAPSHOT`.
  Upstream's check compared the two strings for inequality rather than for order, so a
  build AHEAD of the newest release announced that release as an upgrade. It also knows
  nothing about this fork. Ignore it.
- **Every ERROR line printed twice.** The logger attached both a stdout and a stderr
  appender to the root, so an error went to both and any terminal that merges the
  streams showed it doubled. Errors now go to stderr only, which is what a shell user
  expects and what lets you filter them.

## Invocation

```bash
$FR_JAVA -Xmx3g -Djava.awt.headless=true \
  -jar $FR_JAR \
  -de board.dsn \
  -do out.ses \
  --gui.enabled=false \
  --router.algorithm=freerouting-router-current \
  --router.fanout.enabled=false \
  --router.job_timeout=00:15:00
```

Add `--outcome_exit_codes=true` if a script is reading the exit status; see the
exit-code section below.

> **USE `--flag=value`, NEVER `--flag value`.** The space-separated form used to be
> parsed and **silently ignored**. As of this build it is **refused by name at ERROR**,
> naming the argument and the working form — so you find out at startup instead of from
> a wrong result days later. The warning below is kept because it explains what the
> guard is protecting you from, and because a jar older than this one still fails
> silently. An earlier version of this document used it, which meant anyone
> following it believed fanout was off while it was running — the reproducibility dial
> did nothing and the results were from the nondeterministic path. Measured on one board:
> `--router.fanout.enabled false` → 4+ fanout passes logged; `--router.fanout.enabled=false`
> → zero. Found by an independent bench, in this document's own example, and it is exactly the failure
> class this document warns about two paragraphs below.
>
> **Also pass `-Djava.awt.headless=true`.** `--gui.enabled=false` is an application flag;
> it cannot stop the JVM from being able to create windows. Without the JVM flag, an
> uncaught exception can open a **modal dialog that waits for a click** — the process does
> not exit, writes nothing further, produces no artifact, and burns its entire timeout
> while looking identical to "still working" from the outside.

Notes that cost people time:

- `-gui.enabled false` is required headless, otherwise you get a screen-resolution
  warning and possibly a hang.
- `--router.algorithm` now genuinely selects. `freerouting-router-current` = v2,
  `freerouting-router-v19` = the v1.9 algorithm. An unrecognised value is now
  reported as unrecognised instead of being silently swallowed.
- `--router.fanout.enabled false` is the reproducibility dial. Fanout gives **each
  individual pin** its own 10-second budget, so anything that changes machine speed
  changes the routed result. Off costs some quality and buys stability.
- **Always check the log for `Unknown settings property`.** That warning is how the
  v1.9 selector defect hid for so long. Exit code 0 does not mean your flags applied.
- `-Xmx3g` is enough now (peak heap ~200 MB). the routing machine is shared — please do not run more
  than two of these concurrently; I took the box down with four.

## Where a JVM crash leaves its debris

If the JVM itself dies -- not the router throwing, the JVM -- it writes an `hs_err_pid*.log`,
possibly a `replay_pid*.log`, and possibly a core dump. By default all of it lands in the
**current working directory**, which for most people is the directory holding the board
they were routing. A multi-hundred-megabyte core file next to someone's PCB is a poor way
to find out the program crashed.

This cannot be fixed from inside the program -- the paths are fixed when the JVM starts, so
they belong on the command line:

```bash
-XX:ErrorFile=/tmp/freerouting/hs_err_pid%p.log -XX:ReplayDataFile=/tmp/freerouting/replay_pid%p.log
```

Core dumps are the operating system's business, not the JVM's: on Linux they follow
`ulimit -c` and `/proc/sys/kernel/core_pattern`. If you run this unattended, set
`ulimit -c 0` unless you specifically want them.

Worth knowing rather than acting on: the reproducible SIGSEGV that made this matter -- a C2
compiler crash in `MazeSearchAlgo::expand_to_room_doors`, roughly one run in five -- was
fixed earlier in this fork and has not recurred in 20 runs. These flags are insurance now,
not a workaround.

## Bounding a run by wall clock

```bash
--router.job_timeout=00:15:00        # HH:mm:ss, or mm:ss, or ss
```

The shipped default is **15 minutes** (upstream shipped 12 hours — effectively no bound
for an unattended run). Set it to something you are actually willing to wait.

The flag name is `job_timeout`, **not** `jobTimeoutString`. The field is called the latter
in the source and the CLI matches on the serialised name; the wrong form is now refused
out loud rather than ignored, so you will know immediately if you guess it.

What you get when the clock runs out:

```
Job 'X' finished with state: TIMED_OUT (stopped at the job time limit of 00:15:00
  -- the result is partial) (270 unrouted, 28 clearance violations)
```

and the partial board **is written** to your `-do` file. A time-boxed run is supposed to
hand you the best board it had when the clock ran out; that is the point of asking for one.

Two things worth knowing about the shape of it:

- The deadline is enforced with a **30-second grace period** — the router is asked to stop,
  and is given that long to stop tidily rather than being cut mid-write. A 20-second limit
  therefore ends at roughly 30 seconds, not 20. At a 15-minute limit the grace is noise.
- Process overhead beyond the job itself is about **3 seconds** (JVM start, DSN parse,
  writing the `.ses`). Measured, not estimated.

Earlier builds of this jar had a defect here: a timed-out job left the headless CLI
spinning forever and never wrote the output file, so the run had to be killed from outside
and produced nothing. If you are on a jar cut before this section existed, do not rely on
`--router.job_timeout`.

## Exit codes — telling a clean run from a dirty one

By default the exit status is what it has always been: **0** if a result was produced,
**1** if not. A board with 270 unrouted connections exits 0, because an incomplete board
is a normal autorouter result, not a failure.

If you are driving this from CI and want to branch on the outcome, opt in:

```bash
--outcome_exit_codes=true
```

| Code | Meaning | What you should do |
|---|---|---|
| `0` | COMPLETE | routed, nothing left, no clearance violations |
| `3` | INCOMPLETE | the router finished and handed work back — move components, re-route |
| `4` | STOPPED_EARLY | the clock or an abort cut it short — a longer budget may improve it |
| `1` | FAILED | no result produced (bad input, unwritable output) |

`3` and `4` are deliberately different. A board the router *finished* with wants your
attention on the layout; a board it was *interrupted* on may just want more time. Telling
someone to go move components when what they needed was five more minutes is the whole
reason these are not one code.

It is opt-in precisely because INCOMPLETE is normal: turning distinct codes on by default
would make every existing CI job that tests `$? -eq 0` start failing on boards that are
behaving exactly as expected.

## One command that tells you what happened

```bash
run-report.sh <board.dsn> [repeats]
```

Runs a board N times and prints, in one place: the outcome and what it means, whether the
output is **repeatable**, what is left unrouted **by net**, and anything the program
refused to apply. Everything it shows already existed — spread across a log nobody reads
and an exit status that said nothing.

It refuses to judge repeatability on a run cut short by the deadline: truncated runs
differ because of the clock, not the engine, and reporting "not repeatable" there would
blame the engine for your time limit.

It lives outside the repository on purpose — it presumes our paths, so it is a lane tool
rather than something to offer upstream. Set `FR_JAR` and `FR_JAVA` before running it.

## Driving it over HTTP

Only if you are using the REST server (`--api_server.enabled=true`). Three things are
not obvious and cost four failed attempts to discover against a running server:

```
Freerouting-Environment-Host: YourTool/1.0     # ALWAYS required
Freerouting-Profile-ID: <any uuid>             # only when authentication is ON
```

The first identifies your EDA tool and is always required; without it you get a **400**
that spells out the expected `<ToolName>/<Version>` format.

The second is only needed when authentication is on. Running locally with
`--api_server.authentication.enabled=false`, you can call the API with just the tool
header and the server treats you as an anonymous local user:

```bash
curl -X POST -H 'Freerouting-Environment-Host: CurlTest/1.0' \
  http://127.0.0.1:37864/v1/sessions/create
```

> Before this build, that setting disabled only one of two gates: the Bearer-key check
> honoured it, while the profile-header check consulted nothing and answered with a
> **500** about a header you had never heard of. A setting whose name promises more than
> it does is the same defect this fork exists to remove, so it now does what it says.
> With authentication ON nothing changed — the header is still required.

And `PUT /v1/jobs/{id}/start`, **not POST** — a wrong method surfaces as a **500
wrapping "HTTP 405"**, so it reads like a server fault rather than your mistake.

The working sequence:

```
POST /v1/sessions/create                 -> sessionId
POST /v1/jobs/enqueue                    -> jobId   {session_id, name, router_settings}
POST /v1/jobs/{jobId}/input              -> upload  {job_id, data: <base64 dsn>}
PUT  /v1/jobs/{jobId}/start
GET  /v1/jobs/{jobId}                    -> poll until state is terminal
GET  /v1/jobs/{jobId}/output             -> the board
```

A job stopped by its deadline (`TIMED_OUT`) **downloads normally** — 200 with the
partial board. Before this build it returned 400 "has no valid output" for a board that
existed and that the CLI would happily write to disk. All three SSE streams
(`/output/stream`, `/output/json/stream`, `/logs/stream`) now close when the job reaches
a terminal state; previously they stayed open re-sending every 500 ms.

Walked end to end over real HTTP, 10 checks, 10 passing.

## Canary comparison before you trust it

Do not swap jars on faith. Run both on one board you already have banked numbers for:

1. Same board, same flags, **n ≥ 5 per jar** (single runs are meaningless — see below).
2. Compare **median and range** of: unrouted count, DRC violations, wall clock,
   and `.ses` file size.
3. Expect: quality same or better, wall clock roughly halved, `.ses` never 0 bytes.
4. A difference only counts if it **exceeds the within-arm spread** of both arms.
5. If quality is *worse* on this build, tell me with the board — that is a regression
   in our work and I want it, not a reason for you to quietly stay on stock.

## Known limitation you must design around

**The engine is not deterministic.** Same jar, same board, same settings can produce
different routed output. Fanout-off reduces it but does not eliminate it; a second
source is bounded to the 544-commit range and not yet localised. One of our three test
boards is stable in both builds, so it is board-dependent.

Practical rule: **treat any single run as one sample.** Median and range over n≥5, and
a delta must exceed the within-arm spread before you believe it. v1.9 was deterministic
on every board we tried — if you need reproducibility more than v2's quality, that is a
real option now that the selector works.

## If you rebuild it yourself

`settings.gradle` needs the foojay toolchain resolver at `1.0.0` (not `0.8.0`) for
Gradle to provision JDK 25. Everything else is stock `./gradlew build`.

## Logs — where they are, and why you probably want to move them

File logging is **on** at INFO, and since 1.1.1 it writes into `logs/` inside the
user-data directory, which defaults to platform app-data:

```
Linux    ~/.local/share/freerouting/logs/freerouting.log
Windows  %APPDATA%\freerouting\logs\freerouting.log
macOS    ~/Library/Application Support/freerouting/logs/freerouting.log
```

(Upstream wrote into the system TEMP directory; that was replaced — a log the docs
point at must survive a reboot. The first log line of every run prints the full path.)

**Move it if the logs matter to you.** Temp is the one directory the OS is entitled to
empty: Windows Disk Cleanup and Storage Sense clear it, many Linux distributions wipe
`/tmp` on boot, and `systemd-tmpfiles` ages files out. The log of an intermittent crash is
therefore the file most likely to be gone by the time you go looking for it.

```bash
--logging.file.location=/path/to/routing.log   # a .log file, or a directory
--logging.file.enabled=true|false
--logging.file.level=INFO|DEBUG|TRACE
```

`FREEROUTING__USER_DATA_PATH` moves the whole user-data directory, log included.

**Two files, deliberately.** `freerouting.log` carries INFO and above. `freerouting-debug.log`
carries DEBUG and TRACE only, and nothing else. They are complementary — raising the file
level to TRACE does not pollute the normal log, so you can leave debug on without making
the ordinary log unreadable.

**Rotation:** 20 MB per file, four files kept (`routing.log.1` … `.4`), oldest discarded.
Tunable for testing with `-Dfreerouting.logging.file.maxSize=64KB` and
`-Dfreerouting.logging.file.maxFiles=4`.

> **`-D` system properties do NOT control enabled/level/location.** The application
> overwrites those three from its own settings at startup, so a `-D` is accepted by the JVM
> and silently discarded. Use the `--logging.file.*` flags above. `maxSize` and `maxFiles`
> are not overwritten, which is why those two are `-D`.

## Questions

Report them as issues. If something here is wrong, that is a defect in this document
and I would rather fix it than have you work around it.

## How long a run takes, and how to change it

**This build bounds a route to 15 minutes by default** (an earlier experimental cut used
three minutes; that number survives in old logs only). Upstream's default is twelve
hours. That ceiling was harmless there only because the optimizer never really ran — it
stopped after a single pass that improved nothing, so nothing ever approached the limit.
With that fixed, an unbounded default means a heavy board can spend a quarter of an hour
polishing a result that stopped getting measurably better after the first couple of minutes.

The three stages share one budget:

```
freerouting -de board.dsn -do board.ses          # fifteen minutes, all in
freerouting -de board.dsn -do board.ses --router.job_timeout=00:10:00
freerouting -de board.dsn -do board.ses --router.optimizer.enabled=false
```

The optimizer derives its own deadline from the job's and stops just inside it, so the stage
finishes by choice rather than being cut off mid-pass with a half-applied change.

### What more time actually buys

Measured on a 4-layer board with declared planes, same build, same input:

| optimizer budget | unrouted nets | score |
|---|---|---|
| optimizer disabled | 17 | 886.05 |
| ~149 s (the shipped default) | 13 | 910.29 |
| ~449 s (unbounded) | 12 | 916.36 |

Tripling the optimizer's time bought **one more net**. That is the shape of the curve on
every board measured so far: most of the gain arrives early, and the tail is long and flat.
Budgets of 240 s and 480 s produced no measurable difference in the routed result.

So:

- **Want a better board?** Raise `--router.job_timeout`. It will keep finding improvements,
  slowly, and it will stop when you tell it to rather than when it feels like it.
- **Want the result now?** Set `--router.optimizer.enabled=false`. Routing alone is a few
  seconds on boards where the full run is minutes.
- **Want neither decision?** The default is chosen to sit past the knee of that curve.

The point is not that three minutes is optimal for your board — it is that the run is
*bounded and predictable*. A route either finishes inside the budget you set, or it hands
back the best board it had when the clock ran out. It does not run until you give up on it.

## Racing mode: works, off by default, and not recommended

Racing runs several router threads on copies of the same board with different item orderings
and keeps the best result. It is present, it is correct, and it is **off**. We are not
developing it further, and this section exists so the reasoning is on the record rather than
in someone's memory.

**It was broken and is now correct.** Two defects were found and fixed while evaluating it:

- a racing pass read a board another thread was still writing;
- the clearance-candidate sort collected into a `TreeSet` whose tie-break came from a static,
  unsynchronised counter. Two racing threads could be handed the same id, and a `Set` then
  silently discarded a real obstacle — a clearance check that never ran, on a board that
  looked finished (defect 27).

The second is worth noting for anyone tempted to switch it on in an older build: the failure
was silent and produced a board with an unchecked obstacle, not a crash.

**It does not win.** Best-of-N orderings did not beat one good attempt on the boards measured.
That is the whole premise of the mode, and it did not hold.

**And the case for it has weakened underneath it.** Racing spends N times the memory and N
times the CPU to buy a chance at a better ordering. Meanwhile the single-threaded path got
substantially faster: auto-routing on `planes-declared` is 27.0 s against the 2023 original's
44.8 s, and the optimiser — which had never executed a useful pass in this fork before defect
25 was fixed — now does the work that actually improves a board. Spending four cores on
orderings is a poor trade against spending them on nothing and finishing sooner.

**If you enable it anyway:** `--router.racing_enabled=true --router.max_threads=N`. Both are
needed: `BatchAutorouter` takes the racing path only when racing is enabled AND the thread
count exceeds one. The thread flag on its own changes nothing -- routing stays
single-threaded, and since 1.1.1 the conflicting half-request is warned about loudly
instead of being resolved in silence. It is bounded and it will not corrupt a
board. It will use N times the heap, and on every board measured it returned the same or a
worse result than a single thread.

Board partitioning — routing quadrants in parallel and stitching the crossings — was scoped
and deliberately not attempted. It is a larger change than racing and shares racing's problem:
the single-threaded path is now fast enough that the coordination cost is hard to justify.

## Stopping a run, and what you get back

A route can be ended at any point and the board routed so far is written. That was not true
before: an interrupted run used to exit with nothing at all.

| how you end it | what happens |
|---|---|
| **Stop** button (GUI) | current pass finishes, board is kept |
| **`s`** (CLI, while running) | current pass finishes, board is written |
| **`c`** (CLI, while running) | run ends, no output file |
| `SIGTERM`, `Ctrl-C`, container stop | jobs are asked to stop, up to 10 s to finish, board written |
| closing the console window on Windows | **may lose the board — see below** |
| `kill -9` | nothing is saved; the process is gone before it can act |

The CLI prints the keys under the progress line while a route is running:

```
routing 4:30 of 15:00 (OPTIMIZATION)
Press 's' to stop and keep the board routed so far, 'c' to cancel.
```

### Closing the window on Windows

Closing a console window raises `CTRL_CLOSE_EVENT`, and Windows terminates the process when
its handler returns **or when the system's own time-out elapses** — five seconds by
long-standing default
([Microsoft docs](https://learn.microsoft.com/en-us/windows/console/ctrl-close-signal)).

We ask for ten, because that is what saving a board can need. If Windows takes the process
first, that run's board is lost or partial.

This is a deliberate line. Shrinking our budget to fit the shortest OS time-out would abandon
saves that would otherwise succeed, on every platform, to protect one case: closing the
window while a route is visibly still running. **Stop ends the run cleanly and writes the
board** — closing the window instead is choosing not to use it.

So if a long route matters, press Stop and let it finish. Then close whatever you like.

### Which ending did you get

Every run says how it ended, and the three mean different things:

- **"Finished by itself: no further improvement was being found."** The optimiser stopped
  because it stopped finding improvements. A longer `--router.job_timeout` will not change
  this result.
- **"Ran out of time."** The router was still working when the budget expired. A longer
  timeout may produce a better board — this is the one to re-run.
- **"Stopped at your request."** You ended it. Letting it run longer may have improved it.

The default budget is 15 minutes. Most boards finish well inside it and say so; the ones that
do not tell you to come back with more time.
