package app.freerouting.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.StoppableThread;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Defect 28: a stop from outside must save the board, not discard it.
 *
 * <p>Sending SIGTERM to a run sixty seconds in — auto-routing finished, optimiser working —
 * exited with no .ses written at all. The job's own timeout is graceful: it calls
 * {@code requestStop()} and waits for the stage to hand back a whole board. Every stop a user
 * can actually initiate — Ctrl-C, a CI or supervisor timeout, container shutdown, the machine
 * suspending — took the JVM down with the work still in memory.
 *
 * <p>That is backwards. The one stop that behaved well was the one the program scheduled for
 * itself; the ones where somebody is waiting for an answer lost everything.
 *
 * <p>These tests pin the behaviour of the hook body directly rather than by sending a real
 * signal: a test that kills its own JVM cannot then assert anything.
 */
class GracefulShutdownTest {

  /** A thread that reports RUNNING until asked to stop, like a routing job in flight. */
  private static final class FakeJobThread extends StoppableThread {
    volatile boolean sawStop = false;

    @Override
    protected void thread_action() {
      // nothing: the test drives state by hand
    }

    @Override
    public synchronized void requestStop() {
      super.requestStop();
      sawStop = true;
    }
  }

  @Test
  @DisplayName("a running job is asked to stop, not abandoned")
  void requestsStopOnRunningJobs() {
    RoutingJob job = new RoutingJob();
    FakeJobThread thread = new FakeJobThread();
    job.thread = thread;
    job.state = RoutingJobState.RUNNING;

    GracefulShutdown.stopJobs(List.of(job), 200);

    assertTrue(thread.sawStop, "a running job must be asked to stop so it can save its board");
  }

  @Test
  @DisplayName("shutdown waits for the job to finish saving, within a bound")
  void waitsForTheJobToLeaveRunning() {
    RoutingJob job = new RoutingJob();
    FakeJobThread thread = new FakeJobThread();
    job.thread = thread;
    job.state = RoutingJobState.RUNNING;

    // The job acknowledges shortly after being asked, the way a stage finishing a pass does.
    new Thread(() -> {
      try {
        Thread.sleep(50);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      job.state = RoutingJobState.COMPLETED;
    }).start();

    long start = System.currentTimeMillis();
    GracefulShutdown.stopJobs(List.of(job), 2_000);
    long waited = System.currentTimeMillis() - start;

    assertTrue(waited >= 50, "shutdown must actually wait for the board to be written");
    assertTrue(waited < 1_500, "shutdown must not wait for the full bound once the job is done");
  }

  @Test
  @DisplayName("a job that will not stop cannot hang the shutdown forever")
  void boundedWhenTheJobNeverAcknowledges() {
    RoutingJob job = new RoutingJob();
    job.thread = new FakeJobThread();
    job.state = RoutingJobState.RUNNING; // never changes

    long start = System.currentTimeMillis();
    GracefulShutdown.stopJobs(List.of(job), 300);
    long waited = System.currentTimeMillis() - start;

    assertTrue(waited >= 300, "the bound is a floor for a job that never acknowledges");
    assertTrue(waited < 3_000,
        "a stuck job must not hold the machine's shutdown open indefinitely; waited " + waited);
  }

  @Test
  @DisplayName("the board is saved after the job stops, not left in memory")
  void savesTheBoardAfterStopping() {
    RoutingJob job = new RoutingJob();
    FakeJobThread thread = new FakeJobThread();
    job.thread = thread;
    job.state = RoutingJobState.RUNNING;

    java.util.concurrent.atomic.AtomicBoolean saved =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    GracefulShutdown.register(job, () -> saved.set(true));

    // The job acknowledges the stop the way a stage finishing its pass does.
    new Thread(() -> {
      try {
        Thread.sleep(30);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      job.state = RoutingJobState.TIMED_OUT;
    }).start();

    GracefulShutdown.stopJobs(List.of(job), 2_000);

    assertTrue(saved.get(),
        "stopping a job is not enough -- the board it produced has to be written, or the run"
            + " had nothing to show for itself");
  }

  @Test
  @DisplayName("a job that never stops does not get its half-written board saved")
  void doesNotSaveWhenTheJobNeverStopped() {
    RoutingJob job = new RoutingJob();
    job.thread = new FakeJobThread();
    job.state = RoutingJobState.RUNNING; // never acknowledges

    java.util.concurrent.atomic.AtomicBoolean saved =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    GracefulShutdown.register(job, () -> saved.set(true));

    GracefulShutdown.stopJobs(List.of(job), 200);

    assertFalse(saved.get(),
        "a job still mid-pass has no consistent board to write; saving it would produce a"
            + " file that looks finished and is not");
  }

  @Test
  @DisplayName("jobs that are not running are left alone")
  void ignoresJobsThatAreNotRunning() {
    RoutingJob job = new RoutingJob();
    FakeJobThread thread = new FakeJobThread();
    job.thread = thread;
    job.state = RoutingJobState.COMPLETED;

    GracefulShutdown.stopJobs(List.of(job), 200);

    assertFalse(thread.sawStop, "a finished job has nothing to stop");
  }

  @Test
  @Timeout(10)
  @DisplayName("a job reachable by two routes is saved once, not twice")
  void savesEachJobExactlyOnce() {
    RoutingJob job = new RoutingJob();
    FakeJobThread thread = new FakeJobThread();
    job.thread = thread;
    job.state = RoutingJobState.RUNNING;

    AtomicInteger saves = new AtomicInteger();
    GracefulShutdown.register(job, saves::incrementAndGet);

    // Acknowledges shortly after being asked, the way a stage finishing its pass does.
    new Thread(() -> {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      job.state = RoutingJobState.COMPLETED;
    }).start();

    // A CLI run reaches the hook by two routes: InitializeCLI registers the job here, and
    // cliSession.addJob puts the same instance into RoutingJobScheduler.jobs. The hook
    // concatenates the two, so the job arrives twice.
    GracefulShutdown.stopJobs(List.of(job, job), 1_000);

    assertEquals(1, saves.get(),
        "the save action must run once per job. Files.write truncates the destination before "
            + "writing, so a redundant second save is a window in which an OS that kills us "
            + "mid-write replaces an already-complete board with a partial one");
  }
}
