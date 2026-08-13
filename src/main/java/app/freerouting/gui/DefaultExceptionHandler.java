package app.freerouting.gui;

import static javax.swing.JOptionPane.OK_OPTION;

import app.freerouting.Freerouting;
import app.freerouting.logger.FRLogger;
import app.freerouting.management.analytics.FRAnalytics;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

public class DefaultExceptionHandler implements Thread.UncaughtExceptionHandler {

  public static void handleException(Throwable e) {
    // The handler itself must never throw. During shutdown the logging subsystem may
    // already be stopped and lazy class loading can fail (measured: NoClassDefFoundError
    // thrown FROM this handler at a job-timeout exit) -- and an exception escaping an
    // UncaughtExceptionHandler is printed by the JVM and eats the process exit path.
    // Everything below is best-effort; the fallback uses only java.lang.
    try {
      handleExceptionInternal(e);
    } catch (Throwable handlerFailure) {
      // Class names first -- they come from java.lang and cannot fail -- then a
      // best-effort stack print, itself guarded: printStackTrace() goes through
      // toString()/getLocalizedMessage(), which is exactly what just broke.
      try {
        System.err.println("freerouting: error during error handling ("
            + handlerFailure.getClass().getName() + "); original error was "
            + e.getClass().getName() + ".");
        e.printStackTrace(System.err);
      } catch (Throwable ignored) {
        // The class names above are already out; nothing more without risking the exit.
      }
    }
  }

  private static void handleExceptionInternal(Throwable e) {
    // Every exception gets a short reference. The LOG gets the reference and the stack
    // trace; the USER gets the reference and nothing else. A user cannot act on
    // "NullPointerException: curr_object is null" -- they can act on "error FR-1A2B3C,
    // check the log", and support can find it instantly. Showing the raw exception and
    // then continuing, which is what this did, taught users that errors are dismissible.
    String reference = newErrorReference();
    FRLogger.error("[" + reference + "] " + e.getLocalizedMessage(), e);
    FRAnalytics.exceptionThrown(e.getLocalizedMessage(), e);
    if (shouldShowDialog()) {
      String message = "The operation was stopped by an unexpected error."
          + System.lineSeparator() + System.lineSeparator()
          + "Error reference: " + reference
          + System.lineSeparator() + System.lineSeparator()
          + "Details have been written to the log. Please quote this reference"
          + System.lineSeparator()
          + "when reporting the problem.";
      JOptionPane.showMessageDialog(findActiveFrame(), message, "Error " + reference, OK_OPTION);
    }
  }

  /**
   * Identifies this run in an error reference, so two processes writing to the same log
   * directory do not produce references that look alike.
   */
  private static final String PROCESS_TAG = String.format("%08X",
      (int) ((ProcessHandle.current().pid() << 20) ^ System.nanoTime()));

  /** Guarantees that no two references from this process are ever equal. */
  private static final java.util.concurrent.atomic.AtomicLong ERROR_SEQUENCE =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * Short handle tying what the user was told to what the log recorded.
   *
   * <p>A reference is only useful if it identifies exactly ONE error. The first version
   * masked {@code nanoTime()} to 24 bits, which cycles about every 16.8 ms and has nothing
   * to separate two calls landing on the same tick -- so a repeatedly failing GUI could
   * print one reference for unrelated exceptions. That is the exact situation the
   * reference exists for, and two failures close together in time is exactly when it broke.
   *
   * <p>A monotonic counter cannot repeat within a process, which is the guarantee that
   * matters: one process, one log, one reference per error. Kept short and upper-case
   * because a human has to read it aloud and type it into a ticket.
   */
  static String newErrorReference() {
    return "FR-" + PROCESS_TAG + "-" + ERROR_SEQUENCE.incrementAndGet();
  }

  private static boolean shouldShowDialog() {
    if (GraphicsEnvironment.isHeadless()) {
      return false;
    }
    if (Freerouting.globalSettings != null
        && Boolean.FALSE.equals(Freerouting.globalSettings.guiSettings.isEnabled)) {
      return false;
    }
    return true;
  }

  private static Frame findActiveFrame() {
    Frame[] frames = JFrame.getFrames();
    for (Frame frame : frames) {
      if (frame.isVisible()) {
        return frame;
      }
    }
    return null;
  }

  @Override
  public void uncaughtException(Thread t, Throwable e) {
    handleException(e);
  }
}