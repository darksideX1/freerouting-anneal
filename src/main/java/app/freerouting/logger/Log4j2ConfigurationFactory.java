package app.freerouting.logger;

import java.io.File;
import java.net.URI;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Order;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.config.plugins.Plugin;

/**
 * Custom Log4j2 ConfigurationFactory that programmatically builds the logging
 * configuration based on system properties set early in the application
 * startup.
 *
 * This eliminates the need for runtime configuration manipulation which causes
 * threading issues and exceptions.
 */
@Plugin(name = "FreeroutingConfigurationFactory", category = ConfigurationFactory.CATEGORY)
@Order(50)
public class Log4j2ConfigurationFactory extends ConfigurationFactory {

    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-6level %msg%n";

    /**
     * Where debug/trace output goes, given the main log location.
     *
     * <p>Derived rather than configured separately so the two files are always siblings and
     * a user who set one location cannot end up with the other somewhere unexpected. An
     * explicit {@code freerouting.logging.debug.file.location} still wins if set.
     *
     * <p>{@code routing.log} becomes {@code routing-debug.log}; a name with no extension
     * simply gains the suffix.
     */
    String debugFileLocationFor(String fileLocation) {
        String explicit = getProperty("freerouting.logging.debug.file.location", null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return deriveDebugFileLocation(fileLocation);
    }

    /**
     * Pure derivation of the debug log path, split out so it can be tested directly
     * without constructing a configuration factory or touching system properties.
     */
    static String deriveDebugFileLocation(String fileLocation) {
        if (fileLocation == null || fileLocation.isBlank()) {
            return fileLocation;
        }
        int slash = Math.max(fileLocation.lastIndexOf('/'), fileLocation.lastIndexOf('\\'));
        int dot = fileLocation.lastIndexOf('.');
        if (dot > slash) {
            return fileLocation.substring(0, dot) + "-debug" + fileLocation.substring(dot);
        }
        return fileLocation + "-debug";
    }

    private int getIntProperty(String key, int defaultValue) {
        String raw = getProperty(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    protected String[] getSupportedTypes() {
        return new String[] { "*" };
    }

    @Override
    public Configuration getConfiguration(LoggerContext loggerContext, ConfigurationSource source) {
        return getConfiguration(loggerContext, source.toString(), (URI) null);
    }

    @Override
    public Configuration getConfiguration(LoggerContext loggerContext, String name, URI configLocation) {
        ConfigurationBuilder<BuiltConfiguration> builder = newConfigurationBuilder();

        // Read configuration from system properties
        boolean consoleEnabled = getBooleanProperty("freerouting.logging.console.enabled", true);
        String consoleLevel = getProperty("freerouting.logging.console.level", "INFO");

        boolean fileEnabled = getBooleanProperty("freerouting.logging.file.enabled", true);
        String fileLevel = getProperty("freerouting.logging.file.level", "DEBUG");
        String fileLocation = getProperty("freerouting.logging.file.location", null);
        String filePattern = getProperty("freerouting.logging.file.pattern", PATTERN);

        // Set configuration name and status
        builder.setConfigurationName("FreeroutingConfiguration");
        builder.setStatusLevel(Level.WARN);

        // Create Console appender if enabled
        if (consoleEnabled) {
            AppenderComponentBuilder consoleAppender = builder.newAppender("Console", "Console")
                    .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT)
                    .add(builder.newLayout("PatternLayout")
                            .addAttribute("pattern", PATTERN))
                    // WARN and below only. ERROR and FATAL go to the stderr appender, and
                    // without this they went to BOTH -- so every error appeared twice in
                    // any terminal that merges the streams, which is most of them. Doubling
                    // the output at exactly the moment someone is confused is its own defect,
                    // and errors on stderr is the behaviour a shell user already expects.
                    .add(builder.newFilter("LevelRangeFilter", Filter.Result.ACCEPT, Filter.Result.DENY)
                            .addAttribute("minLevel", Level.WARN)
                            .addAttribute("maxLevel", Level.TRACE));
            builder.add(consoleAppender);
        }

        // Create File appender if enabled
        if (fileEnabled && fileLocation != null && !fileLocation.isBlank()) {
            // Ensure parent directory exists
            File logFile = new File(fileLocation);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // A log that grows without bound is a log that eventually fills the disk of
            // whoever trusted it. Cap each file and keep a small ring of them: once the
            // newest reaches maxSize it rolls, and only maxFiles are kept.
            String maxSize = getProperty("freerouting.logging.file.maxSize", "20M");
            int maxFiles = getIntProperty("freerouting.logging.file.maxFiles", 4);

            builder.add(builder.newAppender("File", "RollingFile")
                    .addAttribute("fileName", fileLocation)
                    .addAttribute("filePattern", fileLocation + ".%i")
                    .addAttribute("immediateFlush", true)
                    .add(builder.newLayout("PatternLayout")
                            .addAttribute("pattern", filePattern))
                    // INFO and above only. Without this the split is one-directional:
                    // the debug file keeps INFO out, but raising the file level would pour
                    // DEBUG/TRACE into the normal log and defeat the whole point.
                    .add(builder.newFilter("LevelRangeFilter", Filter.Result.ACCEPT, Filter.Result.DENY)
                            .addAttribute("minLevel", Level.FATAL)
                            .addAttribute("maxLevel", Level.INFO))
                    .addComponent(builder.newComponent("Policies")
                            .addComponent(builder.newComponent("SizeBasedTriggeringPolicy")
                                    .addAttribute("size", maxSize)))
                    .addComponent(builder.newComponent("DefaultRolloverStrategy")
                            .addAttribute("max", maxFiles)
                            .addAttribute("fileIndex", "min")));

            // DEBUG and TRACE go to their OWN file. Someone reading normal events should not
            // have to wade through trace output, and someone debugging should not have to
            // find their detail interleaved with routine INFO lines. Same rotation policy.
            String debugLocation = debugFileLocationFor(fileLocation);
            builder.add(builder.newAppender("DebugFile", "RollingFile")
                    .addAttribute("fileName", debugLocation)
                    .addAttribute("filePattern", debugLocation + ".%i")
                    .addAttribute("immediateFlush", true)
                    .add(builder.newLayout("PatternLayout")
                            .addAttribute("pattern", filePattern))
                    .add(builder.newFilter("LevelRangeFilter", Filter.Result.ACCEPT, Filter.Result.DENY)
                            .addAttribute("minLevel", Level.DEBUG)
                            .addAttribute("maxLevel", Level.TRACE))
                    .addComponent(builder.newComponent("Policies")
                            .addComponent(builder.newComponent("SizeBasedTriggeringPolicy")
                                    .addAttribute("size", maxSize)))
                    .addComponent(builder.newComponent("DefaultRolloverStrategy")
                            .addAttribute("max", maxFiles)
                            .addAttribute("fileIndex", "min")));
        }

        // Create stderr appender for errors
        AppenderComponentBuilder stderrAppender = builder.newAppender("stderr", "Console")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_ERR)
                .add(builder.newLayout("PatternLayout")
                        .addAttribute("pattern", filePattern)); // Use the same pattern for stderr
        builder.add(stderrAppender);

        // Configure root logger
        // NOT Level.ALL. This configuration filters per appender-ref, so a root of ALL
        // looks harmless -- nothing extra is written, because each appender still applies
        // its own threshold. What it silently destroys is every level GUARD in the
        // codebase: FRLogger.isTraceEnabled() asks the LOGGER, and an ALL logger answers
        // true forever.
        //
        // Roughly 198 hot-path sites are guarded precisely because FRLogger.trace() takes
        // an already-built String, so the concatenation runs before the level is checked.
        // With the guard permanently true, all of that string building ran on every call
        // and none of it was ever emitted -- measured on bm01 as byte[] being the single
        // largest allocator at 17.5%, plus String at 3.1%.
        RootLoggerComponentBuilder rootLogger = builder.newRootLogger(
                rootLevelFor(consoleEnabled, consoleLevel, fileEnabled, fileLevel));

        if (consoleEnabled) {
            rootLogger.add(builder.newAppenderRef("Console")
                    .addAttribute("level", parseLevelOrDefault(consoleLevel)));
        }

        if (fileEnabled && fileLocation != null && !fileLocation.isBlank()) {
            rootLogger.add(builder.newAppenderRef("File")
                    .addAttribute("level", parseLevelOrDefault(fileLevel)));
            // The SAME threshold as the normal file. These two attributes answer different
            // questions and the earlier fixed TRACE confused them: the reference level
            // decides WHETHER an event is delivered at all, while the appender's own
            // LevelRangeFilter decides WHICH of the two files receives an otherwise
            // enabled event. Pinning this to TRACE delivered every trace record no matter
            // what the user configured, so ordinary runs wrote the highest-volume output
            // this program produces -- and, with the 20MB ring added alongside it, rotated
            // away the diagnostics the ring exists to keep.
            rootLogger.add(builder.newAppenderRef("DebugFile")
                    .addAttribute("level", parseLevelOrDefault(fileLevel)));
        }

        // Always add stderr for ERROR level
        rootLogger.add(builder.newAppenderRef("stderr")
                .addAttribute("level", Level.ERROR));

        builder.add(rootLogger);

        return builder.build();
    }

    private String getProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        return (value != null) ? Boolean.parseBoolean(value) : defaultValue;
    }

