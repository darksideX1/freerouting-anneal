package app.freerouting.geometry.planar;

import app.freerouting.logger.FRLogger;
import java.io.Serializable;
import java.math.BigInteger;

/**
 * Implementation of the abstract class Point as a tuple of integers.
 */
public class IntPoint extends Point implements Serializable {

  /**
   * the x coordinate of this point
   */
  public final int x;
  /**
   * the y coordinate of this point
   */
  public final int y;

  /**
   * create an IntPoint from two integer coordinates
   */
  /**
   * Coordinates seen outside the exact range, for the run as a whole.
   *
   * <p>Counted rather than thrown because whether a violation should end a run depends on
   * whether it happens on real boards, and that is evidence we do not have yet. What is
   * not defensible is the previous answer: silence.
   */
  private static final java.util.concurrent.atomic.AtomicLong EXACT_RANGE_VIOLATIONS =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * Whether a coordinate keeps this geometry's arithmetic exact.
   *
   * <p>{@link Limits#CRIT_INT} is 2^25. Within it, the products inside
   * {@code IntVector.side_of} are at most 2^50 and their difference at most 2^51 — inside
   * a double's 53-bit significand, so the orientation predicates are exact with no filter
   * and no fallback. Outside it, that guarantee is simply gone.
   */
  public static boolean isWithinExactRange(int coordinate) {
    // Widen BEFORE taking the absolute value. Math.abs(Integer.MIN_VALUE) returns
    // Integer.MIN_VALUE -- still negative -- so the int form reports the most
    // out-of-range coordinate representable as being comfortably IN range. The original
    // guard had this hole too, which is why MIN_VALUE never even reached the debug log.
    return Math.abs((long) coordinate) <= Limits.CRIT_INT;
  }

  /** How many points broke the exactness invariant in this run. */
  /**
   * Violations since process start.
   *
   * <p>PROCESS-GLOBAL and never reset in production. The scheduler runs several jobs
   * concurrently, so reading this directly to describe ONE job reports another job's
   * violations against it, and marks every later job in the same process forever. Callers
   * describing a single job must take a snapshot before it starts and subtract -- see
   * {@link #exactRangeViolationsSince(long)}.
   */
  public static long exactRangeViolationCount() {
    return EXACT_RANGE_VIOLATIONS.get();
  }

  /**
   * Violations recorded since a snapshot taken with {@link #exactRangeViolationCount()}.
   *
   * <p>Never negative: the counter only rises, but a defensive clamp costs nothing and
   * keeps a reordered snapshot from reporting a nonsensical negative count.
   */
  public static long exactRangeViolationsSince(long snapshot) {
    long now = EXACT_RANGE_VIOLATIONS.get();
    return Math.max(0L, now - snapshot);
  }

  /** Test hook. Not for production use. */
  static void resetExactRangeViolations() {
    EXACT_RANGE_VIOLATIONS.set(0);
  }

  public IntPoint(int p_x, int p_y) {
    app.freerouting.datastructures.AllocationCensus.record("IntPoint");
    if (!isWithinExactRange(p_x) || !isWithinExactRange(p_y)) {
      // Counted per POINT, not per axis: the question a reader asks is how many points
      // broke the invariant, and counting both coordinates of one bad point overstates it.
      long violations = EXACT_RANGE_VIOLATIONS.incrementAndGet();
      if (violations == 1) {
        // Once, loudly. A board that breaks this breaks it in a loop, so the first
        // occurrence carries the detail and the total is reported at the end of the run.
        // This used to be a debug() behind isDebugEnabled(), i.e. nothing at all at the
        // default level, while the geometry kept computing on arithmetic that was no
        // longer guaranteed exact.
        FRLogger.error("Coordinate outside the exact range: (" + p_x + ", " + p_y + ");"
            + " the limit is +/-" + Limits.CRIT_INT + " (2^25). Beyond it this geometry's"
            + " orientation predicates are no longer guaranteed exact, so any clearance"
            + " result from this board must be treated as unverified. Further occurrences"
            + " are counted, not logged.", null);
      }
    }

    x = p_x;
    y = p_y;
  }

  /**
   * Returns true, if this IntPoint is equal to p_ob
   */
  @Override
  public final boolean equals(Object p_ob) {
    if (this == p_ob) {
      return true;
    }
    if (p_ob == null) {
      return false;
    }
    if (getClass() != p_ob.getClass()) {
      return false;
    }
    IntPoint other = (IntPoint) p_ob;
    return x == other.x && y == other.y;
  }

  @Override
  public boolean is_infinite() {
    return false;
  }

  @Override
  public IntBox surrounding_box() {
    return new IntBox(this, this);
  }

  @Override
  public IntOctagon surrounding_octagon() {
    int tmp_1 = x - y;
    int tmp_2 = x + y;

    return new IntOctagon(x, y, x, y, tmp_1, tmp_1, tmp_2, tmp_2);
  }

  @Override
  public boolean is_contained_in(IntBox p_box) {
    return x >= p_box.ll.x && y >= p_box.ll.y && x <= p_box.ur.x && y <= p_box.ur.y;
  }

  /**
   * returns the translation of this point by p_vector
   */
  @Override
  public final Point translate_by(Vector p_vector) {
    if (p_vector.equals(Vector.ZERO)) {
      return this;
    }
    return p_vector.add_to(this);
  }

  @Override
  Point translate_by(IntVector p_vector) {
    return new IntPoint(x + p_vector.x, y + p_vector.y);
  }

  @Override
  Point translate_by(RationalVector p_vector) {
    return p_vector.add_to(this);
  }

  /**
   * returns the difference vector of this point and p_other
   */
  @Override
  public Vector difference_by(Point p_other) {
    // US-3. The double-dispatch route allocates TWICE on the dominant path: p_other
    // .difference_by(this) builds an IntVector, and negate() builds a second one while the
    // first becomes garbage immediately. It was 4.2% of all allocation on bm01.
    //
    // IntVector.negate() is new IntVector(-x, -y), so -(other.x - x) is simply x - other.x:
    // the same value, one allocation. Overflow is not reachable here because the exactness
    // invariant caps coordinates at +/-2^25, so a difference cannot exceed 2^26 and cannot
    // touch the Integer.MIN_VALUE negation edge case.
    //
    // The general path is untouched: only the IntPoint-to-IntPoint case short-circuits,
    // everything else still dispatches as before.
    if (p_other instanceof IntPoint other) {
      return new IntVector(x - other.x, y - other.y);
    }
    Vector tmp = p_other.difference_by(this);
    return tmp.negate();
  }

  @Override
  Vector difference_by(RationalPoint p_other) {
    Vector tmp = p_other.difference_by(this);
    return tmp.negate();
  }

  @Override
  IntVector difference_by(IntPoint p_other) {
    return new IntVector(x - p_other.x, y - p_other.y);
  }

  @Override
  public Side side_of(Line p_line) {
    Vector v1 = difference_by(p_line.a);
    Vector v2 = p_line.b.difference_by(p_line.a);
    return v1.side_of(v2);
  }

  /**
   * converts this point to a FloatPoint.
   */
  @Override
  public FloatPoint to_float() {
    return new FloatPoint(x, y);
  }

  public int get_id_no() {
    return 31 * x + y;
  }

  /**
   * returns the determinant of the vectors (x, y) and (p_other.x, p_other.y)
   */
  public final long determinant(IntPoint p_other) {
    return (long) x * p_other.y - (long) y * p_other.x;
  }

  @Override
  public Point perpendicular_projection(Line p_line) {
    // this function is at the moment only implemented for lines
    // consisting of IntPoints.
    // The general implementation is still missing.
    IntVector v = (IntVector) p_line.b.difference_by(p_line.a);
    BigInteger vxvx = BigInteger.valueOf((long) v.x * v.x);
    BigInteger vyvy = BigInteger.valueOf((long) v.y * v.y);
    BigInteger vxvy = BigInteger.valueOf((long) v.x * v.y);
    BigInteger denominator = vxvx.add(vyvy);
    BigInteger det = BigInteger.valueOf(((IntPoint) p_line.a).determinant((IntPoint) p_line.b));
    BigInteger point_x = BigInteger.valueOf(x);
    BigInteger point_y = BigInteger.valueOf(y);

    BigInteger tmp1 = vxvx.multiply(point_x);
    BigInteger tmp2 = vxvy.multiply(point_y);
    tmp1 = tmp1.add(tmp2);
    tmp2 = det.multiply(BigInteger.valueOf(v.y));
    BigInteger proj_x = tmp1.add(tmp2);

    tmp1 = vxvy.multiply(point_x);
    tmp2 = vyvy.multiply(point_y);
    tmp1 = tmp1.add(tmp2);
    tmp2 = det.multiply(BigInteger.valueOf(v.x));
    BigInteger proj_y = tmp1.subtract(tmp2);

    int signum = denominator.signum();
    if (signum != 0) {
      if (signum < 0) {
        denominator = denominator.negate();
        proj_x = proj_x.negate();
        proj_y = proj_y.negate();
      }
      if (proj_x.mod(denominator).signum() == 0 && proj_y.mod(denominator).signum() == 0) {
        proj_x = proj_x.divide(denominator);
        proj_y = proj_y.divide(denominator);
        return new IntPoint(proj_x.intValue(), proj_y.intValue());
      }
    }
    return new RationalPoint(proj_x, proj_y, denominator);
  }

  /**
   * Returns the signed area of the parallelogramm spanned by the vectors p_2 - p_1 and this - p_1
   */
  public double signed_area(IntPoint p_1, IntPoint p_2) {
    IntVector d21 = p_2.difference_by(p_1);
    IntVector d01 = this.difference_by(p_1);
    return d21.determinant(d01);
  }

  /**
   * calculates the square of the distance between this point and p_to_point
   */
  public double distance_square(IntPoint p_to_point) {
    double dx = p_to_point.x - this.x;
    double dy = p_to_point.y - this.y;
    return dx * dx + dy * dy;
  }

  /**
   * calculates the distance between this point and p_to_point
   */
  public double distance(IntPoint p_to_point) {
    return Math.sqrt(distance_square(p_to_point));
  }

  /**
   * Calculates the nearest point to this point on the horizontal or vertical line through p_other (Snaps this point to on orthogonal line through p_other).
   */
  public IntPoint orthogonal_projection(IntPoint p_other) {
    IntPoint result;
    int horizontal_distance = Math.abs(this.x - p_other.x);
    int vertical_distance = Math.abs(this.y - p_other.y);
    if (horizontal_distance <= vertical_distance) {
      // projection onto the vertical line through p_other
      result = new IntPoint(p_other.x, this.y);
    } else {
      // projection onto the horizontal line through p_other
      result = new IntPoint(this.x, p_other.y);
    }
    return result;
  }

  /**
   * Calculates the nearest point to this point on an orthogonal or diagonal line through p_other (Snaps this point to on 45 degree line through p_other).
   */
  public IntPoint fortyfive_degree_projection(IntPoint p_other) {
    int dx = this.x - p_other.x;
    int dy = this.y - p_other.y;
    double[] dist_arr = new double[4];
    dist_arr[0] = Math.abs(dx);
    dist_arr[1] = Math.abs(dy);
    double diagonal_1 = ((double) dy - (double) dx) / 2;
    double diagonal_2 = ((double) dy + (double) dx) / 2;
    dist_arr[2] = Math.abs(diagonal_1);
    dist_arr[3] = Math.abs(diagonal_2);
    double min_dist = dist_arr[0];
    for (int i = 1; i < 4; i++) {
      if (dist_arr[i] < min_dist) {
        min_dist = dist_arr[i];
      }
    }
    IntPoint result;
    if (min_dist == dist_arr[0]) {
      // projection onto the vertical line through p_other
      result = new IntPoint(p_other.x, this.y);
    } else if (min_dist == dist_arr[1]) {
      // projection onto the horizontal line through p_other
      result = new IntPoint(this.x, p_other.y);
    } else if (min_dist == dist_arr[2]) {
      // projection onto the right diagonal line through p_other
      int diagonal_value = (int) diagonal_2;
      result = new IntPoint(p_other.x + diagonal_value, p_other.y + diagonal_value);
    } else {
      // projection onto the left diagonal line through p_other
      int diagonal_value = (int) diagonal_1;
      result = new IntPoint(p_other.x - diagonal_value, p_other.y + diagonal_value);
    }
    return result;
  }

  /**
   * Calculates a corner point p so that the lines through this point and p and from p to p_to_point are multiples of 45 degree, and that the angle at p will be 45 degree. If p_left_turn, p_to_point
   * will be on the left of the line from this point to p, else on the right. Returns null, if the line from this point to p_to_point is already a multiple of 45 degree.
   */
  public IntPoint fortyfive_degree_corner(IntPoint p_to_point, boolean p_left_turn) {
    int dx = p_to_point.x - this.x;
    int dy = p_to_point.y - this.y;
    IntPoint result;

    // handle the 8 sections between the 45 degree lines

    if (dy > 0 && dy < dx) {
      if (p_left_turn) {
        result = new IntPoint(p_to_point.x - dy, this.y);
      } else {
        result = new IntPoint(this.x + dy, p_to_point.y);
      }
    } else if (dx > 0 && dy > dx) {
      if (p_left_turn) {
        result = new IntPoint(p_to_point.x, this.y + dx);
      } else {
        result = new IntPoint(this.x, p_to_point.y - dx);
      }
    } else if (dx < 0 && dy > -dx) {
      if (p_left_turn) {
        result = new IntPoint(this.x, p_to_point.y + dx);
      } else {
        result = new IntPoint(p_to_point.x, this.y - dx);
      }
    } else if (dy > 0 && dy < -dx) {
      if (p_left_turn) {
        result = new IntPoint(this.x - dy, p_to_point.y);
      } else {
        result = new IntPoint(p_to_point.x + dy, this.y);
      }
    } else if (dy < 0 && dy > dx) {
      if (p_left_turn) {
        result = new IntPoint(p_to_point.x - dy, this.y);
      } else {
        result = new IntPoint(this.x + dy, p_to_point.y);
      }
    } else if (dx < 0 && dy < dx) {
      if (p_left_turn) {
        result = new IntPoint(p_to_point.x, this.y + dx);
      } else {
        result = new IntPoint(this.x, p_to_point.y - dx);
      }
    } else if (dx > 0 && dy < -dx) {
      if (p_left_turn) {
        result = new IntPoint(this.x, p_to_point.y + dx);
      } else {
        result = new IntPoint(p_to_point.x, this.y - dx);
      }
    } else if (dy < 0 && dy > -dx) {
      if (p_left_turn) {
        result = new IntPoint(this.x - dy, p_to_point.y);
      } else {
        result = new IntPoint(p_to_point.x + dy, this.y);
      }
    } else {
      // the line from this point to p_to_point is already a multiple of 45 degree
      result = null;
    }
    return result;
  }

  /**
   * Calculates a corner point p so that the lines through this point and p and from p to p_to_point are horizontal or vertical, and that the angle at p will be 90 degree. If p_left_turn, p_to_point
   * will be on the left of the line from this point to p, else on the right. Returns null, if the line from this point to p_to_point is already orthogonal.
   */
  public IntPoint ninety_degree_corner(IntPoint p_to_point, boolean p_left_turn) {
    int dx = p_to_point.x - this.x;
    int dy = p_to_point.y - this.y;
    IntPoint result;

    // handle the 4 quadrants

    if (dx > 0 && dy > 0 || dx < 0 && dy < 0) {
      if (p_left_turn) {
        result = new IntPoint(p_to_point.x, this.y);
      } else {
        result = new IntPoint(this.x, p_to_point.y);
      }
    } else if (dx < 0 && dy > 0 || dx > 0 && dy < 0) {
      if (p_left_turn) {
        result = new IntPoint(this.x, p_to_point.y);
      } else {
        result = new IntPoint(p_to_point.x, this.y);
      }
    } else {
      // the line from this point to p_to_point is already orthogonal
      result = null;
    }
    return result;
  }

  @Override
  public int compare_x(Point p_other) {
    return -p_other.compare_x(this);
  }

  @Override
  public int compare_y(Point p_other) {
    return -p_other.compare_y(this);
  }

  @Override
  int compare_x(IntPoint p_other) {
    int result;
    if (this.x > p_other.x) {
      result = 1;
    } else if (this.x == p_other.x) {
      result = 0;
    } else {
      result = -1;
    }
    return result;
  }

  @Override
  int compare_y(IntPoint p_other) {
    int result;
    if (this.y > p_other.y) {
      result = 1;
    } else if (this.y == p_other.y) {
      result = 0;
    } else {
      result = -1;
    }
    return result;
  }

  @Override
  int compare_x(RationalPoint p_other) {
    return -p_other.compare_x(this);
  }

  @Override
  int compare_y(RationalPoint p_other) {
    return -p_other.compare_y(this);
  }

  @Override
  public String toString() {
    return "(" + this.x + "," + this.y + ")";
  }
}