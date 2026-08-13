package app.freerouting.management;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.logger.FRLogger;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;

/**
 * Stops running jobs the way the job deadline does, when something outside asks the process to
 * go away.
 *
 * <p>The job's own timeout was already graceful: it calls {@code requestStop()} and waits for
 * the stage to finish its current unit of work and hand back a whole board. Nothing else was.
 * A {@code SIGTERM} sixty seconds into a run — auto-routing complete, optimiser working — took
 * the JVM down and wrote no {@code .ses} at all (defect 28). Ctrl-C, a CI or supervisor
 * timeout, a container being reclaimed, the machine suspending or shutting down: every one of
 * them threw the work away.
 *
 * <p>That is the wrong way round. The stop the program schedules for itself behaved well, and
 * the stops a user actually initiates — the ones where somebody is waiting for the answer —
 * lost everything.
 *
 * <p>A partial board is a result. A run that has routed the board and is polishing it has
 * produced something worth keeping, even if it is interrupted a minute in.
 *
 * <p>The wait is bounded because a shutdown hook holds up whatever asked the process to stop.
 * An operating system going down will not wait forever, and a job that refuses to acknowledge
 * must not be able to hold the machine open.
 */
public final class GracefulShutdown {

  /**
   * How long to let jobs finish saving.
   *
   * <p>Ten seconds, chosen for what saving a board actually needs rather than for what an
   * operating system is willing to wait. We ask for the time and we use it.
   *
   * <p>Windows may not grant it. Closing a console window raises CTRL_CLOSE_EVENT, and the
   * system terminates the process when the handler returns or when its own time-out elapses
   * -- five seconds by long-standing default
   * (https://learn.microsoft.com/en-us/windows/console/ctrl-close-signal). If that fires
   * first, the save is cut short and the board is lost or partial.
   *
   * <p>That is a deliberate line, not an oversight. Shrinking our budget to fit the shortest
   * OS timeout would mean giving up on saves that would otherwise have succeeded, on every
   * platform, to protect one case that the user created: closing the window while a route
   * is visibly still running. The application offers Stop, which ends the run cleanly and
   * writes the board; closing the window instead is choosing not to use it.
   *
   * <p>So: we try, we say we are trying, and if the OS insists we lose that run's output.
   * Documented rather than engineered around, because the alternative degrades the common
   * case to rescue the one where the user overrode the tool that already solved it.
   */
  public static final long DEFAULT_GRACE_MS = 10_000L;

  private static volatile boolean installed = false;

  /**
   * Jobs that should be stopped politely on the way out.
   *
   * <p>Kept here rather than read from the scheduler because only the GUI enqueues jobs there.
   * The headless CLI builds its RoutingJob directly, so a hook that consulted the scheduler saw
   * an empty list and did nothing at all -- which is the exact case defect 28 was reported for.
   */
  private static final java.util.Set<RoutingJob> tracked =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** How to persist a job's result. Supplied by whoever owns the output path. */
  private static final java.util.Map<RoutingJob, Runnable> saveActions =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** Registers a job so an external stop can ask it to save rather than killing it. */
  public static void register(RoutingJob p_job) {
    register(p_job, null);
  }

  /**
   * Registers a job together with how to write its result.
   *
   * <p>Asking the job to stop is only half of it. The CLI writes its output in the main flow
   * AFTER the job reaches a final state, and a shutdown preempts that flow -- so a run that
   * stopped politely still produced no file at all. Whoever knows where the output goes hands
   * that knowledge over here, because at shutdown this is the only code still running.
   */
  public static void register(RoutingJob p_job, Runnable p_saveAction) {
    if (p_job == null) {
      return;
    }
    tracked.add(p_job);
    if (p_saveAction != null) {
      saveActions.put(p_job, p_saveAction);
    }
  }

  private GracefulShutdown() {
  }

  /** Installs the hook once. Safe to call repeatedly; later calls do nothing. */
  public static synchronized void install() {
    if (installed) {
      return;
    }
    installed = true;
    Runtime.getRuntime().addShutdownHook(new Thread(
        () -> {
          java.util.List<RoutingJob> all = new java.util.ArrayList<>(tracked);
          all.addAll(RoutingJobScheduler.getInstance().jobs);
          stopJobs(all, DEFAULT_GRACE_MS);
        },
        "freerouting-graceful-shutdown"));
  }

  /**
   * Asks every running job to stop and waits, up to p_graceMs in total, for them to finish.
   *
   * @param p_jobs   the jobs to consider; those not running are left alone.
   * @param p_graceMs total time to wait for all of them, not per job.
   */
  public static void stopJobs(Collection<RoutingJob> p_jobs, long p_graceMs) {
    if (p_jobs == null || p_jobs.isEmpty()) {
      return;
    }

    // Copied first: the scheduler mutates this list from its own thread, and iterating it
    // while it changes during shutdown would be a poor way to discover that.
    //
    // Deduplicated by IDENTITY, because a CLI job reaches us by two routes at once:
    // InitializeCLI registers it here, and cliSession.addJob puts the same instance into
    // RoutingJobScheduler.jobs, which the hook concatenates. Saving it twice is not a
    // harmless repeat -- the save action truncates its destination before writing, so the
    // redundant second save opens a window in which an OS that kills us mid-write replaces
    // an already-complete board with a partial one. That is precisely the loss this class
    // exists to prevent, so the guard lives here rather than at the one call site that
    // happens to need it today.
    //
    // Identity rather than equality: the question is whether this is the same object
    // registered twice, which is not a question about a job's fields.
    Set<RoutingJob> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    List<RoutingJob> running = List.copyOf(p_jobs).stream()
        .filter(job -> job != null && job.state == RoutingJobState.RUNNING && job.thread != null)
        .filter(seen::add)
        .toList();

    if (running.isEmpty()) {
      return;
    }

    FRLogger.info("Stopping " + running.size() + " running job(s) before exit; "
        + "each will finish its current pass and save the board it has.");

    for (RoutingJob job : running) {
      try {
        job.thread.requestStop();
      } catch (Exception e) {
        // One job refusing to be asked must not stop us asking the others.
        FRLogger.warn("Could not request stop on job " + job.id + ": " + e.getMessage());
      }
    }

    Instant deadline = Instant.now().plusMillis(p_graceMs);
    for (RoutingJob job : running) {
      while (job.state == RoutingJobState.RUNNING && Instant.now().isBefore(deadline)) {
        try {
          Thread.sleep(25);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

    // Save what actually stopped. Only jobs that reached a final state: one still mid-pass
    // has no consistent board, and writing it would produce a file that looks finished and is
    // not -- worse than writing nothing, because nobody would know to distrust it.
    for (RoutingJob job : running) {
      if (job.state == RoutingJobState.RUNNING) {
        continue;
      }
      Runnable save = saveActions.get(job);
      if (save == null) {
        continue;
      }
      try {
        save.run();
        FRLogger.info("Saved the board from job " + job.id + " before exiting.");
      } catch (Exception e) {
        FRLogger.error("Could not save the board from job " + job.id
            + " while shutting down; the work is lost.", e);
      }
    }

    long stillRunning = running.stream()
        .filter(job -> job.state == RoutingJobState.RUNNING)
        .count();
    if (stillRunning > 0) {
      FRLogger.warn(stillRunning + " job(s) did not stop within " + p_graceMs
          + " ms and their work is being abandoned. The board they had is lost.");
    }
  }
}
