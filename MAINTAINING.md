# Taking this over

Nobody maintains this. It is a snapshot, published once and archived, so it cannot be
maintained in place — there is no branch to push to and no issue to open. Anyone who wants
to change anything forks or clones it first and is then working on their own copy, with
their own name on it.

This page is for that person. It carries what a maintainer would have said in a five-minute
handover, given that no such conversation is available. It is deliberately blunt about what
is broken.

If only one section gets read, read **Traps** — those are the ones that cost hours.

---

## Traps

### Two workflows still publish without being asked

Inherited from upstream, where they make sense for a maintained project with a maintained
budget. Two have been defused here; two are still live:

| workflow | fires on | what it does |
|---|---|---|
| `docker-nightly.yml` | **manual only** — push trigger removed | would push an image to ghcr |
| `create-snapshot.yml` | **manual only** — push trigger removed | would upload release assets |
| `create-release.yml` | **any tag matching `v*`** | builds installers, publishes a GitHub release |
| `docker-release.yml` | **a release being published** | pushes another image |

The two push triggers were removed because they fired on every commit to `master` and
published artifacts nobody had asked for. In a fork, that means publishing under the new
owner's account on their first push, which is a surprising thing to inherit.

The remaining two are deliberate: a `v*` tag is a release instruction, and it is the only
one. There is no separate publish step and no confirmation prompt, so **the tag is the
button**.

### The CI matrix was cut on purpose, and putting it back costs real money

Upstream runs pull requests on Linux, macOS and Windows. Here it is **Linux only**.

GitHub bills macOS at ten times a Linux minute and Windows at two, so a three-platform run
on a documentation-only commit spends roughly thirteen minutes of quota to learn what one
Linux job already reported.

Platform coverage did not disappear — `create-release.yml` still builds on Windows and
macOS, because the MSI and the DMG can only be produced there. Verification happens where a
platform-specific artifact is actually made.

Restoring the three-platform matrix is one line in `gradle-build-on-pr.yml` and entirely
reasonable for a project with a budget for it. Worth knowing before you do: when an account
runs out of Actions minutes, jobs fail at scheduling — every check red within seconds, zero
steps executed — which looks exactly like broken code and is not.

### The KiCad plugin jar is built, never committed

`integrations/KiCad/kicad-freerouting/plugins/jar/` is empty on purpose. The
`kicadPlugin` Gradle task stages the current jar into the plugin and produces
`build/dist/kicad-freerouting-<version>.zip`; `create-release.yml` runs it and uploads the
zip as a release asset, and both `metadata.json` files point at that asset.

Do not commit a jar back into that directory. A jar sitting in the tree makes the tree
dirty, which makes the jar built from it report a build nobody can reproduce from the sha
it names. This was the arrangement before; it produced a plugin shipping a binary two
releases out of date.

### Some tests read files Gradle does not watch

`ShippedVersionTest` reads `build.gradle`, `.github/workflows/create-release.yml` and the
KiCad manifests. Gradle does not track those as inputs to the `test` task, so after you
edit one, `./gradlew test` reports up-to-date and **silently skips them**. Use
`--rerun-tasks` when you have changed a file of that kind. CI checks out fresh, so it
always runs them.

### The repository is large and cannot be made smaller

`integrations/KiCad` holds roughly half a gigabyte of committed plugin archives, one per
historical version, and the git history is over a gigabyte. That is inherited from
upstream. Deleting the files would not shrink a clone, and rewriting history to purge them
would break the ability to cherry-pick these commits — which is the main reason this fork
was published. Live with the clone time.

---

## Known broken

Nothing here is a surprise waiting for you; it is a list so you can recognise these
rather than diagnose them.

### Multi-threading: FIXED in this release — kept here because the shape of the bug
is the most useful thing to know before touching the optimiser

This is no longer broken, but the shape of what was wrong is the most useful thing an
inheritor can know about this code, so it is kept here rather than deleted.

There are three parallel mechanisms in this codebase. Measured on real boards with
external CPU accounting rather than the engine's own logs:

**The multi-threaded optimiser found improvements and never delivered them.** Each pool
task cloned the board, rerouted its item and reported honestly — 41 wins accepted on one
board in one run. `replaceMasterRoutingBoardWithTheWinningCandidate()` swapped only the
*optimizer's* board field; `job.board`, which is what gets saved and exported, still
pointed at the original object, so the delivered board was byte-identical to running no
optimiser at all, deterministically, at every width. A second fault compounded it: each
task's fresh `BatchOptimizer` had `min_cumulative_trace_length = 0.0` (only a pass
initialised it), so length-only improvements — the majority class, 246 of 311 items in
the single-threaded comparison run — could never register in a task. Both are fixed: the
winning board is handed back to the job, and the length baseline is initialised from the
clone's own statistics in task mode. Tasks also clone at run time now, so wins compound
within a pass instead of every task starting from the pass's opening position.

**Racing's width is controlled by a different `max_threads` than the one you expect,
and that is still true.** There are TWO settings with that name: `router.max_threads`
(router level) and `router.optimizer.max_threads` (the optimiser pool). Racing reads the
FIRST. Our own instrumented probes set the second, so "requested 2" and "requested 4"
were identical configs racing ~15 ways (—411% CPU both times, same 175—180 s) — the
trap is easy to fall into even while hunting for dropped flags, because the flag
applied cleanly, to a different mechanism. The names are historical and both are
honoured; what changed is that a width requested without `racing_enabled` now warns
loudly instead of silently doing nothing. Racing itself is unchanged and not recommended:
it delivered a *worse* board than routing once (106 vias vs 104, longer traces) at 2.7x
the wall clock. It is redundant parallelism — N identical routers, best score kept — so
the cost buys luck, not throughput.

