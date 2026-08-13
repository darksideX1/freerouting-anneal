package app.freerouting.datastructures;

/**
 * Implementation of a stack as an array
 */
@SuppressWarnings("unchecked")
public class ArrayStack<p_element_type> {

  /**
   * Number of slots allocated up front, regardless of the depth hint. The stack grows on
   * demand (see {@link #reallocate()}), so allocating the hint immediately costs memory
   * that is almost never used: every tree-traversal call site passes a hint of 10000
   * while typically pushing a few dozen nodes.
   */
  private static final int INITIAL_CAPACITY = 64;

  private int level = -1;
  private p_element_type[] node_arr;

  /**
   * Creates a new instance of ArrayStack. p_max_stack_depth is an expected-depth hint,
   * not a fixed capacity: the stack starts small and grows as elements are pushed.
   */
  public ArrayStack(int p_max_stack_depth) {
    int initial_capacity = Math.max(1, Math.min(p_max_stack_depth, INITIAL_CAPACITY));
    node_arr = (p_element_type[]) new Object[initial_capacity];
  }

  /**
   * Sets the stack to empty.
   */
  public void reset() {
    level = -1;
  }

  /**
   * Pushed p_element onto the stack.
   */
  public void push(p_element_type p_element) {

    ++level;

    if (level >= node_arr.length) {
      reallocate();
    }

    node_arr[level] = p_element;
  }

  /**
   * Pops the next element from the top of the stack. Returns null, if the stack is exhausted.
   */
  public p_element_type pop() {
    if (level < 0) {
      return null;
    }
    p_element_type result = node_arr[level];
    --level;
    return result;
  }

  private void reallocate() {
    p_element_type[] new_arr = (p_element_type[]) new Object[4 * this.node_arr.length];
    System.arraycopy(node_arr, 0, new_arr, 0, node_arr.length);
    this.node_arr = new_arr;
  }
}
