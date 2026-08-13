package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Defect 29: the fanout pin-exit neckdown laid tracks below the board's declared minimum
 * without reporting it, and the run then claimed zero violations.
 *
 * <p>Measured on public boards: one wire of ninety-five at 0.225 mm against a 0.30 mm
 * minimum, thirty-five tracks at 0.1124 against 0.15, and traces at exactly three quarters
 * of the board width in twenty-three of ninety-two session files from one sweep. Reported
 * by a third party before it could be reproduced here.
 *
 * <p>The narrowing itself is kept. Refusing it costs real pin escapes — twelve of one
 * fixture's 157 SMD pins, and completions on two of upstream's own regression boards — on
 * boards where the net width equals the board minimum and no legal neckdown exists at all.
 * What changed is that a track under the minimum is now named in the log, so a board that
 * may not be manufacturable says so rather than reporting a clean run.
 *
 * <p>These tests pin the candidate ladder. That the caller reports a sub-minimum choice is
 * asserted at the call site, not here — this method does not know the board.
 */
class NeckdownTest {

  @Test
  @Timeout(10)
  @DisplayName("the fallback ladder is offered in order, widest first")
  void ladderOrderIsPreserved() {
    LinkedHashSet<Integer> c = InsertFoundConnectionAlgo.neckdownCandidates(200, null, null);

    assertEquals(List.of(150, 120, 100), List.copyOf(c),
        "three quarters, then three fifths, then half — widest first, so the least drastic "
            + "narrowing that fits is the one taken");
  }

  @Test
  @Timeout(10)
  @DisplayName("a width the pin declares comes before our guesses")
  void pinDeclaredNeckdownIsTriedFirst() {
    LinkedHashSet<Integer> c = InsertFoundConnectionAlgo.neckdownCandidates(200, 180, null);

    assertEquals(180, List.copyOf(c).get(0),
        "a pin's neckdown allowance is a design rule and outranks anything we invent");
  }

  @Test
  @Timeout(10)
  @DisplayName("both pins contribute, without duplicates")
  void bothPinsAreConsidered() {
    LinkedHashSet<Integer> c = InsertFoundConnectionAlgo.neckdownCandidates(200, 180, 180);

    assertEquals(1, c.stream().filter(w -> w == 180).count(),
        "two pins declaring the same allowance is one candidate, not two attempts at it");
  }

  @Test
  @Timeout(10)
  @DisplayName("no candidate is a zero-width track")
  void degenerateCandidatesAreRejected() {
    for (int base : new int[] {1, 2, 3}) {
      for (int w : InsertFoundConnectionAlgo.neckdownCandidates(base, null, null)) {
        assertTrue(w > 0, "half width " + w + " from base " + base + " is not a track");
      }
    }
  }
}
