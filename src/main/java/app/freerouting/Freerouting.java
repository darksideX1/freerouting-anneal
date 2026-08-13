package app.freerouting;

import app.freerouting.api.AppContextListener;
import app.freerouting.api.mcp.McpApplication;
import app.freerouting.api.mcp.McpContextListener;
import app.freerouting.api.mcp.McpWebSocketEndpoint;
import app.freerouting.constants.Constants;
import app.freerouting.core.CliOutcome;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.io.specctra.SesImportSummary;
import app.freerouting.io.specctra.SesReader;
import app.freerouting.gui.DefaultExceptionHandler;
import app.freerouting.gui.GuiManager;
import app.freerouting.logger.FRLogger;
import app.freerouting.management.BoardLoader;
import app.freerouting.management.SessionManager;
import app.freerouting.management.analytics.FRAnalytics;
import app.freerouting.settings.ApiServerSettings;
import app.freerouting.settings.GlobalSettings;
import app.freerouting.settings.McpServerSettings;
import app.freerouting.settings.SettingsMerger;
import app.freerouting.settings.sources.CliSettings;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.settings.sources.DsnFileSettings;
import app.freerouting.settings.sources.EnvironmentVariablesSource;
import app.freerouting.settings.sources.JsonFileSettings;
import app.freerouting.util.TextManager;
import app.freerouting.util.VersionChecker;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.CrossOriginHandler;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.glassfish.jersey.servlet.ServletContainer;

/* Entry point class of the application */
public class Freerouting {

  public static final String WEB_URL = "https://www.freerouting.app";
  public static final String VERSION_NUMBER_STRING = formatVersionBanner(
      Constants.FREEROUTING_VERSION, Constants.FREEROUTING_SOURCE_BUILD,
      Constants.FREEROUTING_BUILD_DATE);

  /**
   * The one line that identifies this build.
   *
   * <p>Every cut of this fork reports the same {@code v2.3.x-SNAPSHOT}, so the handout had
   * to tell people to record a commit sha by hand and the changelog had to open with
   * "identity is the commit, not a version string". Asking a human to carry information
   * the program already has is a documentation workaround for a product defect: three
   * byte-different jars were cut in one day, all claiming the same version, and given two
   * of them nobody could say which was which.
   *
   * <p>A build from a modified tree carries {@code -dirty}. It is not the commit it names
   * -- nobody can reproduce it from that sha -- and a build claiming an identity it cannot
   * support is worse than one that admits it has none.
   *
   * <p>With no lane id (no git, e.g. a source tarball) the banner is byte-for-byte what
   * upstream always printed, so this costs an upstream user nothing.
   */
  static String formatVersionBanner(String version, String sourceBuild, String buildDate) {
    String build = (sourceBuild == null) ? "" : sourceBuild.trim();
    String buildSuffix = build.isEmpty() ? "" : ("build " + build + ", ");
    return "v" + version + " (" + buildSuffix + "build-date: " + buildDate + ")";
  }
  public static GlobalSettings globalSettings;
  public static String bridgeToken = java.util.UUID.randomUUID().toString();
  private static Server apiServer; // API server instance
  private static Server mcpServer; // MCP server instance
  private static java.io.PrintStream originalSystemOut;

  /** How often the headless CLI checks whether the job has finished. */
  private static final long CLI_POLL_INTERVAL_MS = 500;

  /**
   * The extended manual, printed by --helpful.
   *
   * <p>--help lists flags, which tells you what you may type and nothing about what to do.
   * This is the part a first-time user actually needs: how long to give it, what the endings
   * mean, and how to stop it without losing the board.
   */
  /**
   * The optimiser flag surface, appended to --help after the localized text. English on
   * purpose: it changes with the code, and a stale translation of a default is worse
   * than an untranslated truth. (The localized -mt text shipped "cores minus one" for a
   * release whose headline was the measured width-2 default.)
   */
  private static final String OPTIMIZER_HELP_TEXT = """

      OPTIMIZER FLAGS (the full list: docs/command_line_arguments.md)

        --router.optimizer.max_threads=N    optimiser width. DEFAULT 2 (measured quality
                                            point). 1 = single-threaded. Core count is a
                                            ceiling, never a target.
        --router.optimizer.rounds=N         fixed items per pass; switches the automatic
                                            work-window guard OFF.
        --router.optimizer.memory_budget_mb=N  clone-memory cap; width shrinks to fit,
                                            loudly.
        --router.optimizer.board_update_strategy=greedy|global_optimal|hybrid
        --router.optimizer.item_selection_strategy=prioritized|most_to_gain|sequential|random
        --router.optimizer.enabled=false    routing only.

      Routing itself is single-threaded. -mt / --router.max_threads is the RACING width
      (redundant parallel routing attempts, keep the best) and only acts together with
      --router.racing_enabled=true. It does not affect the optimiser.
      """;

  private static final String HELPFUL_TEXT = """
      FREEROUTING - how to get a good board

      THE SHORT VERSION
        freerouting -de board.dsn -do board.ses
      Fifteen minutes by default. Most boards finish well inside it.

      WHAT IT DOES, IN ORDER
        1. Fanout      escapes pins out of fine-pitch packages so routing has pads to reach
        2. Auto-route  connects the nets
        3. Optimise    rips up and re-routes individual items, keeping only improvements

      Each stage stops inside the job's time budget, finishing the pass it is in so the board
      handed on is whole rather than half-applied.

      WHICH STAGES ARE MULTI-THREADED
        Fanout      single-threaded.
        Routing     single-threaded. The only multi-core routing mode is RACING: redundant
                    identical attempts (same settings, different item orderings), best one
                    wins. Opt-in: --router.racing_enabled=true with --router.max_threads=N.
                    Two correctness defects were fixed (deterministic per-thread
                    ordering seeds; memory-bounded copies); it was NOT algorithmically
                    tuned, and measured it returns the same or a worse board than one
                    attempt. An option, not a recommendation.
        Optimising  MULTI-THREADED BY DEFAULT, two threads. This is the stage this release
                    fixed, measured, and governs. Width 1 = single-threaded.

      STAGE: FANOUT - escapes fine-pitch pads; single-threaded
        On by default; --router.fanout.enabled=false skips it for boards without
        fine-pitch packages. It shares the one job budget like every stage.

      STAGE: ROUTING - the found optimum is the defaults
        The defaults are the measured optimum; there is nothing to raise here for speed.
        What you CAN change is the objective it routes toward:
        --router.scoring.via_costs=100    the via-lean profile: fewer vias per connection
                                          at every width, about one completed connection
                                          fewer on dense boards. Applies end to end.

      STAGE: OPTIMISING - the found optimum is the default; the trade is yours
        Default: 2 threads, greedy updates, prioritized selection, automatic work-window
        guard. That combination won the measurements; the others below are real options,
        not recommendations:
        --router.optimizer.max_threads=4  balanced: faster, best length, a few vias more
        --router.optimizer.max_threads=6  the speed setting; beyond 8 is strictly worse
        --router.optimizer.max_threads=1  single-threaded, the 1.0.3 behaviour
        --router.optimizer.rounds=400     fixed work instead of the guard (same quality,
                                          measured; you pay for work past convergence)
        --router.optimizer.enabled=false  routing only: 2-3x faster overall where
                                          routing is quick, no help where it is not
        Core count is a ceiling, never a target - asking for more than the machine has is
        clamped, and the run says so.

      HOW LONG TO GIVE IT
        --router.job_timeout=00:15:00     default
        --router.job_timeout=01:00:00     a board that reported running out of time

      Time is normally spent almost entirely in the optimiser - routing is usually seconds.
      DIFFICULT BOARDS are the exception, and net count does NOT predict them: we measured
      a 111-net board that routes in under a second and a 95-net board that runs for an
      hour. The reliable signal is the run itself: if routing pass #1 is still going at
      minute five, this is an hours-board - stop it, set a budget in hours, and let it run.
      The board is saved at the wall either way.

      READ THE LAST LINE. IT TELLS YOU WHAT TO DO NEXT.
        "Pass finished. No further improvements found."
            As good as this board gets. A longer timeout changes nothing. Do not re-run.
        "Ran out of time."
            Still improving when the clock stopped it. Re-run with a longer job_timeout.
        "Stopped on request."
            You ended it. Whatever it had is written.

      WHEN IT FINISHES: THE REPORT AND THE LOG
      Every run that is not cancelled writes a report next to the log, named for the board
      and the time, and prints its path as its LAST line (the graphical interface offers
      to open it). It states how the run ended, how long it took, how many connections
      were routed of how many, the violation count, and - if anything is unfinished -
      every unrouted connection by net, naming both ends:
          Net 'GND' (1 unrouted connection):
              - J2-A3  ->  U1-4
      That list is the point: a board with a handful of gaps can be finished by hand from
      it, pad to pad. Two runs of the same board leave two reports, so attempts compare.

      The log is on by default and lives beside the reports, under your user directory:
        Windows  %APPDATA%\\freerouting\\logs\\
        Linux    ~/.local/share/freerouting/logs/
        macOS    ~/Library/Application Support/freerouting/logs/
      The FIRST line of every run prints the full path, and the graphical interface shows
      it in the status bar while a run is going.

      STOPPING A RUN WITHOUT LOSING IT
        CLI, while running:  s = stop and keep the board    c = cancel, no output
        GUI:                 the Stop button
        Signals:             SIGTERM and Ctrl-C save the board before exiting

      Closing a console window on Windows may not leave us time to save - Windows terminates
      the process on its own schedule. Press Stop first if the run matters.

      IF THE BOARD COMES BACK UNFINISHED
      Unrouted nets are not always the router giving up early. Raise the timeout once; if the
      count does not move and it reports no further improvements, the board is telling you
      something about placement rather than about the router.

      MORE
        --help    the flag reference; every setting: docs/command_line_arguments.md
        docs/USER-GUIDE.md and docs/command_line_arguments.md in the source tree
      """;

