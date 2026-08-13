package app.freerouting.settings.sources;

import app.freerouting.logger.FRLogger;
import app.freerouting.util.ReflectionUtil;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.SettingsSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides router settings from command-line arguments.
 * Only the settings specified via CLI will be non-null.
 */
public class CliSettings implements SettingsSource {

    private static final int PRIORITY = 60;
    private final RouterSettings settings;
    private final Map<String, String> parsedArguments;

    /**
     * Creates a CliSettings source by parsing command-line arguments.
     *
     * @param args Command-line arguments array
     */
    public CliSettings(String[] args) {
        this.parsedArguments = new HashMap<>();
        this.settings = parseArguments(args);
    }

    private RouterSettings parseArguments(String[] args) {
        RouterSettings settings = new RouterSettings();
        boolean hasDesignInputArgument = false;
        boolean hasDesignOutputArgument = false;
        boolean hasExplicitRouterEnabledArgument = false;

        // Parse command-line arguments and populate only the specified settings
        // This uses the same logic as GlobalSettings.applyCommandLineArguments
        // but only for router-related settings

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (arg.startsWith("--")) {
                // Handle --property=value format
                if (arg.contains("=")) {
                    String[] parts = arg.substring(2).split("=", 2);
                    String propertyName = parts[0];
                    String value = parts.length > 1 ? parts[1] : "";

                    if ("router.enabled".equals(propertyName)) {
                        hasExplicitRouterEnabledArgument = true;
                    }

                    if (propertyName.startsWith("router.")) {
                        applyRouterSetting(settings, propertyName, value);
                    }
                } else {
                    // A "--flag" with no "=" used to fall through here doing nothing at
                    // all: no warning, and its value token was not consumed either, so
                    // BOTH arguments vanished and the run continued with the setting at
                    // its default. Exit code 0, no message, wrong measurement.
                    String error = validationErrorFor(arg);
                    rejectedArguments.add(arg);
                    FRLogger.error(error, null);
                }
            } else if (arg.startsWith("-")) {
                // Handle -flag value format
                String flag = arg.substring(1);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("-")) ? args[++i] : "";

                if ("de".equals(flag)) {
                    hasDesignInputArgument = true;
                } else if ("do".equals(flag)) {
                    hasDesignOutputArgument = true;
                }

                // Map short flags to router settings
                String propertyName = mapFlagToProperty(flag);
                if (propertyName != null && propertyName.startsWith("router.")) {
                    applyRouterSetting(settings, propertyName, value);
                }
            }
        }

        // Legacy batch invocation (`-de ... -do ...`) is expected to route immediately.
        // Force router enabled unless the caller explicitly set --router.enabled=... .
        if (hasDesignInputArgument && hasDesignOutputArgument && !hasExplicitRouterEnabledArgument) {
            settings.enabled = true;
            FRLogger.debug("Applied CLI router setting: router.enabled = true (implicit from -de/-do batch mode)");
        }

        return settings;
    }

    /**
     * Why an argument cannot be honoured, or null when its FORM is acceptable.
     *
     * <p>Validates form only, never ownership: an unrecognised but well-formed
     * {@code --some.flag=value} returns null, because settings belonging to other
     * components (logging, GUI, API server) legitimately pass through this parser on
     * their way elsewhere. Rejecting those here would break flags that work today.
     *
     * <p>Pure and package-private so the rule can be tested without building a settings
     * object or parsing a whole command line.
     */
    static String validationErrorFor(String arg) {
        if (arg == null || !arg.startsWith("--") || arg.contains("=")) {
            return null;
        }
        return "Unrecognised argument form: '" + arg + "'. Long options require "
                + "--name=value; the space-separated form '" + arg + " <value>' is NOT "
                + "supported and would be silently ignored. This argument was NOT applied.";
    }

    /** Arguments refused during parsing, so a caller can decide whether to abort. */
    public java.util.List<String> getRejectedArguments() {
        return java.util.Collections.unmodifiableList(rejectedArguments);
    }

    private final java.util.List<String> rejectedArguments = new java.util.ArrayList<>();

    private void applyRouterSetting(RouterSettings settings, String propertyName, String value) {
        try {
            // Remove "router." prefix if present
            String fieldPath = propertyName.startsWith("router.")
                    ? propertyName.substring(7)
                    : propertyName;

            ReflectionUtil.setFieldValue(settings, fieldPath, value);
            parsedArguments.put(propertyName, value);
            FRLogger.debug("Applied CLI router setting: " + propertyName + " = " + value);
        } catch (Exception e) {
            FRLogger.warn("Failed to apply CLI router setting: " + propertyName + ": " + e.getMessage());
        }
    }

    private String mapFlagToProperty(String flag) {
        // Map short flags to full property names
        return switch (flag) {
            case "mp" -> "router.max_passes";
            case "mt" -> "router.max_threads";
            // Add more mappings as needed
            default -> null;
        };
    }

    @Override
    public RouterSettings getSettings() {
        return settings;
    }

    @Override
    public String getSourceName() {
        return "CLI Arguments";
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    /**
     * Gets the parsed arguments for debugging/logging.
     *
     * @return Map of property names to values
     */
    public Map<String, String> getParsedArguments() {
        return new HashMap<>(parsedArguments);
    }
}
