package app.freerouting.logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

/**
 * The root level must reflect what is actually wanted, or every level guard is a no-op.
 *
 * <p>This configuration filters per appender-ref and built the root logger at
 * {@code Level.ALL}. {@code FRLogger.isTraceEnabled()} asks the LOGGER, not the appenders
 * — so it returned {@code true} unconditionally, at every log level, forever.
 *
 * <p>The consequence is not cosmetic. Roughly 198 call sites were guarded with
 * {@code isTraceEnabled()} / {@code isDebugEnabled()} precisely because
 * {@code FRLogger.trace(...)} takes an already-built String, so the concatenation runs
 * before the level is ever checked. With the guard permanently true, all of that hot-path
 * string building ran on every call and none of it was ever emitted. Measured on bm01:
 * {@code byte[]} was the single largest allocator at 17.5%, with {@code String} a further
 * 3.1% — the maze search's {@code describe_*} helpers building diagnostics for nobody.
 *
 * <p>The rule: root must be as verbose as the most verbose ENABLED destination, and no
 * more. More verbose than that and the guards lie; less and a configured appender is
 * starved, because the logger filters before the appender ever sees the event.
 */
class RootLevelTest {

  @Test
  void theDefaultShapeDoesNotEnableTrace() {
    // Console INFO + file DEBUG: nothing wants TRACE, so isTraceEnabled() must be false
    // and the maze search must stop building strings nobody reads.
    assertEquals(Level.DEBUG, Log4j2ConfigurationFactory.rootLevelFor(true, "INFO", true, "DEBUG"));
  }

  @Test
  void theMostVerboseEnabledDestinationWins() {
    // Starving a configured appender is the opposite failure and equally real: the logger
    // filters first, so a TRACE file behind an INFO root receives nothing at all.
    assertEquals(Level.TRACE, Log4j2ConfigurationFactory.rootLevelFor(true, "INFO", true, "TRACE"));
    assertEquals(Level.TRACE, Log4j2ConfigurationFactory.rootLevelFor(true, "TRACE", true, "INFO"));
  }

  @Test
  void aDisabledDestinationDoesNotRaiseTheRoot() {
    // File logging off must not keep TRACE alive on the strength of a level nobody uses.
    assertEquals(Level.INFO, Log4j2ConfigurationFactory.rootLevelFor(true, "INFO", false, "TRACE"));
    assertEquals(Level.WARN, Log4j2ConfigurationFactory.rootLevelFor(false, "TRACE", true, "WARN"));
  }

  @Test
  void consoleOnlyAtWarnKeepsDebugAndTraceOff() {
    assertEquals(Level.WARN, Log4j2ConfigurationFactory.rootLevelFor(true, "WARN", false, "DEBUG"));
  }

  @Test
  void withEverythingOffTheRootStillPassesErrors() {
    // stderr is attached unconditionally for ERROR, so the root must not fall below it or
    // a failing run would go completely silent.
    assertEquals(Level.ERROR, Log4j2ConfigurationFactory.rootLevelFor(false, "INFO", false, "DEBUG"));
  }

  @Test
  void anUnparseableLevelDoesNotSilenceTheProgram() {
    // A typo in a level must not be able to turn logging off; fall back to INFO.
    assertEquals(Level.INFO, Log4j2ConfigurationFactory.rootLevelFor(true, "nonsense", false, "INFO"));
  }
}