  /**
   * Hard ceiling on the wait, independent of the predicate.
   *
   * <p>Defence in depth: the job's own deadline is capped at 24 hours by the scheduler, so
   * a wait exceeding 25 hours means the state machine failed to reach a terminal state,
   * not that the board is slow. A loop whose only exit is a predicate someone else sets
   * should not be the last line of defence against a hang -- which is exactly what this
   * loop turned out to be.
   */
  private static final long CLI_MAX_WAIT_ITERATIONS =
      (25L * 60L * 60L * 1000L) / CLI_POLL_INTERVAL_MS;

  /**
   * Whether the job has stopped, for any reason.
   *
   * <p>Every terminal state must answer true -- that is what terminal means. The previous
   * inline test named two of the four, so a timed-out or cancelled job left the CLI
   * spinning until the process was killed from outside.
   */
  static boolean isJobFinished(RoutingJobState state) {
    return state.isTerminal();
  }

  /**
   * Whether the job left a board worth writing to the user's output file.
   *
   * <p>A separate question from {@link #isJobFinished}. A deadline stop is a deliberate,
   * well-formed outcome -- the user asked for a time-boxed run and the partial board IS
   * the thing they asked for, so refusing to write it makes the option useless. A job that
   * was cancelled or that died on an error left nothing worth overwriting a file with.
   */
  /** True once the run has told the user which keys end it, so it is said once. */
  private static boolean stopKeysAnnounced = false;

  /**
   * Ends the run if the user pressed s or c.
   *
   * <p>Only when a console is attached. A piped or redirected run -- CI, a script, nohup --
   * has no interactive user and must behave exactly as it did before; reading a key there
   * would at best do nothing and at worst block a headless run forever.
   *
   * <p>Non-blocking by construction: it reads only what is already buffered, so a run with a
   * console but nobody watching it is not slowed down.
   */
  static void pollForStopKey(RoutingJob p_job) {
    if (System.console() == null) {
      return; // not interactive: leave the run exactly as it was
    }
    if (!stopKeysAnnounced) {
      stopKeysAnnounced = true;
      FRLogger.info("  Stop [s] - end now, keep board   Cancel [c] - end now, no output");
    }
    try {
      while (System.in.available() > 0) {
        int key = System.in.read();
        if (key == 's' || key == 'S') {
          FRLogger.info("Stopping. Current pass will finish; board will be written.");
          p_job.stoppedByUser = true;
          p_job.thread.requestStop();
          return;
        }
        if (key == 'c' || key == 'C') {
          FRLogger.info("Cancelling. No output file will be written.");
          p_job.thread.requestStop();
          p_job.state = RoutingJobState.CANCELLED;
          return;
        }
      }
    } catch (java.io.IOException e) {
      // A console that cannot be read is not a reason to abandon a route that is going fine.
      FRLogger.warn("Could not read the keyboard; the run continues. " + e.getMessage());
    }
  }

  static boolean shouldWriteCliOutput(RoutingJobState state) {
    return state.hasUsableOutput();
  }

  /**
   * Writes the routed board to the path the CLI was given.
   *
   * <p>Extracted so the shutdown hook and the normal flow save through the same code. A second
   * writer for the interrupted case would be a second thing to keep correct, and the one that
   * runs rarely is the one that rots.
   */
  static void writeCliOutput(RoutingJob p_job) throws IOException {
    Path outputFilePath = Path.of(globalSettings.initialOutputFile);
    Files.write(outputFilePath, p_job.output.getData().readAllBytes());
  }

  /**
   * Classifies a finished job so the caller can act on it without reading the log.
   *
   * <p>When the board cannot be measured the answer is INCOMPLETE, never COMPLETE:
   * "I could not tell" and "it is clean" must not collapse into the same signal, and of
   * the two possible errors, sending someone to check a board that was fine is far
   * cheaper than telling them a board is fine when nobody knows.
   */
  /**
   * Tells the user which ending they got, because the two look identical and mean opposite
   * things.
   *
   * <p>An unfinished board after the optimiser gave up is finished work: it stopped because
   * it stopped finding improvements, and a longer budget buys nothing. An unfinished board
   * after the clock ran out is interrupted work: it was still improving when it was cut, and
   * more time may help. Without this line a user looking at unrouted nets has no way to tell
   * whether to raise the timeout or stop trying -- which is the only decision they have.
   */
  static void reportHowTheRunEnded(RoutingJob p_job) {
    String message = endingMessage(p_job);
    if (message != null) {
      FRLogger.info(message);
    }
  }

  /**
   * Which ending this run got, as text, or null when there is nothing to say.
   *
   * <p>Separated from the logging so it can be pinned by a test. These strings have been
   * wrong more than once -- they are read as a decision about whether to re-run, so a
   * confident sentence describing the wrong ending is worse than no sentence.
   */
  public static String endingMessage(RoutingJob p_job) {
    if (p_job.stageTimedOut || p_job.state == RoutingJobState.TIMED_OUT) {
      return "Ran out of time. Routing was still in progress when the budget expired."
          + " A longer --router.job_timeout may produce a better board.";
    }
    if (p_job.stoppedByUser) {
      return "Stopped on request. Board written as routed at that point.";
    }
    if (p_job.state == RoutingJobState.CANCELLED) {
      // Cancel is not stop. hasUsableOutput() is COMPLETED || TIMED_OUT, so a cancelled run
      // writes nothing at all -- sharing the stop wording sent users looking for a file that
      // was never created.
      return "Cancelled on request. No output file was written.";
    }
    if (p_job.state == RoutingJobState.COMPLETED) {
      return "Pass finished. No further improvements found."
          + " A longer --router.job_timeout will not change this result.";
    }
    return null;
  }

  private static CliOutcome outcomeFor(RoutingJob job) {
    if (job.state == RoutingJobState.TERMINATED || job.board == null) {
      return CliOutcome.FAILED;
    }
    boolean stoppedEarly = (job.state == RoutingJobState.TIMED_OUT)
        || (job.state == RoutingJobState.CANCELLED);
    try {
      var stats = new app.freerouting.core.scoring.BoardStatistics(job.board);
      return CliOutcome.of(stats.connections.incompleteCount,
          stats.clearanceViolations.totalCount, stoppedEarly);
    } catch (Exception e) {
      FRLogger.warn("Could not measure the final board to classify the run outcome; "
          + "reporting it as incomplete rather than claiming a clean board. "
          + e.getMessage());
      return stoppedEarly ? CliOutcome.STOPPED_EARLY : CliOutcome.INCOMPLETE;
    }
  }

  /**
   * Reports a failure to read the input file.
   *
   * <p>A file that is not there is the user's typo, not our defect, and answering it with
   * a Java stack trace tells them nothing they can act on while implying the program
   * broke. Stack traces are for OUR bugs. An expected, explainable condition gets a
   * sentence naming the path and what to check.
   */
  private static void logInputFileFailure(String path, Exception e) {
    if ((e instanceof java.io.FileNotFoundException)
        || (e instanceof java.nio.file.NoSuchFileException)) {
      FRLogger.error("Input file not found: '" + path + "'. Check the path is correct and"
          + " that you are running from the directory you think you are.", null);
    } else if (e instanceof java.nio.file.AccessDeniedException) {
      FRLogger.error("Input file '" + path + "' cannot be read: permission denied.", null);
    } else {
      // Anything else is unexplained, and there the stack trace is the useful part.
      FRLogger.error("Couldn't load the input file '" + path + "': " + e.getMessage(), e);
    }
  }

