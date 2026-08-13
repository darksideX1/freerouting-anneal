# Spec: the final run report

**Status:** SHIPPED in 1.1.1 (file + finishing dialog + CLI last-line path; cancelled
runs write nothing, per the ruling below). Originally: specified 2026-08-10, built next
session.

**Why:** the router already computes everything a user needs at the end of a run — routed
and unrouted counts, the end reason, and the per-net list of unrouted connections — and
writes it to the log. The CLI user can find it there. The GUI user gets nothing: no ending,
no counts, and no sight of the one output that says *where* the router gave up. That list
is the point of running the tool, and it is currently the hardest thing in the product to
reach.

**Scope discipline, stated first because it is the whole design.** Nothing here computes
anything new. No routing changes. No HTML, no rendered view, no styled window, no browser.
We take content that is already produced, write it to a text file, and show the numbers in
a plain dialog with a way to open that file. That is the entire feature.

---

## 1. The report file

Written at the end of every run, by both interfaces.

**Format:** plain text. Same convention as the log, which is what the project already uses.
Not markdown — this is machine-adjacent output a user copies and pastes, not a document.

**Location:** the same directory the log is written to. One place for run artifacts; a user
who has found their log has found their reports.

**Name:** the original board's filename, plus a timestamp, plus a suffix. Running the same
board again with a longer budget therefore produces a sibling file rather than an
overwrite — the user can compare two attempts, which is exactly what the "Ran out of time"
ending invites them to do.

    <boardname>_<YYYYMMDD-HHMMSS>_final-report.txt

**Contents:** the same text already emitted to the log, specifically —

- how the run ended (the existing three endings, plus cancellation)
- elapsed time
- routed and unrouted connection counts
- clearance violation count
- the per-net unrouted list, with pins, exactly as `buildUnroutedConnectionsReport()`
  already produces it

Do not reformat or re-derive any of it. If the log line and the report disagree, that is a
defect by construction.

## 2. The GUI dialog

Shown when a run finishes. A plain dialog — the same kind the program already uses.

    ┌─ Routing finished ───────────────────────────┐
    │  Ran out of time.                            │
    │                                              │
    │  Elapsed:     15:00                          │
    │  Routed:      312 of 320 connections         │
    │  Unrouted:    8                              │
    │  Violations:  0                              │
    │                                              │
    │  [ Open report ]                  [  OK  ]   │
    └──────────────────────────────────────────────┘

`Open report` points at the file this run just produced — a live path, not a directory the
user has to search. `OK` dismisses.

That is the whole window. If it starts acquiring tabs, colours or a rendered pane, the
scope has been lost.

## 3. The CLI

Already prints all of this. Two additions:

- write the same report file
- print its path on the last line, so the user knows it exists

---

## Answered — operator, 2026-08-10

**Opening the report: a three-step ladder, degrading to something that always works.**

1. Where `java.awt.Desktop` is available, open the file. The system decides what opens it.
2. Where it is not, render the path as a clickable link so the platform's own file
   manager handles it. We are not opening anything; the user clicks and their system
   navigates.
3. Where even that fails, print the path as plain selectable text. The user copies it into
   a file manager by hand. Inelegant and complete — nobody is ever left without the
   report, only occasionally inconvenienced.

The principle: never a dead button. Each rung does less for the user and none of them
strands them.

**A cancelled run writes no report.** Cancelling means discarding the work. Producing an
artifact from it contradicts the instruction.

**And there is no Cancel button** — the GUI's Cancel was already a stop that kept the
board, so it was relabelled rather than reimplemented. There is no discard-the-work control
in the GUI, and any reference to one is inherited from upstream rather than describing this
build. The CLI `c` key is the only discard path that exists, so the cancellation question
is a CLI question only.

**The end reason for the GUI** still needs checking against the code: `endingMessage` is
pure, but `reportHowTheRunEnded` has one call site in the CLI flow, so whether the GUI path
populates `stageTimedOut` and `stoppedByUser` is unverified. Do not assume it does.

**Where the log lands** likewise: `--logging.file.location` exists, the default does not.

## Found while answering this: the Stop button is not localised

`BoardToolbar` sets the button text twice:

```java
tm.setText(cancel_button, "cancel_button");   // from the locale bundle
cancel_button.setText("Stop");                // hardcoded, and wins
```

The relabel put an English literal over the translated string, so every locale shows
"Stop". This project ships around twenty locales and has a parity test for the bundles,
which this slips past because the key still exists and is simply overridden afterwards.

Fix with a real `stop_button` key across the bundles rather than a literal, and while
there, rename the `cancel_button` field: a variable whose name contradicts its label is the
next person's confusion.

## Test plan

Red first, in this order:

1. A finished run writes a report file at the expected path, named for the board and the
   run's timestamp.
2. The report's counts and ending match what the job holds — assert against the job, not
   against a copy of the string, so the two cannot drift.
3. Two runs of the same board produce two files, neither overwriting the other.
4. A cancelled run writes NO report (aligned with the maintainer ruling above; this line previously contradicted it).
5. The per-net unrouted section is present when there are unrouted connections, and absent
   rather than empty when there are none.

## Acceptance

A GUI user finishes a run, sees why it ended and how much was routed without touching a
log, opens the report in one click, and finds the list of unrouted pins in a file they can
keep, copy or send to somebody. That is the bar. Nothing about it requires a prettier
window than the one sketched above.
