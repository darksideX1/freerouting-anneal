# Inheritance gaps — questions this document set does not answer

Companion to `INHERITANCE.md` and `docs/USER-GUIDE.md`, both written from a standing start by
someone who had never seen this project: the only sources were the repository and the
shipped jar's `--help` and `--helpful`. Every question below arose while writing those two
pages and could not be closed from those sources.

Entries stamped **CLOSED in triage** were fixed in the same change-set that landed this
register; the gap text is kept because the register records what a fresh reader found.

Each entry states what was asked, where it was looked for, and what was found instead.
**No entry speculates about the answer.** A gap that is guessed at is worse than a gap that
is recorded, because the guess reads like documentation.

Three kinds appear here, and they are not equally serious: a **contradiction** (two sources
in this repository disagree and neither defers to the other), an **absence** (no source
addresses it), and a **staleness** (a document describes a state the shipped build no
longer has). Contradictions are the ones that cost a reader hours, because both sides look
authoritative.

---

## Discoverability

### 1. Nothing published points at `docs/fork/`

**CLOSED in triage:** README's “What this fork changed” section now points at `docs/fork/` as the evidence base.

**Looked in:** `README.md`, `MAINTAINING.md`, `RELEASE-NOTES.md`, `WHAT-CHANGED.md`,
`AGENTS.md`, and every file in `docs/*.md`. **Absence.** No file in any of those references
the `docs/fork/` directory, and `docs/fork/` contains no index or README of its own. The
sixteen documents that constitute the entire evidence base for this release — the defect
register, both measurement documents, the version-provenance rules — are reachable only by
listing the directory.

The single pointer that exists anywhere is the last line of the jar's `--helpful` output,
which names one file (see next entry).

### 2. The one document the shipped program names is marked internal

**CLOSED in triage:** `--helpful` now points at `docs/USER-GUIDE.md` and `docs/command_line_arguments.md` instead.

**Looked in:** the jar's `--helpful` output, which ends with
`docs/fork/RUNNING-THIS-BUILD.md in the source tree`; then that file.
**Contradiction.** That file's opening marks it as internal-only and restricted from redistribution — yet it is the one document the shipped program points its users at.

### 3. No document says which parts of `docs/fork/` describe the shipped build

**Looked in:** the header of every file in `docs/fork/`. **Absence.** Several are explicit
that they describe something not built — `ERROR-HANDLING-POLICY.md` ("specified, NOT yet
implemented"), `FS-SEVERITY-TAXONOMY.md` ("specified, not yet implemented"),
`COHORT-GOAL.md` ("NOT STARTED"), `PARALLELISM-DESIGN.md` (`status: draft`, and it
specifies an algorithm it says was not the one measured). Others describe measured
behaviour. There is no index distinguishing the two, so a reader must open each file and
read its first paragraph to learn whether it is a record or a proposal.

---

## Contradictions between documents

### 3b. What the grading side consumes is not stated

**Looked in:** `MT-METHODOLOGY-REVIEW.md`, `PHASE3-MEASUREMENTS.md`, `RELEASE-NOTES.md`.
Output hashing per run, graded columns per cell, and three repetitions giving bands are
each documented. Whether the independent grading receives the delivered `.ses` itself or
re-derives from something else is not stated anywhere. **Absence.**

### 4. The default job budget is stated four different ways

**CLOSED in triage:** all four statements now read 15 minutes; the three-minute figure is labelled as an earlier experimental cut.

**Looked in:** `docs/command_line_arguments.md` §`--router.job_timeout` — "**Default
`00:15:00`**". `README.md` — "the default time budget is **15 minutes**, not 12 hours".
`docs/fork/RUNNING-THIS-BUILD.md` line 216 — "The default is **12 hours**, which for an
unattended run is effectively no bound at all". The same file's "How long a run takes"
section — "**This build bounds a route to three minutes by default.** Upstream's default is
twelve hours." The same file's closing section — "The default budget is 15 minutes."
**Contradiction, four ways, two of them inside one file.** A run of the shipped jar prints
`of 15:00` in its progress lines, which settles what the build does but not which document
is meant to be believed on anything else it says.

### 5. The log's default location is stated two ways, with two filenames

**CLOSED in triage:** the internal build notes now carry the 1.1.1 app-data location; the user guide states it and the first log line prints it.

**Looked in:** `README.md` and `RELEASE-NOTES.md` — the user-data directory defaults to
platform app-data (`%APPDATA%\freerouting`, `~/.local/share/freerouting`,
`~/Library/Application Support/freerouting`) and the log lives in `logs` inside it.
`docs/fork/RUNNING-THIS-BUILD.md` — "File logging is **on**, and by default it writes here:
`/tmp/freerouting/freerouting.log` … That is the system TEMP directory — upstream's choice,
unchanged by this fork", followed by a section describing two files named `routing.log` and
`routing-debug.log`. **Contradiction plus a naming mismatch.** The observed run prints
`.../freerouting/logs/freerouting.log`. Neither document is marked as superseding the
other, and nothing explains where the `routing.log` / `routing-debug.log` names come from.

### 6. Whether the multi-threaded optimiser is fixed depends on which file you open

**CLOSED in triage:** `MAINTAINING.md`'s scheduled-work block now states what 1.1.1 shipped and defers to RELEASE-NOTES.

**Looked in:** `RELEASE-NOTES.md` 1.1.1 — the optimiser "now runs multi-threaded by
default, two threads wide", with commits for each fix. `MAINTAINING.md` §Known broken —
"**Multi-threading discards its own work (all three kinds; GUI now defaults to
single-threaded)**", describing the delivery defect in the present tense, listing the
repairs as "**Scheduled for 1.1.1**", and ending "Until then the GUI constructs the
single-threaded optimiser unconditionally". **Staleness presenting as contradiction.**
`MAINTAINING.md` is the file `README.md` sends an inheriting maintainer to first, and it is
the only document that says anything about which optimiser the GUI constructs. Whether the
shipped GUI runs the multi-threaded optimiser is therefore unanswerable from the documents.

