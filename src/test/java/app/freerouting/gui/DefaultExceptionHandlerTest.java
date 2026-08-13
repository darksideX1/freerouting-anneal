package app.freerouting.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/**
 * The uncaught-exception handler is the last line of the exit path: if IT throws, the JVM
 * prints "Exception ... thrown from the UncaughtExceptionHandler" and the process exit is
 * eaten. Measured in the field: NoClassDefFoundError from this handler at a job-timeout
 * shutdown, when the logging subsystem was already stopped and lazy loading failed.
 */
class DefaultExceptionHandlerTest {

  /** A throwable whose own accessors fail, as they do when shutdown breaks class loading. */
  private static final class HostileThrowable extends RuntimeException {
    @Override
    public String getLocalizedMessage() {
      throw new NoClassDefFoundError("simulated: class loading failed during shutdown");
    }
  }

  @Test
  void handlerNeverPropagates_evenWhenTheThrowableItselfIsHostile() {
    assertDoesNotThrow(() -> DefaultExceptionHandler.handleException(new HostileThrowable()));
  }

  @Test
  void uncaughtExceptionNeverPropagates() {
    DefaultExceptionHandler handler = new DefaultExceptionHandler();
    assertDoesNotThrow(() -> handler.uncaughtException(Thread.currentThread(), new HostileThrowable()));
  }
}