  private static CliOutcome InitializeCLI(GlobalSettings globalSettings) {
    if ((globalSettings.initialInputFile == null) || (globalSettings.initialOutputFile == null)) {
      FRLogger.error(
          "Both an input file and an output file must be specified with command line arguments if you are running in CLI mode.",
          null);
      return CliOutcome.FAILED;
    }

    // Start a new Freerouting session
    var cliSession = SessionManager
        .getInstance()
        .createSession(UUID.fromString(globalSettings.userProfileSettings.userId),
            "Freerouting/" + globalSettings.version);

    // Create a new routing job
    RoutingJob routingJob = new RoutingJob(cliSession.id);
    // So an external stop can ask this job to save instead of killing it: the CLI does not
    // go through RoutingJobScheduler, so the hook cannot find it any other way.
    app.freerouting.management.GracefulShutdown.register(routingJob, () -> {
      // Runs only if the job actually reached a final state; the hook checks that. Guarded
      // again here because a board written from a job with nothing usable would be a file
      // that looks like a result and is not.
      if (!shouldWriteCliOutput(routingJob.state)) {
        return;
      }
      try {
        writeCliOutput(routingJob);
        FRLogger.info("Wrote the board to '" + globalSettings.initialOutputFile
            + "' after being asked to stop.");
      } catch (IOException e) {
        FRLogger.error("Could not write the board to '" + globalSettings.initialOutputFile
            + "' after being asked to stop; the routing work is lost.", e);
      }
    });

    // Load the input file
    DsnFileSettings inputFileSettings = null;
    try {
      routingJob.setInput(globalSettings.initialInputFile);
      inputFileSettings = new DsnFileSettings(routingJob.input.getData(), routingJob.input.getFilename());
    } catch (Exception e) {
      logInputFileFailure(globalSettings.initialInputFile, e);
    }

    if (routingJob.input == null) {
      FRLogger.warn("Couldn't read the input file '" + globalSettings.initialInputFile + "', aborting.");
      return CliOutcome.FAILED;
    }

    cliSession.addJob(routingJob);

    var desiredOutputFile = new File(globalSettings.initialOutputFile);
    if ((desiredOutputFile != null) && desiredOutputFile.exists()) {
      if (!desiredOutputFile.delete()) {
        FRLogger.warn("Couldn't delete the file '" + globalSettings.initialOutputFile + "'");
      }
    }

    routingJob.tryToSetOutputFile(new File(globalSettings.initialOutputFile));

    var settingsMerger = globalSettings.settingsMergerProtype.clone();
    settingsMerger.addOrReplaceSources(
        new DsnFileSettings(routingJob.input.getData(), routingJob.input.getFilename()));

    routingJob.routerSettings = settingsMerger.merge();
    routingJob.drcSettings = Freerouting.globalSettings.drcSettings.clone();
    routingJob.state = RoutingJobState.READY_TO_START;

    // Wait for the RoutingJobScheduler to do its work
    long waitedIterations = 0;
    while (!isJobFinished(routingJob.state)) {
      if (++waitedIterations > CLI_MAX_WAIT_ITERATIONS) {
        // Never spin forever on a state someone else is responsible for setting.
        FRLogger.error("The routing job did not reach a final state within 25 hours and is"
            + " still reported as " + routingJob.state + ". Giving up waiting; no output"
            + " file will be written. This is a defect in the job state machine, not a"
            + " property of the board.", null);
        break;
      }
      try {
        Thread.sleep(CLI_POLL_INTERVAL_MS);
        pollForStopKey(routingJob);
      } catch (InterruptedException _) {
        routingJob.state = RoutingJobState.CANCELLED;
        break;
      }
    }

    reportHowTheRunEnded(routingJob);

    // The final run report: counts and the per-pin unrouted list, in a file the user
    // can keep (spec: docs/fork/FINAL-REPORT-SPEC.md). Cancelled runs write nothing.
    java.nio.file.Path finalReportPath = app.freerouting.core.FinalRunReport.write(
        routingJob, routingJob.board, endingMessage(routingJob));

    // Save the output file
    boolean outputWriteFailed = false;
    if (shouldWriteCliOutput(routingJob.state)) {
      try {
        writeCliOutput(routingJob);
      } catch (IOException e) {
        FRLogger.error("Couldn't save the output file '" + globalSettings.initialOutputFile + "'", e);
        // A run that routed for an hour and could not save it did not succeed, whatever
        // the routing result was. This used to return success regardless (FS11).
        outputWriteFailed = true;
      }

      printSponsorMessageIfDue(globalSettings);
    }

    // Last line by design: the user learns the report exists from the final thing the
    // run prints (spec section 3).
    if (finalReportPath != null) {
      FRLogger.info("Run report: " + finalReportPath);
    }

    if (outputWriteFailed) {
      return CliOutcome.FAILED;
    }
    return outcomeFor(routingJob);
  }


  /**
   * Prints the sponsorship note to stdout, not the log, once a user has completed a
   * few jobs without leaving contact details.
   *
   * <p>Lifted out of the routing entry point: soliciting sponsorship is not part of
   * routing a board, and inlining it meant the method that loads, routes and saves a
   * design also owned when to ask for money.
   */
  private static void printSponsorMessageIfDue(GlobalSettings globalSettings) {
    if ((globalSettings.statistics.jobsCompleted >= 5)
        && globalSettings.userProfileSettings.userEmail.isEmpty()) {
      String nl = System.lineSeparator();
      System.out.println(
          nl
          + "╔══════════════════════════════════════════════════════════════════╗" + nl
          + "║           Thank you for using Freerouting!                       ║" + nl
          + "║                                                                  ║" + nl
          + "║  If you would like to support the project, please consider       ║" + nl
          + "║  sponsoring me at https://github.com/sponsors/andrasfuchs        ║" + nl
          + "║  Even a small monthly donation is greatly appreciated!           ║" + nl
          + "╚══════════════════════════════════════════════════════════════════╝"
      );
    }
  }

  private static boolean InitializeDRC(GlobalSettings globalSettings) {
    if (globalSettings.initialInputFile == null) {
      FRLogger.error("An input file must be specified with -de argument in DRC mode.", null);
      return false;
    }

    // Start a new Freerouting session
    var drcSession = SessionManager
        .getInstance()
        .createSession(UUID.fromString(globalSettings.userProfileSettings.userId),
            "Freerouting/" + globalSettings.version);

    // Create a new routing job (but won't route it)
    RoutingJob drcJob = new RoutingJob(drcSession.id);
    drcJob.drc = globalSettings.drc_report_file;
    try {
      FRLogger.info("Loading DSN file for DRC: " + globalSettings.initialInputFile);
      drcJob.setInput(globalSettings.initialInputFile);
    } catch (Exception e) {
      logInputFileFailure(globalSettings.initialInputFile, e);
      System.exit(1);
    }

    // Load the board without routing
    if (!BoardLoader.loadBoardIfNeeded(drcJob)) {
      FRLogger.error("Failed to load board for DRC check", null);
      System.exit(1);
    }

    // Load session file if specified for DRC
    if (globalSettings.design_session_filename != null) {
      try {
        java.io.File sessionFile = new java.io.File(globalSettings.design_session_filename);
        if (sessionFile.exists()) {
          if (globalSettings.design_session_filename.toLowerCase().endsWith(".json")) {
            FRLogger.info("Loading KiCad JSON session file for DRC: " + globalSettings.design_session_filename);
            try (java.io.FileReader jsonReader = new java.io.FileReader(sessionFile)) {
              app.freerouting.io.kicad.KiCadJsonReader.importSession(jsonReader, drcJob.board);
              FRLogger.info("KiCad JSON session file loaded for DRC successfully");
            }
          } else {
            FRLogger.info("Loading SES file for DRC: " + globalSettings.design_session_filename);
            try (java.io.FileInputStream sesStream = new java.io.FileInputStream(sessionFile)) {
              SesImportSummary summary = SesReader.read(sesStream, drcJob.board);
              FRLogger.info("SES file loaded for DRC: " + summary.wiresImported() + " wires, "
                  + summary.viasImported() + " vias imported"
                  + (summary.errorsEncountered() > 0 ? " (" + summary.errorsEncountered() + " errors)" : ""));
            }
          }
        } else {
          FRLogger.warn("Session file for DRC not found: " + globalSettings.design_session_filename);
        }
      } catch (Exception e) {
        FRLogger.error("Failed to load session file for DRC", e);
      }
    }

    // Run DRC check
    DesignRulesChecker drcChecker = new DesignRulesChecker(drcJob.board, globalSettings.drcSettings);

    // Determine coordinate unit (default to mm)
    String coordinateUnit = "mm";

    // Generate DRC report
    String sourceFileName = new File(globalSettings.initialInputFile).getName();
    app.freerouting.drc.DrcReport report = drcChecker.generateReport(sourceFileName, coordinateUnit);
    
    // Calculate final quality score for DRC report
    try {
      var settingsMerger = globalSettings.settingsMergerProtype.clone();
      settingsMerger.addOrReplaceSources(
          new DsnFileSettings(drcJob.input.getData(), drcJob.input.getFilename()));
      var routerSettings = settingsMerger.merge();
      var finalStats = drcJob.board.get_statistics();
      report.quality_score = (double) finalStats.getNormalizedScore(routerSettings.scoring);
    } catch (Exception e) {
      FRLogger.warn("Failed to calculate quality score for DRC report: " + e.getMessage());
    }
    
    String drcReportJson = app.freerouting.util.gson.GsonProvider.GSON.toJson(report);

    // Output the DRC report
    if (drcJob.drc != null) {
      String outputFileName = drcJob.drc.getAbsolutePath();
      // Write to file
      try {
        Path outputFilePath = Path.of(outputFileName);
        Files.write(outputFilePath, drcReportJson.getBytes(StandardCharsets.UTF_8));
        FRLogger.info("DRC report written to: " + outputFileName);
      } catch (IOException e) {
        FRLogger.error("Couldn't save the DRC report to '" + outputFileName + "'", e);
        System.exit(1);
      }
    } else {
      // Print to console
      IO.println(drcReportJson);
    }

    return true;
  }

