package app.freerouting.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * An error reference is only useful if it identifies exactly one error.
 *
 * <p>The reference exists so a user can quote {@code FR-XXXXXX} and have support find that
 * exact stack trace in the log. The first implementation masked {@code nanoTime()} to 24
 * bits: 16.7 million values, cycling every ~16.8 ms of monotonic time, with nothing to
 * separate two calls that observe the same tick. A GUI that fails repeatedly — which is
 * the situation the reference is for — could therefore print the same reference for
 * unrelated exceptions, and a repeated failure is precisely when two entries land close
 * together in time.
 *
 * <p>A monotonic counter cannot repeat within a process, which is the guarantee that
 * matters: one log file, one process, one reference per error. The random tag separates
 * concurrent processes sharing a log directory, so a reference is not ambiguous across
 * two runs either.
 */
class ErrorReferenceTest {

  @Test
  void referencesNeverRepeat() {
    // The old implementation could collide here; a counter cannot.
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      String reference = DefaultExceptionHandler.newErrorReference();
      assertTrue(seen.add(reference), "duplicate error reference issued: " + reference);
    }
    assertEquals(10_000, seen.size());
  }

  @Test
  void referenceIsQuotableByAHuman() {
    // It gets read aloud, typed into a ticket, and pasted into a search box. Keep it
    // short, unambiguous in case, and free of characters that invite transcription errors.
    String reference = DefaultExceptionHandler.newErrorReference();

    assertTrue(reference.startsWith("FR-"), "should be recognisable as ours: " + reference);
    assertTrue(reference.length() <= 16, "should stay short enough to dictate: " + reference);
    assertTrue(reference.matches("FR-[0-9A-F]+-[0-9]+"),
        "unexpected shape, would break a log search: " + reference);
  }

  @Test
  void referencesFromOneProcessShareTheirTag() {
    // Same process, same log file: the tag is constant and only the sequence advances,
    // so two references from one run sort in the order the errors happened.
    String first = DefaultExceptionHandler.newErrorReference();
    String second = DefaultExceptionHandler.newErrorReference();

    String firstTag = first.substring(0, first.lastIndexOf('-'));
    String secondTag = second.substring(0, second.lastIndexOf('-'));
    assertEquals(firstTag, secondTag);
  }
}
