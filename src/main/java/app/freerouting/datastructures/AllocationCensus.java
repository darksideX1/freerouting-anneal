package app.freerouting.datastructures;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts who allocates what, exactly, when asked to.
 *
 * <p><b>Read this before drawing a conclusion from its output.</b> This census sees ONLY the
 * constructors hand-instrumented to call record(), and those are all geometry types. It
 * cannot see collection nodes, arrays, boxed values, or anything in the JDK. Its output is a
 * ranking WITHIN instrumented geometry and nothing else. It is not a share of total
 * allocation and must never be read as one.
 *
 * <p>That distinction is not hypothetical. This census was used to conclude that the
 * allocation remaining after the first US-3 sites was "geometric work rather than waste" --
 * a statement it is structurally incapable of supporting, since geometry is the only thing it
 * counts. A JFR class breakdown of the same workload then showed roughly 19% of all bytes in
 * sorted-container bookkeeping, invisible here, and one of those containers turned out to be
 * removable outright. Use JFR for what and how much; use this for which caller, the question
 * JFR answers only approximately.
 *
 * <p>JFR answers "what is allocated" well and "where" only approximately: its frame
 * attribution is sampled, and inlining smears a hot allocation onto whichever small method
 * happens to be next to it. This lane has already recorded one instance of that trap
 * ({@code ClearanceMatrix.get_value} showing as the top frame while allocating nothing).
 * Steering an optimisation by that is guesswork with a number attached.
 *
 * <p>This is the exact version: the hot constructors report themselves, the census walks up
 * to the first frame OUTSIDE the geometry package -- the caller that actually wanted the
 * object -- and counts it. The result is a ranked list of real call sites rather than a
 * smeared profile.
 *
 * <p><b>Off unless asked for.</b> {@code -Dfreerouting.alloc.census=N} enables it with a
 * sample rate of one in N; {@code =1} counts every allocation. When disabled the only cost
 * is reading a {@code static final boolean}, which the JIT folds away entirely.
 *
 * <p>Sampling exists because a stack walk per {@code IntPoint} is far more expensive than
 * the allocation it is measuring; on a board that allocates billions of them, an exact walk
 * would change the very behaviour being studied. One in 64 preserves the RANKING, which is
 * the question being asked, and states its own rate in the report so nobody reads a sampled
 * count as an absolute one.
 */
public final class AllocationCensus {

  private static final String PROPERTY = "freerouting.alloc.census";
  private static final int SAMPLE_RATE;
  private static final boolean ENABLED;

  /**
   * Sample rate from the raw property value. Anything that is not a positive number means
   * DISABLED.
   *
   * <p>This used to fall back to 1 on a parse failure -- exact census, the most expensive
   * mode there is -- so a typo in the property put a StackWalker traversal on every
   * allocation and the run simply became mysteriously slow. It also clamped with
   * {@code Math.max(1, ...)}, so a parsed 0 became 1 and asking for the diagnostic to be
   * off turned it fully on.
   *
   * <p>A diagnostic nobody asked for, running at maximum cost, is worse than no diagnostic.
   */
  static int parseSampleRate(String p_raw) {
    if (p_raw == null || p_raw.trim().isEmpty()) {
      return 0;
    }
    try {
      int parsed = Integer.parseInt(p_raw.trim());
      return parsed > 0 ? parsed : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  static {
    String raw = System.getProperty(PROPERTY);
    int rate = parseSampleRate(raw);
    if (rate == 0 && raw != null && !raw.trim().isEmpty()) {
      // Never silent: the user asked for something and did not get it.
      System.err.println("[freerouting] ignoring " + PROPERTY + "='" + raw
          + "': allocation census is disabled. Use a positive sample rate, e.g. -D"
          + PROPERTY + "=100.");
    }
    SAMPLE_RATE = rate;
    ENABLED = rate > 0;
    if (ENABLED) {
      Runtime.getRuntime().addShutdownHook(new Thread(AllocationCensus::report, "alloc-census"));
    }
  }

  private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
  private static final AtomicLong TICK = new AtomicLong();

  private AllocationCensus() {
  }

  public static boolean isEnabled() {
    return ENABLED;
  }

  /**
   * Records one allocation of {@code p_type}, attributed to the caller that wanted it.
   *
   * <p>Called from constructors, so the first two frames are the constructor and this
   * method; the interesting frame is the first one outside {@code geometry.planar}, because
   * geometry calling geometry tells us nothing about which part of the router is driving
   * the work.
   */
  public static void record(String p_type) {
    if (!ENABLED) {
      return;
    }
    if (SAMPLE_RATE > 1 && (TICK.incrementAndGet() % SAMPLE_RATE) != 0) {
      return;
    }
    String site = StackWalker.getInstance()
        .walk(frames -> frames
            .skip(2)
            .filter(f -> !f.getClassName().startsWith("app.freerouting.geometry.planar"))
            .findFirst()
            .map(f -> f.getClassName() + "." + f.getMethodName())
            .orElse("(no caller outside geometry)"));
    COUNTS.computeIfAbsent(p_type + "  <-  " + site, k -> new LongAdder()).increment();
  }

  /** Clears the census; used by tests so one case cannot contaminate the next. */
  public static void reset() {
    COUNTS.clear();
    TICK.set(0);
  }

  /** The current tally, for tests and for callers that want it before exit. */
  public static Map<String, Long> snapshot() {
    Map<String, Long> out = new ConcurrentHashMap<>();
    COUNTS.forEach((k, v) -> out.put(k, v.sum()));
    return out;
  }

  /** Prints the ranked sites to stderr, loudest first. */
  public static void report() {
    if (!ENABLED || COUNTS.isEmpty()) {
      return;
    }
    long total = COUNTS.values().stream().mapToLong(LongAdder::sum).sum();
    StringBuilder sb = new StringBuilder();
    sb.append("\n=== ALLOCATION CENSUS (sample rate 1 in ").append(SAMPLE_RATE)
        .append(", ").append(total).append(" samples) ===\n");
    sb.append("counts are SAMPLED, so read them as proportions rather than totals\n");
    COUNTS.entrySet().stream()
        .sorted(Map.Entry.<String, LongAdder>comparingByValue(
            Comparator.comparingLong(LongAdder::sum)).reversed())
        .limit(25)
        .forEach(e -> sb.append(String.format("  %6.2f%%  %10d  %s%n",
            100.0 * e.getValue().sum() / total, e.getValue().sum(), e.getKey())));
    System.err.print(sb);
  }
}
