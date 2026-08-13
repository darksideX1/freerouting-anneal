package app.freerouting.datastructures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

/**
 * A reused scratch stack must behave exactly like a fresh one.
 *
 * <p>US-3, and the first surgical allocation site: {@code MinAreaTree.overlaps} allocated a
 * new {@link ArrayStack} on every call, which is <b>8.1% of all allocation</b> on bm01 --
 * the single largest site in our own code, ahead of every geometry type. It is a scratch
 * structure for a tree walk, not a value object, and {@code ArrayStack} already has
 * {@link ArrayStack#reset()} because it was built to be reused.
 *
 * <p>Reuse has exactly one failure mode worth testing: <b>state leaking between uses</b>. A
 * stack that is not properly emptied returns a previous caller's nodes, and a tree query
 * would then report overlaps that are not there -- silently, and only under the specific
 * ordering that leaves residue behind. These assertions pin that, and they are written
 * against {@code ArrayStack} directly so the failure is unambiguous rather than surfacing
 * as a strange routing result three layers up.
 *
 * <p>The threading question is handled at the call site rather than here: this fork has
 * already had one concurrent search-tree crash, so the reused stack must not be a plain
 * shared field.
 */
class ArrayStackReuseTest {

  @Test
  void resetEmptiesTheStackCompletely() {
    ArrayStack<String> stack = new ArrayStack<>(10);
    stack.push("a");
    stack.push("b");
    stack.push("c");

    stack.reset();

    assertNull(stack.pop(), "after reset the stack must be empty, not merely rewound");
  }

  @Test
  void aResetStackBehavesLikeAFreshOne() {
    // The property that makes reuse safe: same inputs, same outputs, regardless of what
    // the stack was used for before.
    ArrayStack<String> reused = new ArrayStack<>(10);
    reused.push("old-1");
    reused.push("old-2");
    reused.pop();
    reused.reset();

    ArrayStack<String> fresh = new ArrayStack<>(10);

    for (ArrayStack<String> s : new ArrayStack[] {reused, fresh}) {
      s.push("x");
      s.push("y");
      assertEquals("y", s.pop());
      assertEquals("x", s.pop());
      assertNull(s.pop(), "and then empty");
    }
  }

  @Test
  void residueFromADeeperUseDoesNotSurvive() {
    // The dangerous ordering: a deep walk followed by a shallow one. If reset only moved a
    // pointer without the shallow walk overwriting the old entries, the shallow caller
    // could read the deep caller's leftovers.
    ArrayStack<String> stack = new ArrayStack<>(4);
    for (int i = 0; i < 200; i++) {
      stack.push("deep-" + i);
    }

    stack.reset();
    stack.push("only");

    assertEquals("only", stack.pop());
    assertNull(stack.pop(), "no residue from the deep use may remain visible");
  }

  @Test
  void growthStillWorksAfterAReset() {
    // Reuse must not strand the stack at whatever capacity it had; a later deeper walk has
    // to be able to grow again.
    ArrayStack<Integer> stack = new ArrayStack<>(2);
    stack.push(1);
    stack.reset();

    for (int i = 0; i < 500; i++) {
      stack.push(i);
    }
    for (int i = 499; i >= 0; i--) {
      assertEquals(i, stack.pop());
    }
    assertNull(stack.pop());
  }

  @Test
  void separateInstancesRemainIndependent() {
    ArrayStack<String> a = new ArrayStack<>(8);
    ArrayStack<String> b = new ArrayStack<>(8);
    assertNotSame(a, b);

    a.push("a-only");
    assertNull(b.pop(), "pushing on one stack must not populate another");
  }
}
