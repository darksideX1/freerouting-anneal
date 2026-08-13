# Freerouting Command Line Interface (CLI) Documentation

## Introduction

The Freerouting Command Line Interface (CLI) allows you to automate PCB routing tasks without using the graphical user interface (GUI). This is particularly useful for integrating Freerouting into scripts, build systems, or other software tools where automated routing is required.

This document provides detailed information on how to use Freerouting via the CLI, including available command-line options and how to adjust internal settings for advanced configurations.

## Usage

To run Freerouting from the command line, use the following syntax:

```bash
java -jar path/to/freerouting.jar [options]
```

Download the latest `freerouting-<version>.jar` from the [Releases page](https://github.com/freerouting/freerouting/releases) and substitute its path above.

## Command-Line Options

Below is a comprehensive list of command-line options available in Freerouting, organized by category.

### Input and Output Files

- **`-de [design input file]`**  
  Loads a design file at startup. The input can be:
    - Specctra board (`.dsn`)
    - Specctra session file (`.ses` - optional)
    - Freerouting design rules file (`.rules` - optional)

  The DSN file is mandatory, while the SES and RULES files are optional.  
  They can be provided in any order, separately or appended by the `+` sign (e.g. `-de myboard.dsn+myboard.ses+myboard.rules`).

- **`-do [design output file]`**  
  Saves the routing results when the routing is finished. The output can be:
  - Specctra board (`.dsn`)
  - Specctra session file (`.ses`)
  - Eagle session script file (`.scr`)
  
  The output format is determined by the file extension provided.

- **`-di [design input directory]`**  
  Sets the default folder for the open design dialogs when using the GUI.

- **`-dr [design rules file]`**  
  Reads design rules from a previously saved `.rules` file.

- **`-drc [design rules check json file]`**  
  Writes the design rules check report in KiCad JSON DRC schema format.

### Routing Parameters

- **`-mp [number of passes]`**  
  Sets the upper limit for the number of autorouter passes to perform. More passes may result in better optimization but will take longer.

- **`-mt [number of threads]`**
  Width of the RACING autorouter (redundant parallel routing attempts with identical
  settings and different item orderings; the best attempt wins). Acts only together
  with `--router.racing_enabled=true`; alone it changes nothing. Measured: racing
  returned the same or a worse board than a single attempt on every board tried, at
  N x the memory -- it exists, it is correct, and it is not recommended.
  Route OPTIMIZATION width is a different setting: `--router.optimizer.max_threads`
  (default 2, the measured quality point; `1` = single-threaded).

- **`-oit [percentage]`**
  The optimiser close-to-perfect early stop (`improvement_threshold`): when the
  board score is already within this percentage of the maximum score, further passes
  are not worth their time and the stage stops. Default: `1%` (stored as the fraction
  `0.01`; settable here in percent, or as `--router.optimizer.improvement_threshold`
  with the fraction). `-oit 0` disables this early stop. This is NOT the stall guard:
  the work-window guard (and its `rounds` override) governs stagnation; this threshold
  only ends runs that are already near the ceiling.

- **`-inc [net class names]`**  
  Lists net classes to ignore during autorouting:
  - Provide a comma-separated list (e.g., `-inc GND,VCC`).
  - The autorouter will not route nets belonging to these classes.

- **`-im`**  
  Enables saving of intermediate steps in a version-specific binary format:
  - Allows resuming interrupted optimizations from the last checkpoint.
  - Disabled by default.


### Optimization Strategies

- **`-us [greedy | global | hybrid]`**  
  Sets the board updating strategy for route optimization:
  - `greedy` (default): Accepts any immediate improvement.
  - `global`: Only accepts changes that result in a global optimum.
  - `hybrid`: Combines both strategies; requires `-hr` to specify the ratio.

- **`-hr [m:n]`**  
  Specifies the hybrid ratio when using the `hybrid` update strategy:
  - Format: `#_global_optimal_passes:#_prioritized_passes` (e.g., `1:1`).
  - Only effective with `-us hybrid`.

- **`-is [sequential | random | prioritized]`**  
  Sets the item selection strategy for route optimization:
  - `sequential`: Processes items in order.
  - `random`: Processes items in a random order.
  - `prioritized` (default): Selects items based on calculated scores from previous rounds.

### Language and Localization

- **`-l [language code]`**  
  Sets the language for the user interface:
  - Supported codes:
    - `en`: English
    - `de`: German
    - `zh`: Simplified Chinese
  - If unsupported, defaults to the system language or English.

### Host Integration

- **`-host [host_name host_version]`**  
  Specifies the name and version of the host application if Freerouting is run as an external library or plugin.

### Debugging Options

- **`--debug.enable_detailed_logging=[true|false]`**
  Enables detailed trace logging to the log file. Default is `false`.
  - Effect: Sets the file logging level to TRACE. Note that this can generate very large log files.

- **`--debug.single_step_execution=[true|false]`**
  Enables single-step execution mode. Default is `false`.
  - Effect: Shows "Play", "Pause", "Next", and "Previous" buttons in the toolbar. The autorouter will start valid pauses at breakpoints (e.g. trace insertion).

- **`--debug.trace_insertion_delay=[milliseconds]`**
  Adds a delay in milliseconds after each trace insertion or major routing step. Default is `0`.
  - Effect: Slows down the routing process for visual debugging.

- **`--debug.filter_by_net=[net1,net2,...]`**
  Restricts debug actions (stepping, delays) to specific nets.
  - Format: Comma-separated list of net names or numbers (e.g. `Net1,Net #2,3`).
  - Effect: Other nets are routed at full speed.

### Miscellaneous Options

- **`-dct [seconds]`**  
  Sets the dialog confirmation timeout:
  - Specifies the number of seconds before dialogs proceed with the default action.
  - Default is `20` seconds.

- **`-da`**  
  Disables the collection of anonymous analytics data.

- **`--logging.console.enabled=[true|false]`**
  Enables or disables console logging. Default is `true`.

- **`--logging.console.level=[level]`**
  Sets the console logging level.
  - Valid values: `OFF`, `FATAL`, `ERROR`, `WARN`, `INFO` (default), `DEBUG`, `TRACE`, `ALL`.

- **`--logging.file.enabled=[true|false]`**
  Enables or disables file logging. Default is `true`.

- **`--logging.file.level=[level]`**
  Sets the file logging level. Default is `INFO`.

- **`--logging.file.location=[directory]`**
  Defines the directory where `freerouting.log` is stored.

- **`-dl`** (Legacy)
  Disables **file** logging. Equivalent to `--logging.file.enabled=false`.

- **`-ll [level]`** (Legacy)
  Sets the **console** logging level. Equivalent to `--logging.console.level=[level]`.

- **`--user_data_path=[directory]`**
  Defines the directory where configuration and log files are stored.
  - Purpose:
    - `freerouting.log` will be created in this directory.
    - `freerouting.json` (settings) will be read from this directory if it exists, or created there if it doesn't.
  - Format constraint: Must use the `--user_data_path=path` syntax with an equals sign.
  - If the directory does not exist when Freerouting starts, it is created automatically on the first write (e.g. when `freerouting.json` is saved for the first time). A warning is printed to stderr if the initial `mkdirs()` attempt fails; the path is still registered and the directory will be created later.
  - This option takes priority over the `FREEROUTING__USER_DATA_PATH` environment variable and over `FREEROUTING__LOGGING__FILE__LOCATION`.

- **`-help`**
  Displays help information and exits.

## Options added by this fork

Upstream's flags above still work. These are new, and the first one matters most: it is the
budget for the whole job, and every stage derives its deadline from it.

### `--router.job_timeout=HH:MM:SS`

How long the whole run may take. **Default `00:15:00`.** Not a per-stage allowance — fanout,
routing and optimisation all derive their deadline from this one value, and each finishes
the pass it is in rather than being cut mid-write.

```bash
java -jar freerouting-anneal-1.1.1.jar -de board.dsn -do board.ses --router.job_timeout=01:00:00
```

Raise it when a run reports `Ran out of time.` — that ending means the router was still
improving when the clock stopped it. `Pass finished. No further improvements found.` means a
longer budget buys nothing.

In the GUI the same value is `Settings → Auto-router → Timeout`.

### `--router.optimizer.enabled=false`

Skip optimisation and take the routing-only board. Measured 2-3x faster overall on
boards that route quickly (the optimisation stage is where most of their time goes);
on a board whose ROUTING stage takes hours, it saves none of those hours — routing
is still routing. Leaves more unrouted
connections. Useful when you want the result now and will judge it yourself.

### `--router.optimizer.rounds=N`

How many items the optimisation stage may examine per pass. Setting it switches the
automatic progress guard OFF and uses this cap instead — one mechanism or the other,
never both, so a run is always explainable from a single line of the log.

The stage is probabilistic rather than converging: passes find improvements or they do
not, mostly vias and occasionally a connection, so more items examined means more chances
rather than steadier progress. Measured against routing alone on a 26-board set, 50 costs
roughly 3.5x the routing time and buys the reference via reduction; 150 costs roughly 5.7x
and buys a third more. Below 150 is rarely worth the clock.

Zero or a negative value is rejected with an error and the automatic guard runs instead —
the run tells you it is not configured as requested rather than silently switching modes.

In the GUI the setting persists like every other setting.

### `--router.optimizer.max_threads=N`

How many threads the optimisation stage runs. **Default 2** — the measured quality point:
slightly better boards than single-threaded at roughly half the wall clock. 4 trades a few
vias for the best trace length per connection and more speed; 6 is the practical ceiling: the
curve saturates between 6 and 8, so beyond 6 the clock stops improving while quality
keeps degrading. Wider is never an upgrade. Your core count is a **ceiling, not a target**: a request above it is clamped
and the run says so. On a one-core machine the single-threaded optimiser runs instead.

Not to be confused with `--router.max_threads`, which is read by the *racing* autorouter
(redundant parallel routing attempts), not by the optimiser — and racing acts only when
`--router.racing_enabled=true` is ALSO set; alone, `--router.max_threads` changes nothing
and the run routes single-threaded as always. The two names are historical; both are
honoured, each by its own stage.

### `--router.optimizer.memory_budget_mb=N`

A cap on the memory the optimiser may spend on board clones. Default: 60 % of the JVM
maximum heap. One clone per thread is the cost of width, so a tight budget reduces width
to fit — each reduction is a stated warning. A budget below the measured cost of a single
clone is refused with the numbers named, and the stage runs single-threaded in place.
Degradation is always loud, never silent.

### `--router.optimizer.board_update_strategy=greedy|global_optimal|hybrid`

How an accepted improvement becomes the new master board. **Default `greedy`**, which won
the measurements; `global_optimal` was dominated on every axis, and `hybrid` collapses
into it. Values are case-insensitive.

### `--router.optimizer.item_selection_strategy=prioritized|most_to_gain|sequential|random`

The order items are offered to the optimiser. **Default `prioritized`**; `most_to_gain`
measured equivalent to it, the other two are measured worse. Values are case-insensitive.

### `--router.optimizer.restore_default_scoring=true`

Experimental. When routing ran on a variant objective (for example raised via costs), this
restores the DEFAULT objective for the optimisation stage only. Measured worse than
running either objective end to end — a pipeline at war with itself converges to neither
goal — so it ships as an instrument, not a recommendation, and says so in the log.

### `--helpful`

Prints the operating manual — what the stages do, how long to give it, how to stop without
losing the board, and how to read the ending. It ships inside the jar, so it cannot drift
away from the build you are holding.


## Legacy short flags and their long forms

The short flags above predate this fork; each maps to a long `--router.*` setting, and
the long forms are the complete surface. The mappings that are not obvious:

| Short | Long | Values accepted by the SHORT form |
|---|---|---|
| `-mt N` | `--router.max_threads=N` (racing width) | any number; acts only with racing enabled |
| `-us S` | `--router.optimizer.board_update_strategy=S` | `greedy`, `global` (= `global_optimal`), `hybrid` |
| `-is S` | `--router.optimizer.item_selection_strategy=S` | `sequential`, `random`, `prioritized` -- **`most_to_gain` needs the long flag**; anything unrecognised falls back to `prioritized` |
| `-oit P` | `--router.optimizer.improvement_threshold=P/100` | percent here, fraction there |

Syntax family note: the long `--section.property` settings take `=` (`--flag=value`);
the short single-dash flags take a space (`-mt 4`). Historically a long flag given with
a space was silently ignored; it is now refused with an error naming the flag.

## Where settings come from

Three layers, later wins: **code defaults** (the measured optimums this release ships) →
**`freerouting.json`** in the user-data directory (every `--x.y=z` option has an
identically-named key there, so anything on this page can be made permanent by editing
that file) → **command-line arguments** for the single run. The user-data directory
defaults to platform app-data (`%APPDATA%\freerouting`, `~/.local/share/freerouting`,
`~/Library/Application Support/freerouting`) and moves with `--user_data_path=`.

## Adjusting Internal Settings

Freerouting allows you to fine-tune its internal settings beyond the standard command-line options. To modify these settings, use the double dash `--` prefix followed by the setting name and its value.

**Syntax:**

```bash
java -jar freerouting.jar --setting_name=value
```

**Examples:**

- **Disable the GUI:**

  ```bash
  java -jar freerouting.jar --gui.enabled=false
  ```

- **Adjust the via cost:**

  ```bash
  java -jar freerouting.jar --router.via_costs=150
  ```

### List-valued settings

Settings whose value is a list (e.g. `api_server.endpoints`) accept a **comma-separated string** when set via CLI argument or environment variable. Whitespace around each comma is stripped automatically.

```bash
# Single endpoint
java -jar freerouting.jar --api_server-endpoints=http://0.0.0.0:37864

# Multiple endpoints (comma-separated)
java -jar freerouting.jar --api_server-endpoints=http://0.0.0.0:37864,http://127.0.0.1:37864
```

The equivalent environment-variable syntax is:

```bash
FREEROUTING__API_SERVER__ENDPOINTS=http://0.0.0.0:37864,http://127.0.0.1:37864
```

> **Note:** Commas are the chosen delimiter because URL characters that would normally include a comma are percent-encoded by browsers/tools, so plain commas in the string unambiguously mark element boundaries.

### Layer-specific settings (Arrays)

Settings for individual board layers can be specified as a comma-separated list of values, where each value corresponds to a layer in order (from the top-most layer to the bottom-most):

- **`--router.layers.routable=false,true`**: Sets which layers are active/routable (e.g. `false,true` disables layer 1 and enables layer 2).
- **`--router.layers.preferred_direction_horizontal=true,false`**: Sets if the preferred direction on each layer is horizontal (`true`) or vertical (`false`).

For example, to route a board using only the second (bottom) layer:

```bash
java -jar freerouting.jar -de MyBoard.dsn -do MyBoard.ses --router.layers.routable=false,true
```

The equivalent environment-variable syntax is:

```bash
FREEROUTING__ROUTER__LAYERS__ROUTABLE=false,true
FREEROUTING__ROUTER__LAYERS__PREFERRED_DIRECTION_HORIZONTAL=true,false
```

### API Server Settings

| Setting | Type | Description |
|---------|------|-------------|
| `api_server.enabled` | Boolean | Enable or disable the built-in REST API server. |
| `api_server.http_allowed` | Boolean | Allow plain HTTP connections (in addition to HTTPS). |
| `api_server-endpoints` | String list | Comma-separated list of `protocol://host:port` endpoints the server will bind to. Default: `http://127.0.0.1:37864`. |
| `api_server.authentication.enabled` | Boolean | Require API-key authentication. Default: `true`. |
| `api_server.cors_origins` | String | Comma-separated CORS origin allowlist (use `*` for all origins). |

**Example — expose to all network interfaces without authentication (Docker or LAN):**

```bash
java -jar freerouting-executable.jar \
  --gui.enabled=false \
  --api_server.enabled=true \
  --api_server.authentication.enabled=false \
  --api_server-endpoints=http://0.0.0.0:37864
```

For a complete self-hosting walkthrough — including Docker Compose, systemd, and platform-specific notes — see the [Self-Hosting Guide](self-hosting.md).

## Examples

Below are some common usage examples to help you get started.

### Example 1: Basic Autorouting

Autoroute a design and save the results:

```bash
java -jar freerouting.jar -de MyBoard.dsn -do MyBoard.ses
```

- Loads `MyBoard.dsn`.
- Performs autorouting.
- Saves the routed design to `MyBoard.ses`.

### Example 2: Ignoring Specific Net Classes

Ignore the `GND` and `VCC` net classes during routing:

```bash
java -jar freerouting.jar -de MyBoard.dsn -do MyBoard.ses -inc GND,VCC
```

- Nets in the `GND` and `VCC` classes will not be routed.
- Useful when you plan to route these nets manually.

### Example 3: Limiting Passes and Trading Quality for Speed

Limit the autorouter to 10 passes and run the optimiser 4 wide:

```bash
java -jar freerouting-anneal-1.1.1.jar -de MyBoard.dsn -do MyBoard.ses -mp 10 --router.optimizer.max_threads=4
```

- `-mp` caps the routing passes.
- Width 4 trades a few vias for the best trace length per connection and more speed;
  the default (2) is the measured quality point.
- (The old form of this example paired `-mp` with `-mt 4`, which does nothing without
  `--router.racing_enabled=true` -- `-mt` is the racing width, not the optimiser width.)

## Conclusion

By leveraging Freerouting's CLI, you can integrate advanced PCB routing into your automated workflows, scripts, or applications. The flexibility of command-line options and internal settings allows for customized routing solutions tailored to your project's requirements.

For further customization and advanced configurations, refer to the [Settings Documentation](/docs/settings.md) and other resources provided with Freerouting.
