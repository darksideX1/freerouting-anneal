package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The board we keep and the progress we report must come from the same thread.
 *
 * <p>The racing pass ranked its threads TWICE, independently. A manual loop picked
 * {@code bestThread} by score and used it to decide whether the pass had made progress —
 * while the board actually adopted came from {@code BoardHistory.restoreBestBoard()},
 * which ranks by its own ordering and deduplicates by board hash.
 *
 * <p>Nothing forced those two answers to agree. When they disagreed, the router kept one
 * thread's board and reported another thread's counts, so "this pass routed N items"
 * described a board that had not been kept. In a best-of-N search the selection rule IS
 * the algorithm, and having two of them is worse than having a wrong one, because it
 * cannot be reasoned about at all.
 *
 * <p>One rule now: highest score wins, and the board, the counts and the outcome all come
 * from that same winner. Ties resolve to the lowest thread index — arbitrary, but
 * deterministic, which matters because this stream exists to make the race reproducible.
 */
class RacingWinnerTest {

  @Test
  void theHighestScoreWins() {
    assertEquals(2, BatchAutorouter.bestThreadIndexByScore(new float[] {1.0f, 5.0f, 9.0f, 3.0f}));
  }

  @Test
  void aTieResolvesToTheLowestIndex() {
    // Arbitrary but deterministic. A tie broken by thread completion order would make the
    // race unreproducible again, which is the thing this stream is fixing.
    assertEquals(1, BatchAutorouter.bestThreadIndexByScore(new float[] {2.0f, 7.0f, 7.0f, 7.0f}));
  }

  @Test
  void allEqualScoresPickTheFirst() {
    assertEquals(0, BatchAutorouter.bestThreadIndexByScore(new float[] {4.0f, 4.0f, 4.0f}));
  }

  @Test
  void aSingleThreadIsItsOwnWinner() {
    assertEquals(0, BatchAutorouter.bestThreadIndexByScore(new float[] {-12.5f}));
  }

  @Test
  void negativeScoresAreCompared() {
    // Scores are normalised and can be negative; a naive 0-initialised max would return
    // the wrong thread for an all-negative pass and quietly adopt the worst board.
    assertEquals(1, BatchAutorouter.bestThreadIndexByScore(new float[] {-9.0f, -2.0f, -30.0f}));
  }

  @Test
  void noThreadsIsNotAWinner() {
    assertEquals(-1, BatchAutorouter.bestThreadIndexByScore(new float[] {}));
  }
}
