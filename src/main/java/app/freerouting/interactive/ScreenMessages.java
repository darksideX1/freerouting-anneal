package app.freerouting.interactive;

import app.freerouting.board.Unit;
import app.freerouting.core.RouterCounters;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.util.TextManager;
import java.text.NumberFormat;
import java.util.Locale;
import javax.swing.JLabel;

/**
 * Generate language-specific texts for fields at the bottom of the screen,
 * below the PCB frame.
 */
public class ScreenMessages {

  private static final String empty_string = "            ";
  final JLabel errorLabel;
  final JLabel warningLabel;
  private final String active_layer_string;
  private final String target_layer_string;
  /**
   * The number format for displaying the trace length
   */
  private final NumberFormat number_format;
  private final JLabel add_field;
  private final JLabel status_field;
  private final JLabel layer_field;
  private final JLabel score_field;
  private final JLabel mouse_position;
  private final JLabel unit_label;
  private final TextManager tm;
  private String prev_target_layer_name = empty_string;
  private boolean write_protected;

  /**
   * Creates a new instance of ScreenMessages
   */
  public ScreenMessages(JLabel errorLabel, JLabel warningLabel, JLabel p_status_field, JLabel p_add_field,
      JLabel p_layer_field, JLabel p_score_field, JLabel p_mouse_position, JLabel p_unit_label,
      Locale p_locale) {

    tm = new TextManager(this.getClass(), p_locale);
    active_layer_string = tm.getText("current_layer") + " ";
    target_layer_string = tm.getText("target_layer") + " ";
    this.errorLabel = errorLabel;
    this.warningLabel = warningLabel;
    status_field = p_status_field;
    add_field = p_add_field;
    layer_field = p_layer_field;
    score_field = p_score_field;
    mouse_position = p_mouse_position;
    unit_label = p_unit_label;
    add_field.setText(empty_string);

    this.number_format = NumberFormat.getInstance(p_locale);
    this.number_format.setMinimumFractionDigits(2);
    this.number_format.setMaximumFractionDigits(2);
  }

  public void set_error_and_warning_count(int errorsCount, int warningCount) {
    errorLabel.setText(Integer.toString(errorsCount));
    warningLabel.setText(Integer.toString(warningCount));
  }

  /**
   * Sets the message in the status field.
   */
  public void set_status_message(String p_message) {
    if (!this.write_protected) {
      status_field.setText(p_message);
    }
  }

  /**
   * Displays the latest traced operation in the footer.
   */
  public void set_trace_message(String operation, String message, String impactedItems) {
    if (this.write_protected) {
      return;
    }
    String statusText = (operation == null || operation.isEmpty()) ? message : operation + ": " + message;
    status_field.setText(statusText == null ? empty_string : statusText);
    String impactedText = (impactedItems == null || impactedItems.isEmpty()) ? empty_string : impactedItems;
    add_field.setText(impactedText);
  }

  /**
   * Sets the displayed layer number on the screen.
   */
  public void set_layer(String p_layer_name) {
    if (!this.write_protected) {
      layer_field.setText(active_layer_string + p_layer_name);
    }
  }

  public void set_interactive_autoroute_info(int p_found, int p_not_found, int p_items_to_go) {
    int found = p_found;
    int failed = p_not_found;
    int items_to_go = p_items_to_go;

    add_field.setText(tm.getText("interactive_autoroute_add", String.valueOf(items_to_go)));
    layer_field.setText(tm.getText("interactive_autoroute_layer", String.valueOf(found), String.valueOf(failed)));
  }

  /** The router's own counters, cached so the clock can be rendered beside them. */
  private String routerInfoText = empty_string;
  /** The wall clock, cached so a counter update does not erase it. */
  private String routingClockText = empty_string;
  /** Ticks the clock once a second while the router is working. */
  private javax.swing.Timer routingClockTimer;

  private void renderAddField() {
    if (routingClockText.isEmpty()) {
      add_field.setText(routerInfoText);
    } else if (routerInfoText.isEmpty()) {
      add_field.setText(routingClockText);
    } else {
      add_field.setText(routerInfoText + "   " + routingClockText);
    }
  }

