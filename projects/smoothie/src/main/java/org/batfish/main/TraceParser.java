package org.batfish.main;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Parses a Snowcap trace file (JSON) into a list of {@link Step}s.
 *
 * <p>Each step corresponds to one {@code attempt} or {@code undo} event. The original actions are
 * preserved as-is; {@code isUndo} on the {@link Step} tells the executor to apply them in reverse
 * (i.e., Insert becomes a removal, Remove becomes a re-insertion). {@code solution} events are
 * skipped.
 */
public class TraceParser {

  /**
   * One atomic step in the trace: a list of actions, whether this step is an undo, and whether it
   * succeeded.
   */
  public static class Step {
    public final List<TraceAction> actions;
    public final boolean isUndo;
    public final boolean success;

    public Step(List<TraceAction> actions, boolean isUndo, boolean success) {
      this.actions = actions;
      this.isUndo = isUndo;
      this.success = success;
    }
  }

  /**
   * Parses the trace file at {@code path} and returns a pair of (reflector IDs, steps). {@code
   * solution} events are skipped.
   */
  public static Pair<List<Integer>, List<Step>> parse(Path path) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(path.toFile());

    List<Integer> reflectors = new ArrayList<>();
    for (JsonNode id : root.get("reflector")) {
      reflectors.add(id.asInt());
    }

    List<Step> steps = new ArrayList<>();
    for (JsonNode event : root.get("trace")) {
      String eventType = event.get("event").asText();
      switch (eventType) {
        case "attempt":
          {
            List<TraceAction> actions = new ArrayList<>();
            for (JsonNode actionNode : event.get("attempted")) {
              actions.add(parseAction(actionNode));
            }
            steps.add(new Step(actions, false, event.get("success").asBoolean()));
            break;
          }
        case "undo":
          {
            List<TraceAction> actions = new ArrayList<>();
            for (JsonNode actionNode : event.get("undone")) {
              actions.add(parseAction(actionNode));
            }
            steps.add(new Step(actions, true, true));
            break;
          }
        default:
          break; // skip "solution" and any unknown events
      }
    }
    return Pair.of(reflectors, steps);
  }

  private static TraceAction parseAction(JsonNode node) {
    String type = node.get("type").asText();
    switch (type) {
      case "insert":
        return new TraceAction.Insert(parseExpr(node.get("expr")));
      case "remove":
        return new TraceAction.Remove(parseExpr(node.get("expr")));
      case "update":
        return new TraceAction.Update(parseExpr(node.get("from")), parseExpr(node.get("to")));
      default:
        throw new IllegalArgumentException("Unknown TraceAction type: " + type);
    }
  }

  private static TraceAction.ConfigExpr parseExpr(JsonNode node) {
    String type = node.get("type").asText();
    switch (type) {
      case "igp_link_weight":
        return new TraceAction.ConfigExpr.IgpLinkWeight(
            node.get("source").asInt(),
            node.get("target").asInt(),
            node.get("weight").isNull() ? null : node.get("weight").asDouble());
      case "bgp_session":
        return new TraceAction.ConfigExpr.BgpSession(
            node.get("source").asInt(),
            node.get("target").asInt(),
            parseSessionType(node.get("session_type").asText()));
      case "bgp_route_map":
        return new TraceAction.ConfigExpr.BgpRouteMap(
            node.get("router").asInt(),
            node.get("direction").asText(),
            parse(node, "conds", "neighbor", "router"),
            parse(node, "set", "local_pref", "value"));
      case "static_route":
        return new TraceAction.ConfigExpr.StaticRoute(
            node.get("router").asInt(), node.get("prefix").asText(), node.get("target").asInt());
      default:
        throw new IllegalArgumentException("Unknown ConfigExpr type: " + type);
    }
  }

  private static int parse(JsonNode node, String k1, String k2, String k3) {
    return node.get("map")
        .get(k1)
        .valueStream()
        .filter(e -> e.get("type").asText().equals(k2))
        .findFirst()
        .map(e -> e.get(k3).asInt())
        .get();
  }

  private static TraceAction.SessionType parseSessionType(String s) {
    switch (s) {
      case "ibgp_peer":
        return TraceAction.SessionType.IBGP_PEER;
      case "ibgp_client":
        return TraceAction.SessionType.IBGP_CLIENT;
      case "ebgp":
        return TraceAction.SessionType.EBGP;
      default:
        throw new IllegalArgumentException("Unknown session_type: " + s);
    }
  }
}
