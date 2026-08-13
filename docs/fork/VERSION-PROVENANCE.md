# Version provenance — "freerouting v2" names more than one engine

Read this before comparing any two freerouting measurements, including your own
from last month.

## The trap

A result labelled "freerouting v2" is not reproducible from that label. Between the
`v2.2.4` release tag and our fork point `4a0ae4b7` there are **544 commits**, and
they include the addition of an entire routing phase. Two builds both honestly
described as "v2" can differ by more than a configuration — they can differ by
what the router *does*.

```
git rev-list --count v2.2.4..4a0ae4b7   ->  544
```

## The specific case that caught us

The SMD-pin fanout pre-pass:

| Event | Commit | Date |
|---|---|---|
| `v2.2.4` tagged | *Bump docs and README to v2.2.4* | 2026-05-13 |
| `BatchFanout` added to the **v2** tree (`src/main/`) | *Reintroduction of the fanout phase* | 2026-05-16 |
| `FanoutSettings` added — fanout becomes configurable | *Add FanoutSettings and configurable fanout* | 2026-05-19 |

At `v2.2.4`, `BatchFanout` exists **only** under `src_v19/` — the v1.9 tree. The v2
engine at that tag has no fanout pre-pass at all. The commit that put it into v2 is
titled *"Reintroduction of the fanout phase"*, so the phase had existed, been
removed, and come back — which is exactly the history that makes a version label
untrustworthy.

**Consequence:** on a stock `v2.2.4` jar, `--router.fanout.enabled` is rejected with
*"No field found with name or SerializedName: fanout"*, and no alternative spelling
works. The knob is absent because the feature is absent. A 2×2 experiment crossing
fanout on/off against anything else runs fanout-free in all four cells and produces
a clean-looking table that means nothing.

## What this invalidated

Two lanes measured run-to-run determinism on the same three boards and disagreed:

| Build | b2_logic | b3_logic | b2_power |
|---|---|---|---|
| stock `v2.2.4` (fanout absent) | 1 distinct / 8 | 1 / 8 | 1 / 8 |
| this fork, fanout **off** | 3 distinct / 8 | 3 / 8 | 1 / 8 |

Fanout is not the variable — it is off or absent in both rows. The variable is the
544 commits. Both measurements were correct; the label they shared was not.

The useful half: this **bounds** the second nondeterminism source to an ordered,
bisectable range (`v2.2.4..4a0ae4b7`, ~10 builds to localise) rather than leaving it
unidentified. It also shows the asymmetry is board-dependent — `b2_power` is stable
in both builds while the other two are not.

Caveat carried honestly: the two rows were not verified to use byte-identical board
files (ours carries a plane-type correction), so the comparison is indicative until
the same files are run through both.

## The rule

**Every published determinism, performance or quality figure carries the COMMIT**,
not the version string, plus the fanout state. A version string is a claim about the
past; a commit is checkable. This is the same failure mode as trusting a settings
flag that is silently ignored — the artifact looked fine, its identity was wrong.

If you are comparing against a released jar, state which release *and* confirm the
feature you are toggling exists in it. `git grep -l <ClassName> <tag>` costs seconds
and is the check that would have caught this on day one.
