# Error handling policy — what an error must do, on every path

**Status: specified, NOT yet implemented.** Written before the code so the rule is the
thing being built, rather than being inferred afterwards from whatever the code does.

## The failure this exists to prevent

An uncaught exception during routing currently opens a **modal dialog and waits for a
click**. Dismiss it and routing "kind of continues" — so something went wrong, something
caused it, and the only record is a window that no longer exists.

That is three separate defects wearing one coat:

1. **It blocks.** On an unattended or shared box nobody is there to click. The process
   does not exit, emits nothing further, produces no artifact, and burns its entire
   timeout. From the caller's side it is indistinguishable from "still working" — strictly
   worse than crashing, because a crash is reportable.
2. **It is invisible.** Click it away and there is no durable record. Nothing to grep,
   nothing to count, nothing to compare between runs.
3. **It interrupts.** A non-fatal error stops the human mid-run to show them something
   they can only acknowledge, at the moment they are least able to act on it.

**Consequence: this is not testable.** An error you cannot see in a log cannot be
asserted on, counted, or regression-tested. Any stability claim covering only the headless
path is a claim about half the product — including our own "0 crashes in 20 runs", which
was measured entirely on the CLI path and says nothing about the GUI.

## The rule

**Every error is logged, always, on every path, at a level that stands out — before
anything else is decided.** Logging is not the fallback for when a dialog cannot be shown;
it is the primary record, and the dialog is an optional extra for interactive sessions.

Then severity decides what happens next, and severity is the *only* thing that decides:

| Severity | Automated / headless | Interactive (GUI) |
|---|---|---|
| **Fatal** — routing cannot meaningfully continue | Stop the router. Exit non-zero. Error on stderr and in the log. Write whatever partial artifact exists, clearly marked partial. | Stop the router, report it, keep the board on screen. |
| **Non-fatal** — routing can continue | Log it and carry on. **Never block. Never pop up.** Report it in the run summary at the END. | Log it and carry on. Surface it in the end-of-run summary, not as a mid-run modal. |

Two consequences worth stating explicitly, because they are the parts most likely to be
eroded later:

- **Nothing non-fatal ever interrupts.** Not in automated mode, not in the GUI. The user
  gets one summary at the end rather than a sequence of interruptions during.
- **Nothing fatal is ever silent.** If the router stops, the reason is on stderr, in the
  log, and in the exit code. "It stopped and I don't know why" is the same defect as
  "it popped up and I clicked it away".

## The end-of-run summary

A run that logged errors must say so when it finishes, in one place, with counts by
severity. A single line is enough:

```
Job finished: COMPLETED (3 non-fatal errors logged; see log for detail)
```

This is what makes the thing testable: a harness can assert on the count, a human can see
at a glance whether the run was clean, and a regression that starts throwing a swallowed
exception becomes visible instead of hiding behind an unchanged unrouted count.

## Scope note — both invocation paths are the product

The GUI and the CLI are both shipped, both supported, and both must be stable. A defect
that only manifests in the GUI is not a lesser defect; it is a defect in the interface most
users meet first. Any future stability claim must state which paths it was measured on, and
"headless only" is not a stability claim about freerouting.

## Known instances, not yet fixed

- `ItemSearchTreesInfo.set_precalculated_tree_shapes` — NPE, `search_trees_info` null.
  Appears **twice in a headless log where no dialog was shown**, so it fires on the
  headless path too and is currently swallowed entirely.
- `SearchTreeObject.shape_layer` — NPE, `curr_object` null. Intermittent.

Both are currently caught by the generic handler, which means neither has been diagnosed —
only displayed. Fixing the policy makes them visible; it does not fix them.

## Related defect: silently ignored flags

`--flag value` (space form) is parsed and ignored, while `--flag=value` works. This is the
same family: an input the program cannot honour, accepted without complaint. The fix is the
same shape — **reject loudly rather than continue quietly.** A flag that cannot be applied
must fail the run, not change the behaviour of a measurement nobody knows is wrong.