### 7. `--help` is described as "every flag" and is not

**Looked in:** `README.md` — "`--help` # every flag"; the jar's `--helpful` output —
"`--help` every flag"; then the jar's actual `--help` output, 73 lines.
**Contradiction.** `--help` does not mention `--router.job_timeout` — the flag both
`README.md` and `--helpful` treat as the most important one in the release — nor
`--router.scoring.via_costs`, `--outcome_exit_codes`, `--user_data_path`, or any
`--logging.*` setting. It also omits two short flags the documentation uses in worked
examples: `-inc` (`README.md` "Common flags", and an example in
`docs/command_line_arguments.md`) and `-oit` (the legacy mapping table in that same file).
`--help` does carry the line "the full list: docs/command_line_arguments.md", so the
omission may be deliberate; nothing states which flags it is meant to carry.

### 8. "Read the last line" no longer describes the last line

**Looked in:** `README.md` §"Read the last line — it tells you what to do next (CLI)"; the
jar's `--helpful` — "READ THE LAST LINE. IT TELLS YOU WHAT TO DO NEXT."; then three runs of
the shipped jar. **Staleness.** The ending is printed, and then the run report's path is
printed after it (`Run report: …`), so the ending is second-to-last. With
`--outcome_exit_codes=true` a further line follows (`Exiting with status 3 (INCOMPLETE).`),
putting the ending third from last. On a board with unrouted connections the per-net
listing and the job-state line are also interleaved between them. No document records the
current ordering.

---

## The defect register

### 9. Its own counts do not agree

**Looked in:** `docs/fork/DEFECT-REGISTER.md`. **Contradiction, internal.** The header says
"43 commits. **16 of 23 defects closed, 2 partially, 5 open by decision.**" The section
headings say "CLOSED (**15**)" over a table of 16 rows, "PARTIALLY CLOSED (**3**)" over a
table of 2 rows, and "OPEN BY DECISION (**4**)" over a table of 5 rows. Four numbers, no
two of which agree, and no statement of which is maintained.

### 10. Defects marked open are described as fixed elsewhere

**Looked in:** `docs/fork/DEFECT-REGISTER.md` against `RELEASE-NOTES.md`.
**Staleness.** Defect 24 (a pass aborted by an exception reports `COMPLETED` and exits 0)
carries `Status: open`; `RELEASE-NOTES.md` lists it as fixed. Defect 28 (a stop from
outside discards the board) carries `Status: OPEN`; `RELEASE-NOTES.md` describes the save
on external stop as shipped, and the shipped jar's `--helpful` documents it. Defect 25
carries `Status: open, and RENAMED` and then, in its own body, describes the fix and the
instrumentation that produced it. Defect 26 carries **both** a `STATUS 1.1.1: FIXED` banner
and, four lines below it, `Status: open`. The convention appears to be that the banner is
added and the original line preserved as "the as-found record" — but that is applied to one
defect out of four, and the register does not state the rule.