    /**
     * The root threshold: as verbose as the most verbose ENABLED destination, and no more.
     *
     * <p>Both directions of getting this wrong are real. Too verbose (the previous
     * {@code Level.ALL}) and every {@code isTraceEnabled()} guard in the codebase becomes
     * a no-op, so hot paths build diagnostic strings for nobody. Too quiet and a
     * configured appender is starved, because the logger filters before the appender ever
     * sees the event -- a TRACE file behind an INFO root receives nothing at all.
     *
     * <p>Never falls below ERROR: the stderr appender is attached unconditionally for
     * errors, and a failing run must not be able to go completely silent.
     */
    static Level rootLevelFor(boolean consoleEnabled, String consoleLevel,
                              boolean fileEnabled, String fileLevel) {
        // In log4j2 a LARGER intLevel is more verbose (ERROR 200 ... TRACE 600).
        Level mostVerbose = Level.ERROR;
        if (consoleEnabled) {
            Level level = parseLevelOrDefault(consoleLevel);
            if (level.intLevel() > mostVerbose.intLevel()) {
                mostVerbose = level;
            }
        }
        if (fileEnabled) {
            Level level = parseLevelOrDefault(fileLevel);
            if (level.intLevel() > mostVerbose.intLevel()) {
                mostVerbose = level;
            }
        }
        return mostVerbose;
    }

    /** A typo in a level must not be able to turn logging off. */
    private static Level parseLevelOrDefault(String level) {
        try {
            return Level.valueOf(level.toUpperCase());
        } catch (Exception e) {
            return Level.INFO;
        }
    }
}