  private static void ShutdownApplication() {
    // Stop the API server
    try {
      if (apiServer != null) {
        apiServer.stop();
      }
      if (mcpServer != null) {
        mcpServer.stop();
      }
    } catch (Exception e) {
      FRLogger.error("Error stopping API server", e);
    }

    FRAnalytics.appClosed();
  }

  public static Server InitializeAPI(ApiServerSettings apiServerSettings) {
    // Check if there are any endpoints defined
    if (apiServerSettings.endpoints.length == 0) {
      FRLogger.warn("Can't start API server, because no endpoints are defined in ApiServerSettings.");
      return null;
    }

    // Start the Jetty server
    Server apiServer = new Server();

    // Add all endpoints as connectors
    for (String endpointUrl : apiServerSettings.endpoints) {
      endpointUrl = endpointUrl.toLowerCase();
      String[] endpointParts = endpointUrl.split("://");
      String protocol = endpointParts[0];
      String hostAndPort = endpointParts[1];
      String[] hostAndPortParts = hostAndPort.split(":");
      String host = hostAndPortParts[0];
      int port = Integer.parseInt(hostAndPortParts[1]);

      // Check if the protocol is HTTP or HTTPS
      if (!"http".equals(protocol) && !"https".equals(protocol)) {
        FRLogger.warn("Can't use the endpoint '%s' for the API server, because its protocol is not HTTP or HTTPS."
            .formatted(endpointUrl));
        continue;
      }

      // Check if the http is allowed
      if (!apiServerSettings.isHttpAllowed && "http".equals(protocol)) {
        FRLogger.warn(
            "Can't use the endpoint '%s' for the API server, because HTTP is not allowed.".formatted(endpointUrl));
        continue;
      }

      // Warn the user that HTTPS is not implemented yet
      if ("https".equals(protocol)) {
        FRLogger.warn("HTTPS support is not implemented yet, falling back to HTTP.".formatted(endpointUrl));
      }

      ServerConnector connector = new ServerConnector(apiServer);
      connector.setHost(host);
      connector.setPort(port);
      apiServer.addConnector(connector);
    }

    // Set up the Servlet Context Handler
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");

    Handler apiHandler = context;

    // Configure CORS if origins are provided
    if (apiServerSettings.cors_origins != null && !apiServerSettings.cors_origins.equals("")) {
      String allowedOrigins = apiServerSettings.cors_origins;

      CrossOriginHandler corsHandler = new CrossOriginHandler();
      corsHandler.setAllowCredentials(true);
      corsHandler.setAllowedOriginPatterns(splitCommaSeparated(allowedOrigins));
      corsHandler.setAllowedMethods(Set.of("HEAD", "GET", "POST", "PUT", "DELETE", "OPTIONS"));
      corsHandler.setAllowedHeaders(Set.of(
          "X-Requested-With",
          "Content-Type",
          "Accept",
          "Origin",
          "Authorization",
          "Freerouting-Profile-ID",
          "Freerouting-Profile-Email",
          "Freerouting-Environment-Host"));
      corsHandler.setHandler(context);

      PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
      pathMappingsHandler.addMapping(new ServletPathSpec("/v1/*"), corsHandler);
      pathMappingsHandler.addMapping(new ServletPathSpec("/*"), context);
      apiHandler = pathMappingsHandler;

      FRLogger.info("CORS configured for origins: " + allowedOrigins);
    }

    apiServer.setHandler(apiHandler);

    // Set up the Jersey Servlet that handles the API
    ServletHolder jerseyServlet = context.addServlet(ServletContainer.class, "/*");
    jerseyServlet.setInitOrder(0);
    jerseyServlet.setInitParameter("jersey.config.server.provider.packages", "app.freerouting.api");
    jerseyServlet.setInitParameter("jersey.config.application.disableJsonBinding", "true");

    // Add Listeners
    context.addEventListener(new AppContextListener());

    // Instead of apiServer.join(), start in a new thread
    new Thread(() -> {
      try {
        apiServer.start();
        apiServer.join(); // This will now run in the new thread
      } catch (Exception e) {
        FRLogger.error("Error starting or joining API server", e);
        if (globalSettings != null) {
          globalSettings.apiServerSettings.isRunning = false;
        }
      }
    }).start();

    return apiServer;
  }

