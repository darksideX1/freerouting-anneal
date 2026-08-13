package app.freerouting.datastructures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * {@link ArrayStack} is constructed inside the tree-traversal hot path
 * (ShapeSearchTree, ShapeSearchTree45Degree, ShapeSearchTree90Degree,
 * MinAreaTree.overlaps), always with a depth hint of 10000. Allocation profiling of a
 * single DAC2020 bm01 route attributed ~85% of all allocated bytes to this
 * constructor: the hint is paid for in full, on every call, before a single element is
 * pushed.
 *
 * <p>These tests pin the contract: a depth hint is a hint, not an up-front cost, and
 * the stack still behaves correctly when it has to grow past it.
 */
class ArrayStackAllocationTest {

  /** Keeps constructed stacks reachable so the JIT cannot elide the allocation. */
  private static Object sink;

  private static long allocatedBytes() {
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
  }

  @Test
  void constructionWithLargeDepthHintMustNotAllocateProportionally() {
    final int iterations = 1000;
    final int depthHint = 10_000; // the value every tree-traversal call site passes

    for (int i = 0; i < 100; i++) {
      sink = new ArrayStack<Object>(depthHint); // warm up
    }

    long before = allocatedBytes();
    for (int i = 0; i < iterations; i++) {
      sink = new ArrayStack<Object>(depthHint);
    }
    long used = allocatedBytes() - before;

    long eagerCost = (long) iterations * depthHint * 8L; // ~80 MB of references
    assertTrue(
        used < eagerCost / 10,
        "ArrayStack allocates its depth hint up front: "
            + used
            + " bytes for "
            + iterations
            + " constructions (eager cost is ~"
            + eagerCost
            + " bytes). A depth hint must not be paid for before it is used.");
  }

  @Test
  void stackGrowsBeyondItsInitialCapacity() {
    ArrayStack<Integer> stack = new ArrayStack<>(4);
    for (int i = 0; i < 5000; i++) {
      stack.push(i);
    }
    for (int i = 4999; i >= 0; i--) {
      assertEquals(Integer.valueOf(i), stack.pop(), "LIFO order must survive growth");
    }
    assertNull(stack.pop(), "an exhausted stack returns null");
  }

  @Test
  void resetMakesTheStackReusable() {
    ArrayStack<String> stack = new ArrayStack<>(8);
    stack.push("a");
    stack.push("b");
    stack.reset();
    assertNull(stack.pop(), "reset() empties the stack");
    stack.push("c");
    assertEquals("c", stack.pop(), "the stack is usable again after reset()");
  }
}