### 11. Defects referenced by number have no entry

**Looked in:** `docs/fork/DEFECT-REGISTER.md` for the numbers used elsewhere.
**Absence.** `docs/fork/MT-METHODOLOGY-REVIEW.md` refers to "defect 31" three times as the
optimiser delivery fault, and `WHAT-CHANGED.md` numbers six further 1.1.1 defects in its
own table. Neither defect 30 nor 31 appears in the register, and none of the six 1.1.1
entries has a register record. The register's own scope statement ("Every defect
catalogued during this work, with its current status verified against the code rather than
recalled") does not say when it closed.

---

## The final run report

### 12. Its specification says it was never built

**CLOSED in triage:** the spec now carries a shipped-status line, and the user guide documents the report.

**Looked in:** `docs/fork/FINAL-REPORT-SPEC.md` — "**Status:** specified, not built.
Operator brief 2026-08-10, to be implemented next session." **Staleness.** The shipped jar
writes the report file at the specified path with the specified contents, including the
per-net unrouted listing, and prints its path on the last line. The spec is the only
document describing the feature at all — no user-facing document mentions the report — so
a reader looking for it finds a document saying it does not exist.

### 13. Two questions the spec explicitly left open are never answered

**Looked in:** `docs/fork/FINAL-REPORT-SPEC.md`, then every other file in `docs/fork/`, the
root documents, and the jar's help surfaces. **Absence.** The spec closes with two
unresolved items and an instruction not to assume: whether the GUI path populates
`stageTimedOut` and `stoppedByUser`, so whether a GUI run can report *how* it ended
("`reportHowTheRunEnded` has one call site in the CLI flow … Do not assume it does"); and
where the log lands by default ("`--logging.file.location` exists, the default does not").
No document records an answer to either.

### 14. Whether the GUI now shows a finishing dialog

**Looked in:** `README.md` §Known limitations — "**The GUI reports neither how a run ended
nor what was left unrouted** … This is the largest known gap in the release."
`docs/fork/FINAL-REPORT-SPEC.md` specifies a finishing dialog for the GUI with an
`Open report` button. **Contradiction, unresolved.** These were checked against the jar's
help surfaces and against every document; the GUI itself was not exercised, so this entry
records the documentary conflict only. A first-time GUI user cannot learn from the
documentation whether they will be shown the ending.

### 15. Whether the Stop button's localisation was repaired

**Looked in:** `docs/fork/FINAL-REPORT-SPEC.md`, which records the finding — `BoardToolbar`
sets the button text twice, from the locale bundle and then from a hardcoded English
literal that wins, so all ~20 locales show "Stop" — and prescribes the fix.
**Absence.** No document records whether it was applied. `docs/translations.md` and
`docs/developer.md` describe the bundle parity test that the finding notes this slips past.

---

## Verification the documents invite but do not enable

### 16. The board the width curve was measured on is not in the repository

**Looked in:** `fixtures/` (157 files), `scripts/benchmark/fixtures/KiCad_10_demos/`, and a
tree-wide search for the name. **Absence.** The xESC2 board carries the entire
width-versus-quality table in `docs/fork/MT-METHODOLOGY-REVIEW.md` — the measurement that
sets the shipped default of two threads — and `RELEASE-NOTES.md` calls it public. No
document says where a reader obtains it, so the table cannot be re-run from this repository.

### 17. "Independently graded" is used as a term of art and defined nowhere

**Looked in:** `RELEASE-NOTES.md`, which is the only published use and glosses it in a
subordinate clause — "connectivity scored by an instrument that is not the router itself";
`docs/fork/MT-METHODOLOGY-REVIEW.md`, which records properties of the grading (unconnected
counts are run-stable, DRC error counts drift ±7 on identical input) but describes it as a
standing arrangement rather than a procedure. **Absence.** No document states what is
submitted for grading, in what form, against what acceptance rule, or how a reader would
stand up an equivalent arm. `INHERITANCE.md` §5 reconstructs what can be reconstructed from
those two sources and says so.

### 18. No document maps a fix to the test that proves it