**The router itself is single-threaded, full stop.** 126% CPU measured during routing (one
core plus JVM housekeeping). There is no work-sharing parallel router; racing is the only
multi-core routing mode and it is redundancy, not cooperation. This is unchanged, and it
is why more threads do not make routing faster.

The defaults that came out of the measurements: optimiser width 2, core count as a clamp
ceiling rather than a target (the ~60%-of-cores plan was superseded — quality degrades
with width, so scaling with cores is a regression). See RELEASE-NOTES
"Multi-threading, made real" and WHAT-CHANGED rows 12—17.

### `SessionManagerTest` fails 5 of 6 on a fresh clone

Test isolation: `globalSettings` is null when the class runs after others. It predates
this fork's work and is unowned. **If you see it on a clean checkout, you did not break
it.**

### Windows CI has a history of hanging

Two separate fixes for it are already merged and it has hung since. It was green on the
release build. That is not the same as fixed — if it hangs for you, you have found the
same ghost, not a new one.

### Fanout necks down below the declared minimum track width — now reported, not silent

Confirmed here after a third party reported it: twenty-three of ninety-two routed boards
carried tracks at exactly three quarters of the board's own width.

The narrowing is kept. Refusing it costs real pin escapes on boards where the net width
equals the board minimum and no legal narrower width exists — measured at twelve of one
fixture's 157 SMD pins, plus completions on two of upstream's regression boards.

What changed is that it is no longer silent. Every track on the delivered board below the
minimum is named by net, and the job summary counts it rather than reporting a clean run. A
board carrying one still may not be manufacturable as routed; the program now says so
instead of reporting zero violations.

### Runs are not bit-for-bit reproducible

The same jar, board and settings can produce different output. This is a property of the
2.x engine rather than of this fork's changes: the 1.9 engine is deterministic on every
board tested, and stock 2.2.4 is stable where later builds are not, which bounds the cause
to a known range of upstream commits. Fanout's per-pin stopwatch is one source and not the
only one.

We diagnosed and bounded it and deliberately stopped there. Chasing it to zero means
changing when the algorithm stops, which changes results — a different project from the
one this was.

---

## What we deliberately did not fix

Each of these was examined, understood, and left alone on purpose. If you are tempted,
read the reasoning first; in several cases the obvious fix is worse than the defect.

**The search tree is traversed while it is mutated.** `MinAreaTree.remove_leaf` clears a
leaf's fields before the replacement is inserted, and the comparator dereferences them, so
a concurrent removal throws out of the traversal. Skipping null entries looks like the fix
and is not: a null object does not mean the obstacle is gone, it means the obstacle is
mid-re-registration, so skipping omits real copper and the router draws through it — a
silent clearance violation on a board somebody fabricates, in place of a loud crash. The
crash is the safer failure. A real fix synchronises traversal against mutation, which
changes this tree's concurrency contract.

**Stage completion is timeboxed rather than criterion-based.** Stages stop on elapsed
milliseconds, not on work done. Changing that changes when the algorithm stops, which is
behaviour, not waste.

**`normalize()` failures are caught, logged and ignored** at four sites, each continuing
with an un-normalised trace. Making the contract explicit is safe; changing the skip
decision is not. Left for whoever owns the geometry.

**`BatchAutorouterV19` is not deleted.** Similarity analysis recommends removing it as an
abandoned fork. It is wired into the interactive path and is the only in-tree reference
implementation of the 1.9 engine.

**Roughly eighty "dead code" findings are not acted on.** They are reflection-invoked
entry points — JAX-RS filters, servlet listeners, websocket callbacks. The tool also
flagged `Freerouting.main`, which is the tell. Deleting them removes the live API layer.

---

## Releasing

1. `./gradlew test --rerun-tasks` — expect the `SessionManagerTest` failures above and
   nothing else.
2. Bump `ext.publishInfo.versionId` in `gradle/project-info.gradle`. That single value
   feeds the jar banner, the KiCad plugin filename and the integration manifests;
   `ShippedVersionTest` fails if any of them drift apart.
3. Commit, then tag `v<version>`. **The tag is the trigger** — `create-release.yml` builds
   the installers, the KiCad archive and the release. There is no separate publish step
   and no confirmation prompt.

The jar names the commit it was built from. A `-dirty` suffix means the tree was modified
when it was built, so it is not the commit it names — never ship one.

**Build the release artifact from the tag, never from a build directory.** This is not
fussiness: a jar named plainly `freerouting-anneal-<version>.jar` accumulated in a dist
directory here and was three builds out of date, silently missing the run report and the
log while every document promised both. It was caught by running the README command and
diffing the filesystem, not by reading anything. So: check out the tag, confirm a clean
tree, build with `--rerun-tasks`, then RUN the jar once and read its banner — the
commit it names must be the tag’s commit. If it is not, the wrong bits are in your hand.
Publish the sha256 beside the artifact; that hash is what makes a plain filename
auditable later.

**The KiCad manifests carry a hash of the release zip, so they can only be filled after
the zip exists.** Build it from the same tree (`./gradlew kicadPlugin`), fill
`download_sha256`, `download_size` and `install_size` in BOTH
`integrations/KiCad/metadata.json` and `integrations/KiCad/kicad-freerouting/metadata.json`,
and commit that as a post-release commit. They ship as zeros until then, deliberately: a
hash committed before the tag describes a zip the tagged build cannot reproduce.