  /**
   * Starts the elapsed/limit clock.
   *
   * <p>Ticks every second even when the router itself reports nothing for minutes at a
   * time -- which is the whole point. A fanout pass on a real board has run for 284
   * seconds without a single progress event, and during that silence the only honest
   * signal that anything is alive is the clock moving.
   *
   * @param limitSeconds the wall-clock limit, or {@code null} if none applies
   */
  public void start_routing_clock(Long limitSeconds) {
    stop_routing_clock();
    final long startedAtMillis = System.currentTimeMillis();
    routingClockText = app.freerouting.core.RoutingProgress.format(0, limitSeconds);
    renderAddField();
    routingClockTimer = new javax.swing.Timer(1000, _ -> {
      long elapsed = (System.currentTimeMillis() - startedAtMillis) / 1000L;
      routingClockText = app.freerouting.core.RoutingProgress.format(elapsed, limitSeconds);
      renderAddField();
    });
    routingClockTimer.start();
  }

  /** Stops the clock and removes it from the status bar. Safe to call when not running. */
  public void stop_routing_clock() {
    if (routingClockTimer != null) {
      routingClockTimer.stop();
      routingClockTimer = null;
    }
    routingClockText = empty_string;
    renderAddField();
  }

  public void set_batch_autoroute_info(RouterCounters routerCounters) {
    int items_to_go = routerCounters.queuedToBeRoutedCount;
    int routed = routerCounters.routedCount;
    int failed = routerCounters.failedToBeRoutedCount;
    if ("fanout".equals(routerCounters.phase)) {
      int extraVias = routerCounters.fanoutExtraViasCount == null ? 0 : routerCounters.fanoutExtraViasCount;
      routerInfoText = tm.getText("batch_autoroute_add", String.valueOf(items_to_go), String.valueOf(routed));
      renderAddField();
      layer_field.setText(tm.getText("batch_fanout_layer", String.valueOf(failed), String.valueOf(extraVias)));
      return;
    }
    int ripped = routerCounters.rippedCount;
    routerInfoText = tm.getText("batch_autoroute_add", String.valueOf(items_to_go), String.valueOf(routed));
    renderAddField();
    layer_field.setText(tm.getText("batch_autoroute_layer", String.valueOf(ripped), String.valueOf(failed)));
  }

  public void set_post_route_info(int p_via_count, double p_trace_length, Unit unit) {
    // Routing is finished by the time these figures exist, so this is the backstop that
    // guarantees the clock cannot keep ticking after the run has ended.
    stop_routing_clock();
    int via_count = p_via_count;
    routerInfoText = tm.getText("post_route_add", String.valueOf(via_count));
    add_field.setText(routerInfoText);
    layer_field.setText(tm.getText("post_route_layer", this.number_format.format(p_trace_length), unit.toString()));
  }

  /**
   * Sets the displayed layer of the nearest target item in interactive routing.
   */
  public void set_target_layer(String p_layer_name) {
    if (!(p_layer_name.equals(prev_target_layer_name) || this.write_protected)) {
      add_field.setText(target_layer_string + p_layer_name);
      prev_target_layer_name = p_layer_name;
    }
  }

  public void set_mouse_position(FloatPoint p_pos) {
    if (p_pos == null || this.mouse_position == null || this.write_protected) {
      return;
    }
    this.mouse_position.setText(p_pos.to_string(this.tm.getLocale(), 2, 10));
  }

  public void set_unit_label(String p_unit) {
    this.unit_label.setText(p_unit);
  }

  /**
   * Clears the additional field, which is among others used to display the layer
   * of the nearest target item.
   */
  public void clear_add_field() {
    if (!this.write_protected) {
      add_field.setText(empty_string);
      prev_target_layer_name = empty_string;
    }
  }

  /**
   * Clears the status field and the additional field.
   */
  public void clear() {
    if (!this.write_protected) {
      status_field.setText(empty_string);
      clear_add_field();
      layer_field.setText(empty_string);
      score_field.setText(empty_string);
    }
  }

  /**
   * As long as write_protected is set to true, the set functions in this class
   * will do nothing.
   */
  public void set_write_protected(boolean p_value) {
    write_protected = p_value;
  }

  public void set_board_score(float score, int unrouted_count, int violation_count) {
    score_field.setText(tm.getText("score", FRLogger.defaultFloatFormat.format(score), String.valueOf(unrouted_count),
        String.valueOf(violation_count)));
  }
}