**Looked in:** `WHAT-CHANGED.md`, which states that each clean single-commit fix arrives
"with a failing-first test" but names no test; `docs/fork/DEFECT-REGISTER.md`, which cites
evidence per defect but names test classes only occasionally; `docs/developer.md`.
**Absence.** The table in `INHERITANCE.md` §5 was built by reading the 173 test source
filenames and matching them to claims, which is inference from naming, not a documented
mapping.

### 19. There is no way to verify a jar you were handed

**Looked in:** `docs/fork/RUNNING-THIS-BUILD.md`, which instructs the reader to record
`sha256sum` of what they were given, and `MAINTAINING.md`'s release ritual.
**Absence.** No document publishes the checksum of any released jar, so the recorded hash
can only be compared against another copy of the same file. Separately, the distributed jar
examined for this handover is named `freerouting-anneal-1.1.1_7b594a43.jar` while its own
banner reports `build 7e2e3ca6`; the documents state that the banner names the build's
commit, and say nothing about what the hash in a filename is or which of the two is
authoritative when they differ.

### 20. Whether racing honours its width setting in this build

**CLOSED in triage:** answered in `INHERITANCE.md` §racing (width comes from `--router.max_threads`, active only with `racing_enabled`).

**Looked in:** `README.md` — this fork "fixed two correctness defects in it (deterministic
per-thread ordering seeds, memory-bounded copies) but did not tune the algorithm".
`MAINTAINING.md` — racing's width is read from a different `max_threads` than the one most
people set, and two nominally different widths were measured as the same configuration.
`docs/fork/MT-METHODOLOGY-REVIEW.md` — the recommendation to "fix ONLY the width-honesty
defect (respect the setting or refuse loudly)" is recorded as **held for a ruling** and
therefore unresolved at the time of writing. `RELEASE-NOTES.md` 1.1.1 does not mention
racing. **Absence.** Whether `--router.max_threads` is honoured by racing in the shipped
build is not answerable from the documents.

---

## Smaller absences, recorded because each one stopped a sentence being written

### 21. Which value of `--router.optimizer.rounds` to use

**Looked in:** `docs/command_line_arguments.md` — measured on a 26-board set, "50 costs
roughly 3.5x the routing time … 150 costs roughly 5.7x … Below 150 is rarely worth the
clock". The jar's `--helpful` and `RELEASE-NOTES.md` — `rounds=400`, "measured against the
guard: the delivered quality is the same". **Absence.** Both are measured statements about
different quantities on different boards, and no document reconciles them into guidance for
a reader choosing a value.

### 22. What the KiCad demo boards are, and why four are disabled

**Looked in:** `README.md` and `RELEASE-NOTES.md`, which describe the KiCad demo boards as
shipping "with this repository" without saying where;
`scripts/benchmark/fixtures/KiCad_10_demos/`, where they are, and where four files carry a
`.dsn_disabled` extension. **Absence.** No markdown file in the repository mentions the
directory or the extension. The extension is honoured by
`scripts/benchmark/lib/BenchmarkFixtures.ps1`, which skips those files; nothing records why
each was disabled or what re-enabling one would cost.

### 23. `docs/benchmarks.md` describes a tree that no longer exists

**Looked in:** `docs/benchmarks.md`. **Staleness.** It is inherited upstream content: it
measures v1.9 against v2.2 on a machine specification unrelated to any measurement in this
fork, and its worked command line loads `.\tests\Issue508-DAC2020_bm01.dsn`. There is no
`tests/` directory in this repository; the file is in `fixtures/`. Nothing marks the page
as superseded, and `RELEASE-NOTES.md`'s own routing-time figures for the same board do not
reference it.

### 24. The Windows CI hang has no reproduction

**Looked in:** `MAINTAINING.md` — "Two separate fixes for it are already merged and it has
hung since. It was green on the release build. That is not the same as fixed."
**Absence.** No document names the workflow or job that hangs, the step it hangs at, the
symptom to look for, how often it recurred, or what the two merged fixes were. An inheritor
can recognise the ghost only after meeting it.

### 25. Whether an installer is offered, and for what

**Looked in:** `README.md` §Getting started, which says to download the jar and install a
Java 25 JRE; `MAINTAINING.md`, which says `create-release.yml` "builds installers" and that
Windows and macOS builds exist in it "because the MSI and the DMG can only be produced
there". **Absence.** No user-facing document mentions an MSI or a DMG, states what they
contain, or says whether a user who installs one still needs a separate Java runtime.
