package org.batfish.main;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Parses ConPanna plan files (FM2RR scenarios) in two formats:
 *
 * <ul>
 *   <li><b>fd</b>: {@code {"plan": [str, ...]}} — each entry is a separate step
 *   <li><b>smt</b>: {@code {"0": [str, ...], "1": [str, ...], ...}} — each numbered group is a
 *       single step with multiple actions
 * </ul>
 *
 * <p>Each string entry is one of:
 *
 * <ul>
 *   <li>{@code [+-]BgpPeerConfig{r=rN, lIP=..., rIP=192.168.0.M, ..., rrClient=false|true}} —
 *       parsed as Insert or Remove of a BGP session
 *   <li>A group containing both {@code rrClient=false} and {@code rrClient=true} variants (e.g.
 *       {@code SymbolicConfigComponentGroup}) — parsed as Update from IBGP_PEER to IBGP_CLIENT
 * </ul>
 *
 * <p>The route-reflector ID is derived from the plan itself: it is the source router that appears
 * in all Update actions (the router making its peers into RR clients).
 */
public class ConPannaTraceParser implements TraceParser {

  // Matches a single BgpPeerConfig entry: extracts (op, srcId, tgtId, rrClient)
  private static final Pattern PEER_PATTERN =
      Pattern.compile(
          "([+-])BgpPeerConfig\\{r=r(\\d+), lIP=[\\d.]+, rIP=192\\.168\\.0\\.(\\d+),"
              + " lASN=\\d+, rASN=\\d+, rrClient=(true|false)\\}");

  // Matches the first "r=rN, lIP=..., rIP=192.168.0.M" inside any group string
  private static final Pattern GROUP_PEER_PATTERN =
      Pattern.compile("r=r(\\d+), lIP=[\\d.]+, rIP=192\\.168\\.0\\.(\\d+)");

  @Override
  public Pair<List<List<Integer>>, List<Step>> parse(Path path) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(path.toFile());

    List<List<TraceAction>> groups = new ArrayList<>();
    if (root.has("plan")) {
      // fd format: each entry is its own single-action step
      for (JsonNode entry : root.get("plan")) {
        TraceAction action = parseEntry(entry.asText());
        if (action != null) {
          groups.add(Collections.singletonList(action));
        }
      }
    } else {
      // smt format: integer-keyed groups, sorted numerically
      List<String> keys = new ArrayList<>();
      root.fieldNames().forEachRemaining(keys::add);
      keys.sort(Comparator.comparingInt(Integer::parseInt));
      for (String key : keys) {
        List<TraceAction> actions = new ArrayList<>();
        for (JsonNode entry : root.get(key)) {
          TraceAction action = parseEntry(entry.asText());
          if (action != null) {
            actions.add(action);
          }
        }
        if (!actions.isEmpty()) {
          groups.add(actions);
        }
      }
    }

    // All plan steps are forward (no undo), and assumed to succeed
    List<Step> steps = new ArrayList<>();
    for (List<TraceAction> actions : groups) {
      steps.add(new Step(actions, false, true));
    }

    int rrId = deriveRrId(steps);
    return Pair.of(Collections.singletonList(Collections.singletonList(rrId)), steps);
  }

  private static TraceAction parseEntry(String s) {
    // A group contains both rrClient=false and rrClient=true → Update(IBGP_PEER → IBGP_CLIENT)
    if (s.contains("rrClient=false") && s.contains("rrClient=true")) {
      Matcher m = GROUP_PEER_PATTERN.matcher(s);
      if (m.find()) {
        int src = Integer.parseInt(m.group(1));
        int tgt = Integer.parseInt(m.group(2));
        return new TraceAction.Update(
            new TraceAction.ConfigExpr.BgpSession(src, tgt, TraceAction.SessionType.IBGP_PEER),
            new TraceAction.ConfigExpr.BgpSession(src, tgt, TraceAction.SessionType.IBGP_CLIENT));
      }
    } else {
      // Single BgpPeerConfig entry → Insert or Remove
      Matcher m = PEER_PATTERN.matcher(s);
      if (m.find()) {
        char op = m.group(1).charAt(0);
        int src = Integer.parseInt(m.group(2));
        int tgt = Integer.parseInt(m.group(3));
        TraceAction.SessionType type =
            Boolean.parseBoolean(m.group(4))
                ? TraceAction.SessionType.IBGP_CLIENT
                : TraceAction.SessionType.IBGP_PEER;
        TraceAction.ConfigExpr.BgpSession bgp =
            new TraceAction.ConfigExpr.BgpSession(src, tgt, type);
        return op == '+' ? new TraceAction.Insert(bgp) : new TraceAction.Remove(bgp);
      }
    }
    return null;
  }

  /**
   * Derives the RR ID from Update actions: the source router that appears most frequently as the
   * local side of IBGP_CLIENT sessions is the route reflector.
   */
  private static int deriveRrId(List<Step> steps) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (Step step : steps) {
      for (TraceAction action : step.actions) {
        if (action instanceof TraceAction.Update u
            && u.to instanceof TraceAction.ConfigExpr.BgpSession bs
            && bs.sessionType == TraceAction.SessionType.IBGP_CLIENT) {
          counts.merge(bs.source, 1, Integer::sum);
        }
      }
    }
    return counts.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No IBGP_CLIENT sessions found in plan — cannot determine RR ID"))
        .getKey();
  }
}
