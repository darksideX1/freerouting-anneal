package app.freerouting.logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The debug file must honour the level the user configured.
 *
 * <p>The DEBUG/TRACE split gave those levels their own file so that normal events stay
 * readable. The appender reference for that file was then wired at a fixed {@code TRACE},
 * on the reasoning that the appender's own {@code LevelRangeFilter} would decide what
 * lands there. That reasoning confuses two different questions: the range filter decides
 * WHICH destination an event goes to, while the reference level decides WHETHER the event
 * is delivered at all.
 *
 * <p>The consequence was that every ordinary run wrote full TRACE routing output — the
 * highest-volume thing this program emits — regardless of the configured level. Combined
 * with the 20 MB / 4-file ring added in the same change, that is actively harmful: the
 * ring fills with trace nobody asked for and rotates away the diagnostics the ring exists
 * to keep. A rotation policy is only as good as what it is rotating.
 */
class DebugFileLevelTest {

    private Log4j2ConfigurationFactory factory;
    private String logFile;

    @BeforeEach
    void setUp() {
        factory = new Log4j2ConfigurationFactory();
        clearSystemProperties();
        logFile = System.getProperty("java.io.tmpdir")
                + "/freerouting_debuglevel_" + System.nanoTime() + ".log";
    }

    @AfterEach
    void tearDown() {
        clearSystemProperties();
    }

    private void clearSystemProperties() {
        System.clearProperty("freerouting.logging.file.enabled");
        System.clearProperty("freerouting.logging.file.level");
        System.clearProperty("freerouting.logging.file.location");
    }

    private AppenderRef refNamed(Configuration config, String name) {
        return config
                .getRootLogger()
                .getAppenderRefs()
                .stream()
                .filter(ref -> name.equals(ref.getRef()))
                .findFirst()
                .orElse(null);
    }

    private Configuration configureWithFileLevel(String level) {
        System.setProperty("freerouting.logging.file.enabled", "true");
        System.setProperty("freerouting.logging.file.location", logFile);
        if (level != null) {
            System.setProperty("freerouting.logging.file.level", level);
        }
        return factory.getConfiguration(null, "TestConfig", URI.create("test"));
    }

    @Test
    void defaultLevel_doesNotPourTraceIntoTheDebugFile() {
        // The default file level is DEBUG. A user who did not ask for TRACE must not get
        // it: this is the case that was live in every ordinary run.
        Configuration config = configureWithFileLevel(null);

        AppenderRef debugRef = refNamed(config, "DebugFile");
        assertNotNull(debugRef, "the debug file appender should be attached when file logging is on");
        assertEquals(Level.DEBUG, debugRef.getLevel());
    }

    @Test
    void restrictedLevel_isHonouredByTheDebugFile() {
        // Someone who restricts file logging to INFO has asked for no debug output at all.
        // The range filter then denies everything, which is the correct outcome: an empty
        // debug file, not a full one.
        Configuration config = configureWithFileLevel("INFO");

        AppenderRef debugRef = refNamed(config, "DebugFile");
        assertNotNull(debugRef);
        assertEquals(Level.INFO, debugRef.getLevel());
    }

    @Test
    void traceIsAvailableWhenItIsActuallyRequested() {
        // The capability is not removed -- it is put behind the request it belongs to.
        Configuration config = configureWithFileLevel("TRACE");

        AppenderRef debugRef = refNamed(config, "DebugFile");
        assertNotNull(debugRef);
        assertEquals(Level.TRACE, debugRef.getLevel());
    }

    @Test
    void normalFileKeepsItsOwnLevel() {
        // The two appenders share one threshold; the range filters, not the levels, are
        // what separate the destinations. Guards against fixing one and skewing the other.
        Configuration config = configureWithFileLevel("INFO");

        AppenderRef fileRef = refNamed(config, "File");
        assertNotNull(fileRef);
        assertEquals(Level.INFO, fileRef.getLevel());
    }
}
