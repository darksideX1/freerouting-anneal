package app.freerouting.settings;

import static app.freerouting.constants.Constants.FREEROUTING_VERSION;

import app.freerouting.autoroute.BoardUpdateStrategy;
import app.freerouting.autoroute.ItemSelectionStrategy;
import app.freerouting.core.BoardFileDetails;
import app.freerouting.io.FileFormat;
import app.freerouting.logger.FRLogger;
import app.freerouting.util.ReflectionUtil;
import app.freerouting.util.gson.GsonProvider;
import app.freerouting.settings.sources.DefaultSettings;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public class GlobalSettings implements Serializable {

  private static Path userDataPath = Path.of(System.getProperty("java.io.tmpdir"), "freerouting");
  private static Path configurationFilePath = userDataPath.resolve("freerouting.json");
  private static Boolean isUserDataPathLocked = false;
  public final transient RuntimeEnvironment runtimeEnvironment = new RuntimeEnvironment();
  @SerializedName("profile")
  public final UserProfileSettings userProfileSettings = new UserProfileSettings();
  @SerializedName("gui")
  public final GuiSettings guiSettings = new GuiSettings();
  /**
   * @deprecated Use {@link #settingsMergerProtype} to obtain merged {@link RouterSettings}.
   *             This field is retained as a serialisation bridge for {@code freerouting.json}
   *             (written on save, read back on load) and as a target for legacy code paths such
   *             as {@code applyCommandLineArguments} and {@code setValue}.
   *             The {@code @SerializedName} is also required so that
   *             {@code ReflectionUtil.setFieldValue} can resolve the {@code "router.*"} property
   *             path. Do not use this field to drive routing decisions — obtain a merged
   *             {@link RouterSettings} via {@link #settingsMergerProtype} instead.
   */
  @Deprecated
  @SerializedName("router")
  public final RouterSettings routerSettings = new RouterSettings();
  @SerializedName("drc")
  public final DesignRulesCheckerSettings drcSettings = new DesignRulesCheckerSettings();
  @SerializedName("usage_and_diagnostic_data")
  public final UsageAndDiagnosticDataSettings usageAndDiagnosticData = new UsageAndDiagnosticDataSettings();
  @SerializedName("feature_flags")
  public final FeatureFlagsSettings featureFlags = new FeatureFlagsSettings();
  @SerializedName("api_server")
  public final ApiServerSettings apiServerSettings = new ApiServerSettings();
  @SerializedName("mcp_server")
  public final McpServerSettings mcpServerSettings = new McpServerSettings();
  @SerializedName("statistics")
  public final StatisticsSettings statistics = new StatisticsSettings();
  @SerializedName("logging")
  public final LoggingSettings logging = new LoggingSettings();
  @SerializedName("debug")
  public final transient DebugSettings debugSettings = new DebugSettings();
  private final transient String[] supportedLanguages = {
      "en",
      "de",
      "zh",
      "zh_TW",
      "hi",
      "es",
      "it",
      "fr",
      "ar",
      "bn",
      "ru",
      "pt",
      "ja",
      "ko",
      "pl",
      "nl",
      "tr",
      "vi",
      "id",
      "uk",
      "cs",
      "hu",
      "th",
      "sv",
      "ro"
  };
  @SerializedName("version")
  public String version;
  public transient boolean show_help_option;
  /** --helpful: the extended manual, not just the flag list. */
  public transient boolean show_helpful_option;
  public transient String compareFile1;
  public transient String compareFile2;
  // DRC report file details that we got from the command line arguments.
  public transient BoardFileDetails drc_report_file;
  /**
   * The initial input file path provided via command line arguments.
   * This is used for initialization and then transferred to RoutingJob.
   */
  public transient String initialInputFile;
  /**
   * The initial output file path provided via command line arguments.
   * This is used for initialization and then transferred to RoutingJob.
   */
  public transient String initialOutputFile;
  /**
   * The initial rules file path provided via command line arguments.
   * This is used for initialization.
   */
  public transient String initialRulesFile;
  /**
   * The design_session_filename field stores the optional Specctra session file
   * (.ses) path provided via the -de command line argument.
   */
  public transient String design_session_filename;
  /**
   * The current locale for the application.
   * It is initialized based on the system default locale, but can be overridden
   * via command line arguments.
   */
  public transient Locale currentLocale = Locale.getDefault();
  /**
   * Prototype instance of SettingsMerger for merging settings from various
   * sources. These sources are loaded at startup, and they are not going
   * to change during runtime.
   * The other settings sources (like settings from DNS, SES or RULES files,
   * or set by the user on the GUI or via the API) are handled separately
   * in combination with this.
   */
  public transient SettingsMerger settingsMergerProtype;

  public GlobalSettings() {
    // validate and set the current locale
    if (Arrays
        .stream(supportedLanguages)
        .noneMatch(currentLocale.getLanguage()::equals)) {
      // the fallback language is English
      currentLocale = Locale.ENGLISH;
    }

    settingsMergerProtype = new SettingsMerger(new DefaultSettings());
  }

  public static void lockUserDataPath() {
    isUserDataPathLocked = true;
  }

  public static Path getUserDataPath() {
    return userDataPath;
  }

  /**
   * Returns the resolved absolute path of the {@code freerouting.json} configuration file.
   * <p>This path is derived from {@link #getUserDataPath()} and is updated atomically
   * whenever {@link #setUserDataPath(Path)} is called (before the lock is engaged).
   * Use this accessor for logging, diagnostics, or tests that need to verify where the
   * configuration file is written.
   */
  public static Path getConfigurationFilePath() {
    return configurationFilePath;
  }

  public static void setUserDataPath(Path userDataPath) {
    if (!isUserDataPathLocked) {
      GlobalSettings.userDataPath = userDataPath;
      GlobalSettings.configurationFilePath = userDataPath.resolve("freerouting.json");
    }
  }

  /**
   * Resets the user-data-path lock and path to their initial defaults.
   * <p><strong>For testing only.</strong> Must never be called from production code.
   * Resets both static path fields and the lock flag so that
   * {@link #setUserDataPath(Path)} can be exercised in isolated unit tests.
   */
  static void resetForTesting() {
    isUserDataPathLocked = false;
    userDataPath = Path.of(System.getProperty("java.io.tmpdir"), "freerouting");
    configurationFilePath = userDataPath.resolve("freerouting.json");
  }

  /**
   * Returns the "release-safe" version string to be written to {@code freerouting.json}.
   * <p>The {@code -SNAPSHOT} suffix (appended by the build system to development builds) is
   * stripped so that only real release versions are recorded in the config file.
   * <p>This matters for migration: we only want to trigger per-version migration steps when
   * the user updates from one <em>release</em> to another, not on every SNAPSHOT rebuild.
   * Concretely, both {@code "2.2.1-SNAPSHOT"} and {@code "2.2.1"} produce {@code "2.2.1"}.
   *
   * @return the normalized version string, with any {@code -SNAPSHOT} suffix removed
   */
  public static String getReleaseSafeVersion() {
    String v = FREEROUTING_VERSION;
    int snapshotIdx = v.indexOf("-SNAPSHOT");
    return snapshotIdx >= 0 ? v.substring(0, snapshotIdx) : v;
  }

  /**
   * Compares two release-style version strings (e.g. {@code "2.2.0"} vs {@code "2.3.1"}).
   *
   * @return negative if {@code v1 < v2}, zero if equal, positive if {@code v1 > v2}.
   *         Returns {@code -1}/{@code +1} without further detail when either string cannot
   *         be parsed as a dot-separated numeric version.
   */
  static int compareVersionStrings(String v1, String v2) {
    if (v1 == null && v2 == null) {
      return 0;
    }
    if (v1 == null) {
      return -1;
    }
    if (v2 == null) {
      return 1;
    }
    String[] parts1 = v1.split("\\.");
    String[] parts2 = v2.split("\\.");
    int len = Math.max(parts1.length, parts2.length);
    for (int i = 0; i < len; i++) {
      try {
        int n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
        int n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
        if (n1 != n2) {
          return Integer.compare(n1, n2);
        }
      } catch (NumberFormatException e) {
        String s1 = i < parts1.length ? parts1[i] : "";
        String s2 = i < parts2.length ? parts2[i] : "";
        int cmp = s1.compareTo(s2);
        if (cmp != 0) {
          return cmp;
        }
      }
    }
    return 0;
  }

  /*
   * Loads the settings from the default JSON settings file.
   *
   * <p>Throws {@link NoSuchFileException} when the file (or its parent directory) does not
   * exist yet — this is the expected first-run condition and callers should treat it as
   * "create a new default config".
   *
   * <p>Throws {@link AccessDeniedException} when the OS denies read access to the file or
   * its parent directory.  The exception message already contains the path; the caller
   * should log an actionable warning that includes instructions on how to fix permissions.
   *
   * <p>Returns {@code null} when the file exists but its JSON content is corrupt or cannot
   * be parsed.  A WARN message is logged internally before returning so the user is always
   * notified regardless of how the caller handles the return value.
   */
  public static GlobalSettings load() throws IOException {
    GlobalSettings loadedSettings = null;
    try (Reader reader = Files.newBufferedReader(configurationFilePath, StandardCharsets.UTF_8)) {
      loadedSettings = GsonProvider.GSON.fromJson(reader, GlobalSettings.class);
    } catch (com.google.gson.JsonSyntaxException | com.google.gson.JsonIOException e) {
      // The file exists but is corrupt or cannot be parsed — log an actionable WARN
      // and fall through with loadedSettings == null so the caller starts fresh.
      FRLogger.warn("freerouting.json at '" + configurationFilePath
          + "' is corrupt or cannot be parsed — starting with default settings. "
          + "Delete the file or fix its JSON content manually to suppress this message. "
          + "Parse error: " + e.getMessage());
      return null;
    }
    // NoSuchFileException and AccessDeniedException (both extend IOException) propagate
    // to the caller as-is.  The caller is responsible for logging actionable messages.

    GlobalSettings defaultSettings = new GlobalSettings();
    if (loadedSettings != null) {
      // Preserve the version that was stored in the file so that future migration
      // code can compare it against the current release version.
      // NOTE: loadedSettings.version may be null for very old config files that
      // pre-date the version field.
      String fileVersion = loadedSettings.version;
      String currentVersion = getReleaseSafeVersion();

      // -----------------------------------------------------------------------
      // Version-change warnings — give the user actionable feedback whenever the
      // config file was written by a different version of Freerouting.
      // -----------------------------------------------------------------------
      if (fileVersion == null) {
        FRLogger.warn("freerouting.json at '" + configurationFilePath
            + "' has no version field (very old config file). "
            + "Some settings may not be available and have been reset to their defaults. "
            + "The file will be re-saved with the current version (" + currentVersion + ").");
      } else {
        int cmp = compareVersionStrings(fileVersion, currentVersion);
        if (cmp < 0) {
          // File was written by an older version — the most common case after an
          // upgrade.  Migration logic is not yet implemented, so warn the user.
          FRLogger.warn("freerouting.json at '" + configurationFilePath
              + "' was written by an older version of Freerouting (file: " + fileVersion
              + ", current: " + currentVersion + "). "
              + "Settings that still apply have been kept. "
              + "The file will be re-saved with the updated version string.");
        } else if (cmp > 0) {
          // File was written by a newer version — downgrade scenario.
          // Deliberately does NOT tell anyone to go back to the version that wrote this
          // file. This program is a fork with its own version sequence, so a higher number
          // here usually means the file came from upstream Freerouting rather than from a
          // later build of this program -- and every user upgrading from upstream 2.3.x hits
          // this branch on first run. Advising them to revert would send all of them back to
          // the router this release exists to replace.
          FRLogger.warn("freerouting.json at '" + configurationFilePath
              + "' was written by a different version (file: " + fileVersion
              + ", current: " + currentVersion + "). "
              + "Settings that still apply have been kept; any this version does not "
              + "recognise are ignored. The file will be re-saved.");
        }
      }

      // Always record the current release-safe version in the in-memory object and
      // on disk.  Using the release-safe version (no -SNAPSHOT) means SNAPSHOT
      // rebuilds of the same release never trigger a spurious re-save / migration.
      boolean isSaveNeeded = !currentVersion.equals(fileVersion);

      // Apply all the loaded settings to the result if they are not null
      ReflectionUtil.copyFields(loadedSettings, defaultSettings);
      defaultSettings.version = currentVersion;

      if (isSaveNeeded) {
        // TODO: insert per-version migration steps here when needed, e.g.:
        //   migrateSettings(fileVersion, currentVersion, defaultSettings);
        FRLogger.info("freerouting.json config version changed from '"
            + fileVersion + "' to '" + currentVersion + "' – configuration will be "
            + "re-saved once startup commits to a real run.");
        // Deferred, not written here: load() also runs for --help/--helpful, and a help
        // invocation must never mutate configuration on disk. The main flow flushes this
        // after the help early-exits. SNAPSHOT, not reference: the loaded object becomes
        // the live settings and accumulates CLI/runtime state before the flush runs --
        // persisting that would write -de/-do and friends into the config file.
        migrationSavePending = GsonProvider.GSON.fromJson(
            GsonProvider.GSON.toJson(defaultSettings), GlobalSettings.class);
      }
      loadedSettings = defaultSettings;
    }

    return loadedSettings;
  }

  /*
   * Saves the settings to the default JSON settings file.
   *
   * <p>The {@code version} field is always normalised to the release-safe version
   * (no {@code -SNAPSHOT} suffix) before writing so that SNAPSHOT builds never
   * pollute the stored config with a non-release version string.
   *
   * <p>Throws {@link AccessDeniedException} (subtype of {@link IOException}) when the OS
   * denies write access to the target directory or file.  The exception message contains
   * the path; the caller should log an actionable warning advising the user to check
   * permissions and, in Docker deployments, to verify the volume mount configuration.
   */
  public static void saveAsJson(GlobalSettings globalSettings) throws IOException {
    // Make sure that we have the directory structure in place, and create it if it
    // doesn't exist
    try {
      Files.createDirectories(configurationFilePath.getParent());
    } catch (AccessDeniedException e) {
      throw new AccessDeniedException(
          configurationFilePath.getParent().toString(), null,
          "Cannot create the user-data directory '" + configurationFilePath.getParent()
              + "' — permission denied. freerouting.json cannot be saved. "
              + "Check that the process has write permission on the parent directory. "
              + "In Docker deployments, verify that the volume is mounted with write access.");
    } catch (IOException e) {
      throw new IOException(
          "Failed to create the user-data directory '" + configurationFilePath.getParent()
              + "': " + e.getMessage()
              + ". freerouting.json cannot be saved.", e);
    }

    // Always stamp the file with the release-safe version, regardless of what the
    // caller had set on the object (safety net for all call sites).
    globalSettings.version = getReleaseSafeVersion();

    // Write the settings to the file
    try (Writer writer = Files.newBufferedWriter(configurationFilePath, StandardCharsets.UTF_8)) {
      GsonProvider.GSON.toJson(globalSettings, writer);
    } catch (AccessDeniedException e) {
      throw new AccessDeniedException(
          configurationFilePath.toString(), null,
          "Cannot write freerouting.json to '" + configurationFilePath
              + "' — permission denied. Settings won't be persisted. "
              + "Check that the process has write permission on the file and its parent directory. "
              + "In Docker deployments, verify that the volume is mounted with write access.");
    } catch (IOException e) {
      throw new IOException(
          "Failed to write freerouting.json to '" + configurationFilePath
              + "': " + e.getMessage()
              + ". Settings won't be persisted.", e);
    }
  }

  /*
   * Sets a property value in the settings, and it permanently saves it into the
   * settings file.
   * Property names are in the format of "section.property" (eg.
   * "router.max_passes", "gui:input_directory" or "profile-email").
   */
  /** Set when load() migrated the config version; written by flushPendingMigrationSave(). */
  private static volatile GlobalSettings migrationSavePending;

  /** Performs the migration re-save deferred by load(). Called only on paths that commit
   *  to a real run -- never on --help/--helpful, which must not write configuration. */
  public static synchronized void flushPendingMigrationSave() {
    GlobalSettings pending = migrationSavePending;
    if (pending == null) {
      return;
    }
    migrationSavePending = null;
    try {
      saveAsJson(pending);
    } catch (Exception e) {
      FRLogger.error("Failed to re-save migrated configuration", e);
    }
  }

  /**
   * The durable per-user home for configuration and logs. The inherited default was the
   * JVM temp directory, which any reboot or tmp cleanup wipes -- so "check the log"
   * pointed at a file that no longer existed and settings silently reset. Platform
   * app-data is what survives; temp remains only as the last resort when no home
   * directory is resolvable.
   */
  public static java.nio.file.Path defaultUserDataPath() {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      if (appData != null && !appData.isBlank()) {
        return java.nio.file.Path.of(appData, "freerouting");
      }
      // APPDATA unset (service accounts, minimal environments): user.home is still a
      // durable location and MUST be preferred -- temp is the last resort, not the
      // second choice.
      String home = System.getProperty("user.home");
      if (home != null && !home.isBlank()) {
        return java.nio.file.Path.of(home, "AppData", "Roaming", "freerouting");
      }
    } else if (os.contains("mac")) {
      String home = System.getProperty("user.home");
      if (home != null && !home.isBlank()) {
        return java.nio.file.Path.of(home, "Library", "Application Support", "freerouting");
      }
    } else {
      String xdg = System.getenv("XDG_DATA_HOME");
      // The XDG spec requires absolute paths; a relative value would make the default
      // depend on the launch directory, so it is ignored rather than obeyed.
      if (xdg != null && !xdg.isBlank() && java.nio.file.Path.of(xdg).isAbsolute()) {
        return java.nio.file.Path.of(xdg, "freerouting");
      }
      String home = System.getProperty("user.home");
      if (home != null && !home.isBlank()) {
        return java.nio.file.Path.of(home, ".local", "share", "freerouting");
      }
    }
    return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "freerouting");
  }

  public static Boolean setDefaultValue(String propertyName, String newValue) {
    try {
      var gs = load();
      gs.setValue(propertyName, newValue);
      saveAsJson(gs);
      return true;
    } catch (Exception e) {
      FRLogger.error("Failed to save property value for: " + propertyName, e);
      return false;
    }
  }

  public void applyNonRouterEnvironmentVariables() {
    // Read all the environment variables that begins with "FREEROUTING__"
    for (var entry : System
        .getenv()
        .entrySet()) {
      if (entry
          .getKey()
          .startsWith("FREEROUTING__")) {
        String propertyName = entry
            .getKey()
            .substring("FREEROUTING__".length())
            .toLowerCase()
            .replace("__", ".");

        // Skip router settings - they're handled by EnvironmentVariablesSource
        // to prevent conflicts with the SettingsMerger
        if (propertyName.startsWith("router.")) {
          continue;
        }

        setValue(propertyName, entry.getValue());
      }
    }
  }

  /*
   * Sets a property value in the settings for the current process, but it does so
   * without permanently saving it into the settings file.
   * For scenarios where the settings also needs to be saved in the settings file,
   * use the save() method instead.
   * Property names are in the format of "section.property" (eg.
   * "router.max_passes", "gui:input_directory" or "profile-email").
   */
  /**
   * Why the most recent {@link #setValue} failed, for callers that report the refusal.
   *
   * <p>Kept as state rather than folded into the return type because {@code setValue}
   * returns {@code Boolean} to GUI callers that only care whether it worked. The refusal
   * message is the one the user actually reads, and it previously hardcoded "unknown
   * settings property" for BOTH failure modes -- so a valid property with an unparseable
   * value reported that the property did not exist. That is the wrong signpost, and this
   * exists so the caller can print the right one.
   */
  private transient String lastSetValueFailureReason = "unknown settings property";

  String getLastSetValueFailureReason() {
    return lastSetValueFailureReason;
  }

  public Boolean setValue(String propertyName, String newValue) {
    try {
      ReflectionUtil.setFieldValue(this, propertyName, newValue);
      return true;
    } catch (IllegalArgumentException e) {
      // NOT an unknown property. The name resolved perfectly and the VALUE could not be
      // converted to the field's type. Reporting this as "unknown settings property" is
      // how defect 26 stayed hidden: the message sent readers hunting for a misspelling
      // while the name had been right all along. Say which of the two it is.
      FRLogger.error("Settings property '" + propertyName + "' exists, but the value '"
          + newValue + "' could not be converted to its type: " + e.getMessage(), null);
      lastSetValueFailureReason = "value cannot be converted to the property's type";
      return false;
    } catch (NoSuchFieldException e) {
      FRLogger.warn("Unknown settings property: " + propertyName);
      lastSetValueFailureReason = "unknown settings property";
      return false;
    } catch (Exception e) {
      FRLogger.error("Failed to set property value for: " + propertyName, e);
      return false;
    }
  }

  /** Command-line arguments the program could not honour, in the order seen. */
  private final java.util.List<String> rejectedArguments = new java.util.ArrayList<>();

  /**
   * Arguments that were refused, so startup can decide whether to abort.
   *
   * <p>Kept as a list rather than a count because the user needs to be told WHICH setting
   * did not apply; "one argument was ignored" sends them hunting.
   */
  public java.util.List<String> getRejectedArguments() {
    return java.util.Collections.unmodifiableList(rejectedArguments);
  }

  private void rejectArgument(String argument, String reason) {
    rejectedArguments.add(argument);
    FRLogger.error("Argument NOT applied (" + reason + "): '" + argument
        + "'. The run is not configured as requested.", null);
  }

  /**
   * Whether a headless run reports its outcome through the process exit status.
   *
   * <p>Off by default, and deliberately so. An incomplete board is the NORMAL result for
   * an autorouter -- connections it could not route are handed back to the engineer, who
   * moves components and tries again. Turning distinct codes on by default would make
   * every existing CI job that tests {@code $? -eq 0} start failing on boards that are
   * behaving exactly as expected.
   */
  @SerializedName("outcome_exit_codes")
  public Boolean outcomeExitCodes = false;

  /**
   * Options consumed at startup, before any settings object exists.
   *
   * <p>These have no field in the settings model by design -- {@code logging.file.pattern}
   * has to configure logging before there is a model to hold it, and
   * {@code user_data_path} decides where that model is even read from. The registry check
   * would therefore find nothing, refuse them, and log an ERROR saying the run was not
   * configured as requested, about options that HAD been applied. A false alarm is worse
   * than the silence it replaced: it teaches people to ignore the alarms that are real.
   *
   * <p>Named and narrow on purpose. This must never become a way to quieten the check --
   * a neighbouring but genuinely unknown option is still refused. It replaces an inline
   * string comparison that was repeated at two call sites, where the second was easy to
   * miss when adding the next one.
   */
  static boolean isBootstrapOwnedOption(String name) {
    return Objects.equals(name, "user_data_path")
        || Objects.equals(name, "logging.file.pattern");
  }

  public Locale getCurrentLocale() {
    return currentLocale;
  }

  public void applyCommandLineArguments(String[] p_args) {
    for (int i = 0; i < p_args.length; i++) {
      try {
        if (p_args[i].equalsIgnoreCase("-helpful") || p_args[i].equalsIgnoreCase("--helpful")) {
          // Checked BEFORE --help: "--helpful" starts with "--help", and an equalsIgnoreCase
          // chain that tested --help first would still miss it, but a future startsWith would
          // not. Order it defensively so the longer flag can never be swallowed.
          show_helpful_option = true;
        } else if (p_args[i].equalsIgnoreCase("-help") || p_args[i].equalsIgnoreCase("--help") || p_args[i].equalsIgnoreCase("-h")) {
          show_help_option = true;
          continue;
        }
        if (p_args[i].startsWith("--compare-boards=")) {
          String[] files = p_args[i].substring("--compare-boards=".length()).split(",");
          if (files.length == 2) {
            compareFile1 = files[0].trim();
            compareFile2 = files[1].trim();
          }
          continue;
        }
        if (p_args[i].startsWith("--")) {
          // it's a general settings value setter
          // Use split limit=2 so that values containing '=' (e.g. URLs with query strings)
          // are captured correctly as a single token.
          String[] parts = p_args[i]
              .substring(2)
              .split("=", 2);
          if ((parts.length == 2) && !isBootstrapOwnedOption(parts[0])) {
            if (parts[0].startsWith("debug.")) {
              // handle debug settings
              if (parts[0].equals("debug.enable_detailed_logging")) {
                debugSettings.enableDetailedLogging = Boolean.parseBoolean(parts[1]);
              } else if (parts[0].equals("debug.single_step_execution")) {
                debugSettings.singleStepExecution = Boolean.parseBoolean(parts[1]);
              } else if (parts[0].equals("debug.trace_insertion_delay")) {
                debugSettings.traceInsertionDelay = Integer.parseInt(parts[1]);
              } else if (parts[0].equals("debug.filter_by_net")) {
                String[] nets = parts[1].split(",");
                for (String net : nets) {
                  debugSettings.filterByNet.add(net.trim().toLowerCase());
                }
              } else {
                // No final else here meant an unrecognised debug flag matched nothing and
                // was dropped in silence, leaving the user certain they had enabled it.
                rejectArgument(p_args[i], "unrecognised debug setting");
              }
            } else if (!setValue(parts[0], parts[1])) {
              // Was hardcoded to "unknown settings property", which is a lie whenever the
              // name resolved and the VALUE was the problem.
              // setValue already answers this question and every caller used to discard
              // the answer. An unresolvable name is not a note to file -- it means the run
              // is not doing what it was told to do.
              rejectArgument(p_args[i], getLastSetValueFailureReason());
            }
          } else if (p_args[i].equalsIgnoreCase("--helpful")) {
            // Handled by the earlier parser. Named here so this loop does not warn that a
            // flag which just printed a manual is unknown -- three parsers see every
            // argument, and a new flag has to be known to all of them or the app contradicts
            // itself in its own output.
            show_helpful_option = true;
          } else if (!((parts.length == 2) && isBootstrapOwnedOption(parts[0]))) {
            // The exemption covers --name=value ONLY. Startup applies these options by
            // matching the "name=" prefix, so the bare form is never applied -- and
            // exempting it from the warning too would silently ignore it, recreating
            // inside this very fix the defect the rejection registry exists to remove.
            FRLogger.warn("Unknown command line argument: " + p_args[i]);
          }
        } else if (p_args[i].startsWith("-de")) {
          // the design file(s) are provided - can be DSN, SES, and/or RULES files
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            java.util.List<String> files = new java.util.ArrayList<>();
            int j = i + 1;
            while (j < p_args.length && !p_args[j].startsWith("-")) {
              // Split each argument by '+' to support legacy concatenation (e.g. file1.dsn+file2.rules)
              String[] parts = p_args[j].split("\\+");
              for (String part : parts) {
                files.add(part.trim());
              }
              j++;
            }

            // Track which file types we've seen to ensure only one of each
            boolean hasDsn = false;
            boolean hasSes = false;
            boolean hasRules = false;

            // Process each file and identify its type by extension
            for (String file : files) {
              file = file.trim();
              if (file.isEmpty()) {
                continue;
              }

              String lowerFile = file.toLowerCase();
              if (lowerFile.endsWith(".dsn")) {
                if (hasDsn) {
                  FRLogger.warn("Multiple DSN files provided in -de argument. Only the last one will be used.");
                }
                initialInputFile = file;
                hasDsn = true;
              } else if (lowerFile.endsWith(".json")) {
                if (!hasDsn) {
                  initialInputFile = file;
                  hasDsn = true;
                } else {
                  if (hasSes) {
                    FRLogger.warn("Multiple session files (SES/JSON) provided in -de argument. Only the last one will be used.");
                  }
                  design_session_filename = file;
                  hasSes = true;
                }
              } else if (lowerFile.endsWith(".ses")) {
                if (hasSes) {
                  FRLogger.warn("Multiple SES files provided in -de argument. Only the last one will be used.");
                }
                design_session_filename = file;
                hasSes = true;
              } else if (lowerFile.endsWith(".rules")) {
                if (hasRules) {
                  FRLogger.warn("Multiple RULES files provided in -de argument. Only the last one will be used.");
                }
                initialRulesFile = file;
                hasRules = true;
              } else {
                FRLogger.warn("Unknown file type in -de argument: " + file + ". Expected .dsn, .json, .ses, or .rules");
              }
            }

            // Skip the processed arguments
            i = j - 1;
          }
        } else if (p_args[i].startsWith("-di")) {
          // the design directory is provided
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            guiSettings.inputDirectory = p_args[i + 1];
            i++;
          }
        } else if (p_args[i].startsWith("-do")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            initialOutputFile = p_args[i + 1];
            i++;
          }
        } else if (p_args[i].startsWith("-drc")) {
          // DRC-only mode (must be checked before -dr)
          routerSettings.enabled = false;
          drcSettings.enabled = true;
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            drc_report_file = new BoardFileDetails();
            drc_report_file.format = FileFormat.DRC_JSON;
            drc_report_file.setFilename(p_args[i + 1]);
            i++;
          }
        } else if (p_args[i].startsWith("-dr")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            initialRulesFile = p_args[i + 1];
            i++;
          }
        } else if (p_args[i].startsWith("-mp")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            routerSettings.maxPasses = Integer.decode(p_args[i + 1]);

            if (routerSettings.maxPasses < 0) {
              routerSettings.maxPasses = 1;
            }
            if (routerSettings.maxPasses > 9999) {
              routerSettings.maxPasses = 9999;
            }
            // Note: 0 is allowed and means no limit
            i++;
          }
        } else if (p_args[i].startsWith("-mt")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            routerSettings.optimizer.maxThreads = Integer.decode(p_args[i + 1]);

            if (routerSettings.optimizer.maxThreads < 0) {
              routerSettings.optimizer.maxThreads = 0;
            }
            if (routerSettings.optimizer.maxThreads > 1024) {
              routerSettings.optimizer.maxThreads = 1024;
            }
            i++;
          }
        } else if (p_args[i].startsWith("-oit")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            routerSettings.optimizer.optimizationImprovementThreshold = Float.parseFloat(p_args[i + 1]) / 100;

            if (routerSettings.optimizer.optimizationImprovementThreshold <= 0) {
              routerSettings.optimizer.optimizationImprovementThreshold = 0.0f;
            }
            i++;
          }
        } else if (p_args[i].startsWith("-us")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            String op = p_args[i + 1]
                .toLowerCase()
                .trim();
            routerSettings.optimizer.boardUpdateStrategy = "global".equals(op) ? BoardUpdateStrategy.GLOBAL_OPTIMAL
                : ("hybrid".equals(op) ? BoardUpdateStrategy.HYBRID : BoardUpdateStrategy.GREEDY);
            i++;
          }
        } else if (p_args[i].startsWith("-is")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            String op = p_args[i + 1]
                .toLowerCase()
                .trim();
            routerSettings.optimizer.itemSelectionStrategy = op.indexOf("seq") == 0 ? ItemSelectionStrategy.SEQUENTIAL
                : (op.indexOf("rand") == 0 ? ItemSelectionStrategy.RANDOM : ItemSelectionStrategy.PRIORITIZED);
            i++;
          }
        } else if (p_args[i].startsWith("-hr")) { // hybrid ratio
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            routerSettings.optimizer.hybridRatio = p_args[i + 1].trim();
            i++;
          }
        } else if ("-l".equals(p_args[i])) {
          String localeString = "";
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            localeString = p_args[i + 1]
                .toLowerCase()
                .replace("-", "_");
            i++;
          }

          // the locale is provided
          if (localeString.startsWith("en")) {
            currentLocale = Locale.ENGLISH;
          } else if (localeString.startsWith("de")) {
            currentLocale = Locale.GERMAN;
          } else if (localeString.startsWith("zh_tw")) {
            currentLocale = Locale.TRADITIONAL_CHINESE;
          } else if (localeString.startsWith("zh")) {
            currentLocale = Locale.SIMPLIFIED_CHINESE;
          } else if (localeString.startsWith("hi")) {
            currentLocale = Locale.forLanguageTag("hi-IN");
          } else if (localeString.startsWith("es")) {
            currentLocale = Locale.forLanguageTag("es-ES");
          } else if (localeString.startsWith("it")) {
            currentLocale = Locale.forLanguageTag("it-IT");
          } else if (localeString.startsWith("fr")) {
            currentLocale = Locale.FRENCH;
          } else if (localeString.startsWith("ar")) {
            currentLocale = Locale.forLanguageTag("ar-EG");
          } else if (localeString.startsWith("bn")) {
            currentLocale = Locale.forLanguageTag("bn-BD");
          } else if (localeString.startsWith("ru")) {
            currentLocale = Locale.forLanguageTag("ru-RU");
          } else if (localeString.startsWith("pt_br")) {
            currentLocale = Locale.forLanguageTag("pt-BR");
          } else if (localeString.startsWith("pt")) {
            currentLocale = Locale.forLanguageTag("pt-PT");
          } else if (localeString.startsWith("ja")) {
            currentLocale = Locale.JAPANESE;
          } else if (localeString.startsWith("ko")) {
            currentLocale = Locale.KOREAN;
          } else if (localeString.startsWith("pl")) {
            currentLocale = Locale.forLanguageTag("pl-PL");
          } else if (localeString.startsWith("nl")) {
            currentLocale = Locale.forLanguageTag("nl-NL");
          } else if (localeString.startsWith("tr")) {
            currentLocale = Locale.forLanguageTag("tr-TR");
          } else if (localeString.startsWith("vi")) {
            currentLocale = Locale.forLanguageTag("vi-VN");
          } else if (localeString.startsWith("id")) {
            currentLocale = Locale.forLanguageTag("id-ID");
          } else if (localeString.startsWith("uk")) {
            currentLocale = Locale.forLanguageTag("uk-UA");
          } else if (localeString.startsWith("cs")) {
            currentLocale = Locale.forLanguageTag("cs-CZ");
          } else if (localeString.startsWith("hu")) {
            currentLocale = Locale.forLanguageTag("hu-HU");
          } else if (localeString.startsWith("th")) {
            currentLocale = Locale.forLanguageTag("th-TH");
          } else if (localeString.startsWith("sv")) {
            currentLocale = Locale.forLanguageTag("sv-SE");
          } else if (localeString.startsWith("ro")) {
            currentLocale = Locale.forLanguageTag("ro-RO");
          }

        } else if (p_args[i].equalsIgnoreCase("-helpful") || p_args[i].equalsIgnoreCase("--helpful")) {
          // Recognised here too. The flag is handled by the earlier parser, but this loop
          // warns about anything it does not know -- so without this branch the app told the
          // user their working flag was unknown. A refusal message about an argument that
          // just worked is worse than no message; same family as defect 26.
          show_helpful_option = true;
        } else if (p_args[i].startsWith("-dl")) {
          logging.file.enabled = false;
        } else if (p_args[i].startsWith("-da")) {
          usageAndDiagnosticData.disableAnalytics = true;
        } else if (p_args[i].startsWith("-host")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            runtimeEnvironment.host = p_args[i + 1].trim();
            i++;
          }
        } else if (p_args[i].startsWith("-help")) {
          show_help_option = true;
        } else if (p_args[i].startsWith("-inc")) {
          // ignore net class(es)
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            routerSettings.ignoreNetClasses = p_args[i + 1].split(",");
            i++;
          }
        } else if (p_args[i].startsWith("-dct")) {
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            guiSettings.dialogConfirmationTimeout = Integer.parseInt(p_args[i + 1]);

            if (guiSettings.dialogConfirmationTimeout <= 0) {
              guiSettings.dialogConfirmationTimeout = 0;
            }
            i++;
          }
        } else if (p_args[i].startsWith("-ll")) {
          // get the log level from the command line arguments
          // and save it to the settings
          if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
            logging.console.level = p_args[i + 1].toUpperCase();
            i++;
          }
        } else {
          FRLogger.warn("Unknown command line argument: " + p_args[i]);
        }
      } catch (Exception e) {
        FRLogger.error("There was a problem parsing the '" + p_args[i] + "' parameter", e);
      }
    }

  }

  public String getDesignDir() {
    return guiSettings.inputDirectory;
  }

  public int getMaxPasses() {
    return routerSettings.maxPasses;
  }

  public int getNumThreads() {
    return routerSettings.optimizer.maxThreads;
  }

  public String getHybridRatio() {
    return routerSettings.optimizer.hybridRatio;
  }

  public BoardUpdateStrategy getBoardUpdateStrategy() {
    return routerSettings.optimizer.boardUpdateStrategy;
  }

  public ItemSelectionStrategy getItemSelectionStrategy() {
    return routerSettings.optimizer.itemSelectionStrategy;
  }
}