  public static Server InitializeMCP(McpServerSettings mcpServerSettings) {
    if (mcpServerSettings.endpoints.length == 0) {
      FRLogger.warn("Can't start MCP server, because no endpoints are defined in McpServerSettings.");
      return null;
    }

    Server mcpServer = new Server();

    for (String endpointUrl : mcpServerSettings.endpoints) {
      endpointUrl = endpointUrl.toLowerCase();
      String[] endpointParts = endpointUrl.split("://");
      String protocol = endpointParts[0];
      String hostAndPort = endpointParts[1];
      String[] hostAndPortParts = hostAndPort.split(":");
      String host = hostAndPortParts[0];
      int port = Integer.parseInt(hostAndPortParts[1]);

      if (!"http".equals(protocol) && !"https".equals(protocol)) {
        FRLogger.warn("Can't use the endpoint '%s' for the MCP server, because its protocol is not HTTP or HTTPS."
            .formatted(endpointUrl));
        continue;
      }

      if (!mcpServerSettings.isHttpAllowed && "http".equals(protocol)) {
        FRLogger.warn(
            "Can't use the endpoint '%s' for the MCP server, because HTTP is not allowed.".formatted(endpointUrl));
        continue;
      }

      if ("https".equals(protocol)) {
        FRLogger.warn("HTTPS support is not implemented yet, falling back to HTTP.".formatted(endpointUrl));
      }

      ServerConnector connector = new ServerConnector(mcpServer);
      connector.setHost(host);
      connector.setPort(port);
      mcpServer.addConnector(connector);
    }

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");

    Handler mcpHandler = context;

    if (mcpServerSettings.cors_origins != null && !mcpServerSettings.cors_origins.equals("")) {
      String allowedOrigins = mcpServerSettings.cors_origins;

      CrossOriginHandler corsHandler = new CrossOriginHandler();
      corsHandler.setAllowCredentials(true);
      corsHandler.setAllowedOriginPatterns(splitCommaSeparated(allowedOrigins));
      corsHandler.setAllowedMethods(Set.of("HEAD", "GET", "POST", "PUT", "DELETE", "OPTIONS"));
      corsHandler.setAllowedHeaders(Set.of(
          "X-Requested-With",
          "Content-Type",
          "Accept",
          "Origin",
          "Authorization",
          "Freerouting-Profile-ID",
          "Freerouting-Profile-Email",
          "Freerouting-Environment-Host"));
      corsHandler.setHandler(context);

      PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
      pathMappingsHandler.addMapping(new ServletPathSpec("/v1/mcp/*"), corsHandler);
      pathMappingsHandler.addMapping(new ServletPathSpec("/*"), context);
      mcpHandler = pathMappingsHandler;

      FRLogger.info("MCP CORS configured for origins: " + allowedOrigins);
    }

    mcpServer.setHandler(mcpHandler);

    ServletHolder jerseyServlet = context.addServlet(ServletContainer.class, "/*");
    jerseyServlet.setInitOrder(0);
    jerseyServlet.setInitParameter("jakarta.ws.rs.Application", McpApplication.class.getName());
    jerseyServlet.setInitParameter("jersey.config.application.disableJsonBinding", "true");

    context.addEventListener(new McpContextListener());

    JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
      wsContainer.addEndpoint(McpWebSocketEndpoint.class);
    });

    new Thread(() -> {
      try {
        mcpServer.start();
        mcpServer.join();
      } catch (Exception e) {
        FRLogger.error("Error starting or joining MCP server", e);
        if (globalSettings != null) {
          globalSettings.mcpServerSettings.isRunning = false;
        }
      }
    }).start();

    return mcpServer;
  }

  public static void startMcpStdioBridge(java.io.PrintStream originalOut, Server server) {
    Thread bridgeThread = new Thread(() -> {
      try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        int localPort = -1;
        while (localPort <= 0) {
          if (server != null && server.getConnectors().length > 0 && server.getConnectors()[0] instanceof ServerConnector connector) {
            localPort = connector.getLocalPort();
          }
          if (localPort <= 0) {
            try {
              Thread.sleep(50);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
              return;
            }
          }
        }

        String resolvedProfileId = System.getenv("FREEROUTING_PROFILE_ID");
        if (resolvedProfileId == null || resolvedProfileId.isBlank()) {
          resolvedProfileId = System.getenv("FREEROUTING__PROFILE__ID");
        }
        if ((resolvedProfileId == null || resolvedProfileId.isBlank()) && globalSettings != null && globalSettings.userProfileSettings != null) {
          resolvedProfileId = globalSettings.userProfileSettings.userId;
        }
        if (resolvedProfileId == null || resolvedProfileId.isBlank()) {
          resolvedProfileId = "00000000-0000-0000-0000-000000000000";
        }

        String resolvedProfileEmail = System.getenv("FREEROUTING_PROFILE_EMAIL");
        if (resolvedProfileEmail == null || resolvedProfileEmail.isBlank()) {
          resolvedProfileEmail = System.getenv("FREEROUTING__PROFILE__EMAIL");
        }
        if ((resolvedProfileEmail == null || resolvedProfileEmail.isBlank()) && globalSettings != null && globalSettings.userProfileSettings != null) {
          resolvedProfileEmail = globalSettings.userProfileSettings.userEmail;
        }

        String resolvedHost = System.getenv("FREEROUTING_ENVIRONMENT_HOST");
        if (resolvedHost == null || resolvedHost.isBlank()) {
          resolvedHost = System.getenv("FREEROUTING__ENVIRONMENT__HOST");
        }

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.URI targetUri = java.net.URI.create("http://127.0.0.1:" + localPort + "/v1/mcp");

        String line;
        while ((line = reader.readLine()) != null) {
          if (line.trim().isEmpty()) {
            continue;
          }
          try {
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder(targetUri)
                .header("Content-Type", "application/json")
                .header("X-Internal-Bridge-Token", bridgeToken)
                .header("Freerouting-Profile-ID", resolvedProfileId);

            if (resolvedProfileEmail != null && !resolvedProfileEmail.isBlank()) {
              reqBuilder.header("Freerouting-Profile-Email", resolvedProfileEmail);
            }
            if (resolvedHost != null && !resolvedHost.isBlank()) {
              reqBuilder.header("Freerouting-Environment-Host", resolvedHost);
            }

            java.net.http.HttpRequest request = reqBuilder
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8))
                .build();

            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();
            if (responseBody != null) {
              String singleLineResponse = responseBody.replace("\r", "").replace("\n", "");
              originalOut.println(singleLineResponse);
              originalOut.flush();
            }
          } catch (Exception e) {
            FRLogger.error("Error in MCP stdio bridge request forwarding", e);
          }
        }
        FRLogger.info("MCP stdio bridge detected EOF, shutting down application.");
        System.exit(0);
      } catch (IOException e) {
        FRLogger.error("Error reading from System.in in MCP stdio bridge", e);
        System.exit(1);
      }
    }, "mcp-stdio-bridge");
    bridgeThread.setDaemon(true);
    bridgeThread.start();
  }


  private static Set<String> splitCommaSeparated(String value) {
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(token -> !token.isEmpty())
        .collect(Collectors.toSet());
  }

  private static Path resolveLogPath(String input, Path defaultDir) {
    if (input == null || input.isBlank()) {
      // logs/ subdirectory, not the user-data root: the root holds freerouting.json and
      // the GUI state, and a rolling ring of log files next to the config is clutter
      // that makes both harder to find.
      return defaultDir.resolve("logs").resolve("freerouting.log").normalize().toAbsolutePath();
    }

    // In Windows the leading "." character means current directory
    if (input.startsWith(".")) {
      var currentDir = Path.of(System.getProperty("user.dir"));
      input = currentDir + input.substring(1);
    }

    Path path = Path.of(input).normalize().toAbsolutePath();
    boolean isFile = path.getFileName().toString().toLowerCase().endsWith(".log");
    String filename = isFile ? path.getFileName().toString() : "freerouting.log";
    Path folderPath = isFile ? path.getParent() : path;

    // Check if the directory exists, and create it if needed
    if (folderPath != null && !folderPath.toFile().exists()) {
      try {
        Files.createDirectories(folderPath);
      } catch (IOException e) {
        // Failed to create directory, fallback to default
        return defaultDir.resolve(filename).normalize().toAbsolutePath();
      }
    }

    return folderPath.resolve(filename).normalize().toAbsolutePath();
  }

  /**
   * The entry point of the Freerouting application
   *
   * @param args
   */
  void main(String[] args) {
    // Be a good citizen about being told to go away. SIGTERM, Ctrl-C, a container being
    // reclaimed, the machine suspending or shutting down: all of them used to take the JVM
    // down with the routed board still in memory and nothing written (defect 28). The job's
    // own deadline was already graceful; every stop a USER can initiate was not, which is
    // exactly backwards. Installed first so it covers the whole run, not just the part after
    // the settings parse.
    app.freerouting.management.GracefulShutdown.install();

    originalSystemOut = System.out;
    boolean isStdioMode = false;
    if (args.length > 0) {
      for (String arg : args) {
        if (arg.startsWith("--mcp_server.stdio=")) {
          String val = arg.substring("--mcp_server.stdio=".length());
          if ("true".equalsIgnoreCase(val) || "1".equals(val)) {
            isStdioMode = true;
          }
        }
      }
    }
    if (System.getenv("FREEROUTING__MCP_SERVER__STDIO") != null) {
      String envVal = System.getenv("FREEROUTING__MCP_SERVER__STDIO");
      if ("true".equalsIgnoreCase(envVal) || "1".equals(envVal)) {
        isStdioMode = true;
      }
    }
    if (isStdioMode) {
      System.setOut(System.err);
    }

    // CRITICAL: Set up logging configuration BEFORE any logging occurs
    // This must happen before FRLogger.traceEntry() or any other logging call

    // the first thing we need to do is to determine the user directory, because all
    // settings and logs will be located there
    // 1, platform app-data by default -- config and logs must survive a reboot. The
    // temp directory (the inherited default) is the documented last resort only.
    Path userdataPath = app.freerouting.settings.GlobalSettings.defaultUserDataPath();
    String userdataPathSource = "default (platform app-data)";
    // 2, check if we need to override it with the "FREEROUTING__USER_DATA_PATH"
    // environment variable value
    if (System.getenv("FREEROUTING__USER_DATA_PATH") != null
        && !System.getenv("FREEROUTING__USER_DATA_PATH").isBlank()) {
      userdataPath = Path.of(System.getenv("FREEROUTING__USER_DATA_PATH"));
      userdataPathSource = "environment variable FREEROUTING__USER_DATA_PATH";
    } else if (System.getenv("FREEROUTING__LOGGING__FILE__LOCATION") != null
        && !System.getenv("FREEROUTING__LOGGING__FILE__LOCATION").isBlank()) {
      userdataPath = Path.of(System.getenv("FREEROUTING__LOGGING__FILE__LOCATION"));
      userdataPathSource = "environment variable FREEROUTING__LOGGING__FILE__LOCATION (deprecated fallback)";
    }
    // 3, check if we need to override it with the "--user_data_path={directory}"
    // command line argument
    if (args.length > 0 && Arrays
        .stream(args)
        .anyMatch(s -> s.startsWith("--user_data_path="))) {
      var userDataPathArg = Arrays
          .stream(args)
          .filter(s -> s.startsWith("--user_data_path="))
          .findFirst();

      if (userDataPathArg.isPresent()) {
        String argValue = userDataPathArg.get().substring("--user_data_path=".length());
        if (!argValue.isBlank()) {
          userdataPath = Path.of(argValue);
          userdataPathSource = "CLI argument --user_data_path";
        }
      }
    }
    // 4, create the directory if it doesn't exist yet; directory creation is also
    // attempted lazily when the first write happens (in saveAsJson), so a failure
    // here is non-fatal – but we print a warning to stderr because logging is not
    // initialised at this point.
    if (!userdataPath.toFile().exists()) {
      if (!userdataPath.toFile().mkdirs()) {
        System.err.println("WARNING: Could not create user-data directory '" + userdataPath
            + "' (source: " + userdataPathSource + "). "
            + "Freerouting will attempt to create it when writing files. "
            + "If this persists, check permissions for the specified path.");
      }
    } else {
      // Directory exists — proactively check read and write permissions so that
      // permission problems are surfaced immediately rather than at first I/O.
      if (!userdataPath.toFile().canRead()) {
        System.err.println("WARNING: User-data directory '" + userdataPath
            + "' (source: " + userdataPathSource + ") exists but is NOT READABLE. "
            + "freerouting.json cannot be loaded. "
            + "Check that the process has read permission on the directory. "
            + "In Docker deployments, verify the volume mount and file ownership.");
      }
      if (!userdataPath.toFile().canWrite()) {
        System.err.println("WARNING: User-data directory '" + userdataPath
            + "' (source: " + userdataPathSource + ") exists but is NOT WRITABLE. "
            + "freerouting.json cannot be saved and settings won't be persisted. "
            + "Check that the process has write permission on the directory. "
            + "In Docker deployments, verify the volume mount and file ownership.");
      }
    }
    // capture for later use once logging is available
    final String resolvedUserdataPathSource = userdataPathSource;
    // 5, always register the resolved path with GlobalSettings – even when the
    // directory could not be created yet.  saveAsJson() calls
    // Files.createDirectories() before each write, so the directory will be
    // created on demand.  Prior to this fix the path was silently ignored (and
    // the default temp-dir path was locked in) whenever mkdirs() returned false.
    GlobalSettings.setUserDataPath(userdataPath);
    // 6, make sure that this setting can't be changed later on
    GlobalSettings.lockUserDataPath();

    // Parse logging settings from environment variables and command line arguments
    // These will be used to configure log4j2 BEFORE it initializes
    boolean fileLoggingEnabled = true;
    boolean consoleLoggingEnabled = true;
    String fileLoggingLevel = "INFO";
    String consoleLoggingLevel = "INFO";
    String fileLoggingLocation = null;
    String fileLoggingPattern = null;

    if (System.getenv("FREEROUTING__LOGGING__FILE__ENABLED") != null) {
      fileLoggingEnabled = Boolean.parseBoolean(System.getenv("FREEROUTING__LOGGING__FILE__ENABLED"));
    }
    if (System.getenv("FREEROUTING__LOGGING__CONSOLE__ENABLED") != null) {
      consoleLoggingEnabled = Boolean.parseBoolean(System.getenv("FREEROUTING__LOGGING__CONSOLE__ENABLED"));
    }
    if (System.getenv("FREEROUTING__LOGGING__FILE__LEVEL") != null) {
      fileLoggingLevel = System.getenv("FREEROUTING__LOGGING__FILE__LEVEL");
    }
    if (System.getenv("FREEROUTING__LOGGING__CONSOLE__LEVEL") != null) {
      consoleLoggingLevel = System.getenv("FREEROUTING__LOGGING__CONSOLE__LEVEL");
    }
    if (System.getenv("FREEROUTING__LOGGING__FILE__LOCATION") != null) {
      fileLoggingLocation = System.getenv("FREEROUTING__LOGGING__FILE__LOCATION");
    }
    if (System.getenv("FREEROUTING__LOGGING__FILE__PATTERN") != null) {
      fileLoggingPattern = System.getenv("FREEROUTING__LOGGING__FILE__PATTERN");
    }

    if (args.length > 0) {
      for (String arg : args) {
        if (arg.startsWith("--logging.file.enabled=")) {
          fileLoggingEnabled = Boolean.parseBoolean(arg.substring("--logging.file.enabled=".length()));
        } else if (arg.startsWith("--logging.console.enabled=")) {
          consoleLoggingEnabled = Boolean.parseBoolean(arg.substring("--logging.console.enabled=".length()));
        } else if (arg.startsWith("--logging.file.level=")) {
          fileLoggingLevel = arg.substring("--logging.file.level=".length());
        } else if (arg.startsWith("--logging.console.level=")) {
          consoleLoggingLevel = arg.substring("--logging.console.level=".length());
        } else if (arg.startsWith("--logging.file.location=")) {
          fileLoggingLocation = arg.substring("--logging.file.location=".length());
        } else if (arg.startsWith("--logging.file.pattern=")) {
          fileLoggingPattern = arg.substring("--logging.file.pattern=".length());
        } else if (arg.startsWith("--debug.enable_detailed_logging=")) {
          boolean detailed = Boolean.parseBoolean(arg.substring("--debug.enable_detailed_logging=".length()));
          if (detailed) {
            fileLoggingLevel = "TRACE";
            FRLogger.granularTraceEnabled = true;
          }
        } else if ("-dl".equals(arg)) {
          fileLoggingEnabled = false;
        } else if ("-ll".equals(arg)) {
          // simple peek for -ll
          int index = Arrays.asList(args).indexOf("-ll");
          if (index >= 0 && index < args.length - 1) {
            consoleLoggingLevel = args[index + 1];
          }
        }
      }
    }

    // Resolve the log file location
    if (fileLoggingLocation == null || fileLoggingLocation.isBlank()) {
      fileLoggingLocation = resolveLogPath(null, userdataPath).toString();
    } else {
      fileLoggingLocation = resolveLogPath(fileLoggingLocation, userdataPath).toString();
    }

    // Set system properties for log4j2 ConfigurationFactory to read
    // This MUST happen before any logging calls

    // Disable JNDI lookups — Freerouting does not use them, and the java.naming module
    // is intentionally excluded from the jlink runtime to reduce attack surface
    // (Log4Shell / CVE-2021-44228). Without this property Log4j2 tries to load
    // javax.naming.Context at bootstrap and emits a noisy WARN for every lookup plugin.
    System.setProperty("log4j2.disableJndi", "true");

    System.setProperty("log4j2.configurationFactory", "app.freerouting.logger.Log4j2ConfigurationFactory");
    System.setProperty("freerouting.logging.console.enabled", String.valueOf(consoleLoggingEnabled));
    System.setProperty("freerouting.logging.console.level", consoleLoggingLevel);
    System.setProperty("freerouting.logging.file.enabled", String.valueOf(fileLoggingEnabled));
    System.setProperty("freerouting.logging.file.level", fileLoggingLevel);
    System.setProperty("freerouting.logging.file.location", fileLoggingLocation);

    // The first thing the log says is where it lives. Both interfaces inherit this
    // line, so "where do I watch progress" always has a printed answer.
    FRLogger.info("Full log: " + fileLoggingLocation);

    if (fileLoggingPattern != null) {
      System.setProperty("freerouting.logging.file.pattern", fileLoggingPattern);
    }

    // FORCE RECONFIGURATION
    // Log4j2 might have initialized early (before we set these properties).
    // We force it to reload the configuration using our Factory, which will now see
    // the correct properties.
    ((LoggerContext) LogManager.getContext(false)).reconfigure();

    // NOW we can start logging - log4j2 will initialize with our configuration
    FRLogger.traceEntry("MainApplication.main()");

    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (ClassNotFoundException | InstantiationException | UnsupportedLookAndFeelException
        | IllegalAccessException ex) {
      FRLogger.error(ex.getLocalizedMessage(), ex);
    }

    // Log system information
    FRLogger.info("Freerouting " + VERSION_NUMBER_STRING);
    FRLogger.debug("[startup] user-data path : " + GlobalSettings.getUserDataPath() + "  (source: " + resolvedUserdataPathSource + ")");
    FRLogger.debug("[startup] log file       : " + fileLoggingLocation);
    Thread.setDefaultUncaughtExceptionHandler(new DefaultExceptionHandler());

    try {
      globalSettings = GlobalSettings.load();
      FRLogger.debug("Settings loaded from '" + GlobalSettings.getConfigurationFilePath() + "'.");
    } catch (NoSuchFileException _) {
      // Normal first-run condition — the file does not exist yet and will be
      // created below with default values.
      FRLogger.debug("No freerouting.json found at '" + GlobalSettings.getConfigurationFilePath()
          + "' — will create one with default settings.");
    } catch (AccessDeniedException e) {
      FRLogger.warn("Cannot read freerouting.json at '"
          + GlobalSettings.getConfigurationFilePath() + "': " + e.getReason()
          + ". The file and/or its parent directory may have incorrect permissions. "
          + "Check that the process has read access. "
          + "In Docker deployments, verify the volume mount configuration. "
          + "Freerouting will start with default settings.");
    } catch (IOException e) {
      FRLogger.warn("Failed to load freerouting.json from '"
          + GlobalSettings.getConfigurationFilePath() + "': " + e.getMessage()
          + ". Freerouting will start with default settings.");
    }

    // Detect stale logging.file.location in the loaded JSON.
    // Old versions of Freerouting stored the absolute log path in freerouting.json and
    // applied it at startup, potentially redirecting the log away from the volume mount.
    // The current code does NOT re-apply the stored path (logging is already configured
    // above via system properties), but we warn if we detect a mismatch so operators
    // can spot a stale config.
    if (globalSettings != null
        && globalSettings.logging.file.location != null
        && !globalSettings.logging.file.location.isBlank()
        && !globalSettings.logging.file.location.equals(fileLoggingLocation)) {
      FRLogger.warn("[startup] freerouting.json contains a stale 'logging.file.location' value: '"
          + globalSettings.logging.file.location + "'. "
          + "The actual log file is being written to '" + fileLoggingLocation + "' (as resolved at startup). "
          + "The stale value will be corrected in freerouting.json on next save. "
          + "If you see this in Docker, the old JSON was written by an earlier version that stored "
          + "the host path; the fix is to delete freerouting.json so it is regenerated with the correct path.");
    }
    // Always keep the stored log path in sync with the resolved path so the JSON
    // self-heals and old images that do apply the stored path will get the right value.
    if (globalSettings != null) {
      globalSettings.logging.file.location = fileLoggingLocation;
    }

    // Warn if mcp_server.stdio was set in freerouting.json but not via CLI/env.
    // The stdout redirect must happen before logging is initialised, so the JSON setting
    // is too late and is silently ignored.  Operators who set it only in JSON would get
    // non-JSON protocol noise on stdout, breaking the MCP stdio transport.
    if (!isStdioMode
        && globalSettings != null
        && globalSettings.mcpServerSettings != null
        && Boolean.TRUE.equals(globalSettings.mcpServerSettings.isStdioMode)) {
      FRLogger.warn("[startup] 'mcp_server.stdio=true' was found in freerouting.json but is being ignored. "
          + "The stdio redirect must be requested before logging is initialised and therefore "
          + "can only be set via the '--mcp_server.stdio=true' CLI argument or the "
          + "'FREEROUTING__MCP_SERVER__STDIO=true' environment variable. "
          + "The JSON setting has no effect and the MCP stdio transport will NOT work correctly.");
    }

    if ((globalSettings == null) || !GlobalSettings.getReleaseSafeVersion().equals(globalSettings.version)) {
      // let's see if we can preserve the user ID
      String userId = globalSettings == null ? UUID.randomUUID().toString() : globalSettings.userProfileSettings.userId;

      globalSettings = new GlobalSettings();
      globalSettings.userProfileSettings.userId = userId;
      globalSettings.version = GlobalSettings.getReleaseSafeVersion();
      // Stamp the correct log path into the new settings object too.
      globalSettings.logging.file.location = fileLoggingLocation;

      // save the default values
      try {
        GlobalSettings.saveAsJson(globalSettings);
        FRLogger.debug("Default settings saved to '" + GlobalSettings.getConfigurationFilePath() + "'.");
      } catch (AccessDeniedException e) {
        FRLogger.warn("Cannot write freerouting.json to '"
            + GlobalSettings.getConfigurationFilePath() + "': " + e.getReason()
            + ". The directory and/or file may have incorrect permissions. "
            + "Check that the process has write access. "
            + "In Docker deployments, verify the volume mount configuration. "
            + "Settings won't be persisted across restarts.");
      } catch (IOException e) {
        FRLogger.warn("Failed to save freerouting.json to '"
            + GlobalSettings.getConfigurationFilePath() + "': " + e.getMessage()
            + ". Settings won't be persisted across restarts.");
      }
    }

    // apply environment variables to the settings
    globalSettings.applyNonRouterEnvironmentVariables();

    // Note: Logging is already configured via system properties set earlier
    // No need to call ApplyLoggingSettings() - it would cause runtime manipulation
    // errors

    // if we don't have a GUI enabled then we must use the console as our output
    if ((!globalSettings.guiSettings.isEnabled) && (System.console() == null)) {
      FRLogger.warn(
          "GUI is disabled and you don't have a console available, so the only feedback from Freerouting is in the log.");
    }

    // get environment parameters and save them in the settings
    globalSettings.runtimeEnvironment.freeroutingVersion = Constants.FREEROUTING_VERSION + ","
        + Constants.FREEROUTING_BUILD_DATE;
    globalSettings.runtimeEnvironment.appStartedAt = Instant.now();
    globalSettings.runtimeEnvironment.commandLineArguments = String.join(" ", args);
    globalSettings.runtimeEnvironment.architecture = System.getProperty("os.name") + ","
        + System.getProperty("os.arch") + "," + System.getProperty("os.version");
    globalSettings.runtimeEnvironment.java = System.getProperty("java.version") + ","
        + System.getProperty("java.vendor");
    globalSettings.runtimeEnvironment.systemLanguage = Locale
        .getDefault()
        .getLanguage() + "," + Locale.getDefault();
    globalSettings.runtimeEnvironment.cpuCores = Runtime
        .getRuntime()
        .availableProcessors();
    globalSettings.runtimeEnvironment.ram = (int) (Runtime
        .getRuntime()
        .maxMemory() / 1024 / 1024);
    FRLogger.debug("Version: " + globalSettings.runtimeEnvironment.freeroutingVersion);
    FRLogger.debug("Command line arguments: '" + globalSettings.runtimeEnvironment.commandLineArguments + "'");
    FRLogger.debug("Architecture: " + globalSettings.runtimeEnvironment.architecture);
    FRLogger.debug("Java: " + globalSettings.runtimeEnvironment.java);
    FRLogger.debug("System Language: " + globalSettings.runtimeEnvironment.systemLanguage);
    FRLogger.debug("Hardware: " + globalSettings.runtimeEnvironment.cpuCores + " CPU cores,"
        + globalSettings.runtimeEnvironment.ram + " MB RAM");
    FRLogger.debug("UTC Time: " + globalSettings.runtimeEnvironment.appStartedAt);

    // parse the command line arguments (for the non-router settings)
    globalSettings.applyCommandLineArguments(args);

    if (globalSettings.compareFile1 != null && globalSettings.compareFile2 != null) {
      boolean success = compareBoardFiles(globalSettings.compareFile1, globalSettings.compareFile2);
      System.exit(success ? 0 : 1);
    }

    FRLogger.debug("GUI Language: " + globalSettings.currentLocale);

    FRLogger.debug("Host: " + globalSettings.runtimeEnvironment.host);

    // Get some useful information if we are running in a GUI
    int width = 0;
    int height = 0;
    int dpi = 0;
    if (globalSettings.guiSettings.isEnabled) {
      try {
        // Get default screen device
        Toolkit toolkit = Toolkit.getDefaultToolkit();

        // Get screen resolution
        Dimension screenSize = toolkit.getScreenSize();
        width = screenSize.width;
        height = screenSize.height;

        // Get screen DPI
        dpi = toolkit.getScreenResolution();
        FRLogger.debug("Screen: " + width + "x" + height + ", " + dpi + " DPI");
      } catch (Exception _) {
        FRLogger.warn(
            "Couldn't get screen resolution. If you are running in a headless environment, disable the GUI by setting gui.enabled to false.");
        globalSettings.guiSettings.isEnabled = false;
      }
    }

    boolean allowAnalytics = false;

    // initialize analytics
    FRAnalytics.setAccessKey(Constants.FREEROUTING_VERSION, globalSettings.usageAndDiagnosticData.loggerKey);

    // this option allows us to disable analytics for some users (enabled for all if
    // it is set to 1, otherwise it is disabled for every Nth user)
    int analyticsModulo = 1;
    String userIdString = globalSettings.userProfileSettings.userId.length() >= 4
        ? globalSettings.userProfileSettings.userId.substring(0, 4)
        : "0000";
    int userIdValue = Integer.parseInt(userIdString, 16);

    // if the user has disabled analytics, we don't need to check the modulo
    allowAnalytics = !globalSettings.usageAndDiagnosticData.disableAnalytics && (globalSettings.userProfileSettings.isTelemetryAllowed);

    if (!allowAnalytics) {
      FRLogger.debug("Analytics are disabled");
    }
    FRAnalytics.setEnabled(allowAnalytics);
    FRAnalytics.setUserId(globalSettings.userProfileSettings.userId, globalSettings.userProfileSettings.userEmail);
    FRAnalytics.identify();
    if (!globalSettings.userProfileSettings.userEmail.isBlank()) {
      FRAnalytics.refreshIdentity();
    }
    try {
      Thread.sleep(1000);
    } catch (Exception _) {
    }
    FRAnalytics.setAppLocation("app.freerouting.gui", "Freerouting");
    FRAnalytics.appStarted(Constants.FREEROUTING_VERSION, Constants.FREEROUTING_BUILD_DATE + " 00:00",
        String.join(" ", args), System.getProperty("os.name"), System.getProperty("os.arch"),
        System.getProperty("os.version"), System.getProperty("java.version"), System.getProperty("java.vendor"),
        Locale.getDefault(), globalSettings.currentLocale,
        globalSettings.runtimeEnvironment.cpuCores, globalSettings.runtimeEnvironment.ram,
        globalSettings.runtimeEnvironment.host, width, height, dpi);

    // The upstream version check is NOT started. Its endpoint announces upstream
    // releases, so on this fork it produces a false "new version available" prompt --
    // and an unexpected network call from fab-adjacent tooling. Re-enable only against
    // an endpoint that speaks for THIS distribution.

    // Check if the user requested help
    if (globalSettings.show_helpful_option) {
      IO.print(HELPFUL_TEXT);
      System.exit(0);
    }

    if (globalSettings.show_help_option) {
      TextManager ctm = new TextManager(Freerouting.class, globalSettings.currentLocale);
      IO.print(ctm.getText("command_line_help"));
      IO.print(OPTIMIZER_HELP_TEXT);
      IO.print("\n  --helpful    How to actually get a good board: what the stages do, how"
          + " long to give it,\n               and how to read what it tells you at the"
          + " end.\n");
      System.exit(0);
    }

    // Help never mutates state; every path past this line is a real run.
    app.freerouting.settings.GlobalSettings.flushPendingMigrationSave();

    // Disable GUI and API if in DRC-only mode
    if (globalSettings.drc_report_file != null) {
      globalSettings.guiSettings.isEnabled = false;
      globalSettings.apiServerSettings.isEnabled = false;
      globalSettings.mcpServerSettings.isEnabled = false;
    }

    // Create the settings merger prototype based on the sources that will not change at runtime
    globalSettings.settingsMergerProtype = new SettingsMerger(
        new DefaultSettings(),
        new JsonFileSettings(),
        new CliSettings(args),
        new EnvironmentVariablesSource());

    // Initialize the API server
    if (globalSettings.apiServerSettings.isEnabled) {
      apiServer = InitializeAPI(globalSettings.apiServerSettings);
      globalSettings.apiServerSettings.isEnabled = apiServer != null;
      globalSettings.apiServerSettings.isRunning = apiServer != null;

      if (apiServer != null
          && (globalSettings.mcpServerSettings.targetApiBaseUrl == null
          || globalSettings.mcpServerSettings.targetApiBaseUrl.isBlank()
          || "http://127.0.0.1:37864".equals(globalSettings.mcpServerSettings.targetApiBaseUrl))) {
        if (apiServer.getConnectors().length > 0 && apiServer.getConnectors()[0] instanceof ServerConnector connector) {
          int port = connector.getLocalPort();
          if (port <= 0) {
            port = connector.getPort();
          }
          globalSettings.mcpServerSettings.targetApiBaseUrl = "http://127.0.0.1:" + port;
        }
      }
    }

    if (globalSettings.mcpServerSettings.isEnabled) {
      mcpServer = InitializeMCP(globalSettings.mcpServerSettings);
      globalSettings.mcpServerSettings.isEnabled = mcpServer != null;
      globalSettings.mcpServerSettings.isRunning = mcpServer != null;

      if (mcpServer != null && Boolean.TRUE.equals(globalSettings.mcpServerSettings.isStdioMode)) {
        startMcpStdioBridge(originalSystemOut, mcpServer);
      }
    }

    // Initialize the GUI
    if (globalSettings.guiSettings.isEnabled) {
      if (!GuiManager.InitializeGUI(globalSettings)) {
        FRLogger.error("Couldn't initialize the GUI", null);
        globalSettings.guiSettings.isEnabled = false;
      } else {
        globalSettings.guiSettings.isRunning = true;
      }
    }

    // If the GUI is disabled and the API server is not running, then we are in CLI mode
    CliOutcome cliOutcome = CliOutcome.COMPLETE;
    if (!globalSettings.guiSettings.isEnabled
        && !globalSettings.apiServerSettings.isRunning
        && !globalSettings.mcpServerSettings.isRunning) {
      if (globalSettings.drc_report_file != null) {
        cliOutcome = InitializeDRC(globalSettings) ? CliOutcome.COMPLETE : CliOutcome.FAILED;
      } else {
        cliOutcome = InitializeCLI(globalSettings);
      }
    }

    // Legacy policy is bit-for-bit what it was: 0 for any run that produced a result,
    // 1 for one that did not. With --outcome_exit_codes=true the four outcomes become
    // distinguishable, which is what closes FS-X.
    if (!globalSettings.apiServerSettings.isEnabled
        && !globalSettings.mcpServerSettings.isEnabled) {
      int outcomeExit = cliOutcome.exitCode(Boolean.TRUE.equals(globalSettings.outcomeExitCodes));
      if (outcomeExit != 0) {
        FRLogger.info("Exiting with status " + outcomeExit + " (" + cliOutcome + ").");
        ShutdownApplication();
        FRLogger.traceExit("MainApplication.main()");
        System.exit(outcomeExit);
      }
    }

    while (globalSettings.guiSettings.isRunning
        || globalSettings.apiServerSettings.isRunning
        || globalSettings.mcpServerSettings.isRunning) {
      try {
        Thread.sleep(500);
      } catch (InterruptedException _) {
        break;
      }
    }

    ShutdownApplication();

    FRLogger.traceExit("MainApplication.main()");
    System.exit(0);
  }

  private static boolean compareBoardFiles(String file1Path, String file2Path) {
    FRLogger.info("Starting comparison of board files: " + file1Path + " and " + file2Path);
    try {
      java.io.File file1 = new java.io.File(file1Path);
      java.io.File file2 = new java.io.File(file2Path);
      if (!file1.exists()) {
        FRLogger.error("Comparison file 1 does not exist: " + file1Path, null);
        return false;
      }
      if (!file2.exists()) {
        FRLogger.error("Comparison file 2 does not exist: " + file2Path, null);
        return false;
      }

      app.freerouting.board.RoutingBoard board1 = loadBoardFromFile(file1);
      app.freerouting.board.RoutingBoard board2 = loadBoardFromFile(file2);

      if (board1 == null || board2 == null) {
        FRLogger.error("Failed to load one or both boards for comparison.", null);
        return false;
      }

      app.freerouting.board.BoardComparator.ComparisonResult result =
          app.freerouting.board.BoardComparator.compare(board1, board2, 1e-3);

      System.out.println(result.report);

      if (result.areEqual) {
        FRLogger.info("SUCCESS: Boards are identical.");
      } else {
        FRLogger.warn("WARNING: Differences detected between the loaded boards.");
      }
      return result.areEqual;
    } catch (Exception e) {
      FRLogger.error("Error during board files comparison: " + e.getMessage(), e);
      return false;
    }
  }

  private static app.freerouting.board.RoutingBoard loadBoardFromFile(java.io.File file) throws Exception {
    try (java.io.InputStream is = new java.io.FileInputStream(file)) {
      if (file.getName().toLowerCase().endsWith(".json")) {
        try (java.io.Reader r = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
          app.freerouting.io.BoardReadResult readResult = app.freerouting.io.kicad.KiCadJsonReader.readBoard(r, null, null);
          if (readResult instanceof app.freerouting.io.BoardReadResult.Success success) {
            return (app.freerouting.board.RoutingBoard) success.board();
          } else if (readResult instanceof app.freerouting.io.BoardReadResult.OutlineMissing outlineMissing) {
            return (app.freerouting.board.RoutingBoard) outlineMissing.board();
          }
        }
      } else {
        app.freerouting.io.BoardReadResult readResult = app.freerouting.io.specctra.DsnReader.readBoard(is, null, null, file.getName());
        if (readResult instanceof app.freerouting.io.BoardReadResult.Success success) {
          return (app.freerouting.board.RoutingBoard) success.board();
        } else if (readResult instanceof app.freerouting.io.BoardReadResult.OutlineMissing outlineMissing) {
          return (app.freerouting.board.RoutingBoard) outlineMissing.board();
        }
      }
    }
    return null;
  }

}