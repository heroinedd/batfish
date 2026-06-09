package org.batfish.main;

import com.google.common.graph.EndpointPair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.datamodel.BgpPeerConfigId;
import org.batfish.datamodel.Edge;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.dataplane.ibdp.BgpSession;
import org.batfish.dataplane.ibdp.IncrementalSimulator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a parsed Snowcap trace ({@link TraceParser}) into {@link IncrementalSimulator} calls.
 *
 * <p>Two session caches are built at construction time:
 *
 * <ul>
 *   <li>{@code initSessionCache} — from the initial topology, used to restore removed sessions
 *       (forward Remove, undo Insert) and to remove them (forward Remove, undo Insert).
 *   <li>{@code finalSessionCache} — from the final topology, used to add new sessions (forward
 *       Insert) and to remove them (undo Insert).
 * </ul>
 *
 * <p>Cache selection per action/direction:
 *
 * <pre>
 *   forward Insert(x)      → add    x  from finalCache
 *   forward Remove(x)      → remove x  from initCache
 *   forward Update(f→t)    → remove f (initCache), add t (finalCache)
 *   undo    Insert(x)      → remove x  from finalCache
 *   undo    Remove(x)      → add    x  from initCache
 *   undo    Update(f→t)    → remove t (finalCache), add f (initCache)
 * </pre>
 */
public class TraceExecutor {

  private static final Logger LOGGER = LogManager.getLogger(TraceExecutor.class);

  private final IncrementalSimulator simulator;
  private final Set<String> hostnames;

  /** sourceHostname → targetHostname → BgpSession (from initial topology) */
  private final Map<String, Map<String, BgpSession>> initSessionCache = new HashMap<>();

  /** sourceHostname → targetHostname → BgpSession (from final topology) */
  private final Map<String, Map<String, BgpSession>> finalSessionCache = new HashMap<>();

  /**
   * @param simulator wraps the initial-topology data plane being incrementally updated
   * @param initialBatfish used to look up configurations and the initial BGP topology
   * @param finalBgpTopology the target BGP topology; used to resolve {@code Insert} sessions
   */
  public TraceExecutor(
      IncrementalSimulator simulator, Batfish initialBatfish, BgpTopology finalBgpTopology) {
    this.simulator = simulator;
    this.hostnames = initialBatfish.loadConfigurations(initialBatfish.getSnapshot()).keySet();
    buildSessionCache(simulator.getBgpTopology(), initSessionCache);
    buildSessionCache(finalBgpTopology, finalSessionCache);
  }

  private static void buildSessionCache(
      BgpTopology bgpTopology, Map<String, Map<String, BgpSession>> cache) {
    for (EndpointPair<BgpPeerConfigId> edge : bgpTopology.getGraph().edges()) {
      BgpPeerConfigId src = edge.source();
      BgpPeerConfigId tgt = edge.target();
      BgpSession session =
          new BgpSession(src, tgt, bgpTopology.getGraph().edgeValue(src, tgt).orElse(null));
      cache
          .computeIfAbsent(src.getHostname(), k -> new HashMap<>())
          .put(tgt.getHostname(), session);
    }
  }

  /** Executes all steps in the trace against the simulator. */
  public void execute(List<TraceParser.Step> steps) {
    for (TraceParser.Step step : steps) {
      executeStep(step.actions, step.isUndo);
    }
  }

  private void executeStep(List<TraceAction> actions, boolean isUndo) {
    for (TraceAction action : actions) {
      LOGGER.info((isUndo ? "Undo " : "") + action.toString());
      if (action instanceof TraceAction.Remove) {
        TraceAction.ConfigExpr expr = ((TraceAction.Remove) action).expr;
        if (expr instanceof TraceAction.ConfigExpr.BgpSession) {
          TraceAction.ConfigExpr.BgpSession bgp = (TraceAction.ConfigExpr.BgpSession) expr;
          BgpSession fwd = lookupSession(initSessionCache, bgp.source, bgp.target);
          BgpSession rev = lookupSession(initSessionCache, bgp.target, bgp.source);
          if (!isUndo) {
            // Forward Remove: take both directions out of the network (look up init cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                new BgpSession(fwd.id1, fwd.id2, null), new BgpSession(rev.id1, rev.id2, null));
          } else {
            // Undo Remove: restore both directions (look up init cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(fwd, rev);
          }
        } else {
          LOGGER.warn("Skipping unsupported Remove expr: {}", expr.getClass().getSimpleName());
        }

      } else if (action instanceof TraceAction.Insert) {
        TraceAction.ConfigExpr expr = ((TraceAction.Insert) action).expr;
        if (expr instanceof TraceAction.ConfigExpr.BgpSession) {
          TraceAction.ConfigExpr.BgpSession bgp = (TraceAction.ConfigExpr.BgpSession) expr;
          BgpSession fwd = lookupSession(finalSessionCache, bgp.source, bgp.target);
          BgpSession rev = lookupSession(finalSessionCache, bgp.target, bgp.source);
          if (!isUndo) {
            // Forward Insert: add both directions (look up final cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(fwd, rev);
          } else {
            // Undo Insert: remove both directions (look up final cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                new BgpSession(fwd.id1, fwd.id2, null), new BgpSession(rev.id1, rev.id2, null));
          }
        } else {
          LOGGER.warn("Skipping unsupported Insert expr: {}", expr.getClass().getSimpleName());
        }

      } else if (action instanceof TraceAction.Update) {
        TraceAction.Update update = (TraceAction.Update) action;
        if (update.from instanceof TraceAction.ConfigExpr.BgpSession) {
          TraceAction.ConfigExpr.BgpSession fromBgp =
              (TraceAction.ConfigExpr.BgpSession) update.from;
          TraceAction.ConfigExpr.BgpSession toBgp = (TraceAction.ConfigExpr.BgpSession) update.to;
          BgpSession oldFwd = lookupSession(initSessionCache, fromBgp.source, fromBgp.target);
          BgpSession oldRev = lookupSession(initSessionCache, fromBgp.target, fromBgp.source);
          BgpSession newFwd = lookupSession(finalSessionCache, toBgp.source, toBgp.target);
          BgpSession newRev = lookupSession(finalSessionCache, toBgp.target, toBgp.source);
          if (!isUndo) {
            // Forward Update: remove old both directions (init), add new both directions (final)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                new BgpSession(oldFwd.id1, oldFwd.id2, null),
                new BgpSession(oldRev.id1, oldRev.id2, null),
                new BgpSession(newFwd.id1, newFwd.id2, newFwd.properties),
                new BgpSession(newRev.id1, newRev.id2, newRev.properties));
          } else {
            // Undo Update: remove new both directions (final), restore old both directions (init)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                new BgpSession(newFwd.id1, newFwd.id2, null),
                new BgpSession(newRev.id1, newRev.id2, null),
                new BgpSession(oldFwd.id1, oldFwd.id2, oldFwd.properties),
                new BgpSession(oldRev.id1, oldRev.id2, oldRev.properties));
          }
        } else if (update.from instanceof TraceAction.ConfigExpr.IgpLinkWeight) {
          TraceAction.ConfigExpr.IgpLinkWeight from =
              (TraceAction.ConfigExpr.IgpLinkWeight) update.from;
          TraceAction.ConfigExpr.IgpLinkWeight to =
              (TraceAction.ConfigExpr.IgpLinkWeight) update.to;
          // When undoing, the ratio is inverted: restore from by applying (from/to) ratio
          executeIgpUpdate(isUndo ? to : from, isUndo ? from : to);
        } else {
          LOGGER.warn(
              "Skipping unsupported Update expr: {}", update.from.getClass().getSimpleName());
        }
      }
    }
  }

  /**
   * Applies an IGP link weight update using a ratio: {@code newWeight = currentWeight * (to /
   * from)}. This handles cases where the absolute weight in the trace differs from the actual
   * current weight (e.g., after prior incremental changes).
   */
  private void executeIgpUpdate(
      TraceAction.ConfigExpr.IgpLinkWeight from, TraceAction.ConfigExpr.IgpLinkWeight to) {
    String srcHostname = toHostname(from.source);
    String tgtHostname = toHostname(from.target);
    for (Edge edge : simulator.getLayer3Topology().getEdges()) {
      if (edge.getNode1().equals(srcHostname) && edge.getNode2().equals(tgtHostname)) {
        int current = simulator.getOspfLinkWeight(edge);
        int updated = (int) (current * (to.weight / from.weight));
        simulator.modifyOspfLinkWeightAndSimulate(edge, updated);
        return;
      }
    }
    LOGGER.warn("No Layer3 edge found for IGP weight update: {} → {}", srcHostname, tgtHostname);
  }

  private BgpSession lookupSession(
      Map<String, Map<String, BgpSession>> cache, int sourceId, int targetId) {
    String src = toHostname(sourceId);
    String tgt = toHostname(targetId);
    Map<String, BgpSession> inner = cache.get(src);
    if (inner != null) {
      BgpSession session = inner.get(tgt);
      if (session != null) return session;
    }
    throw new IllegalStateException("No cached BGP session found for " + src + " → " + tgt);
  }

  private String toHostname(int id) {
    String internal = "r" + id;
    if (hostnames.contains(internal)) return internal;
    String external = "er" + id;
    if (hostnames.contains(external)) return external;
    throw new IllegalArgumentException("No router found for id " + id);
  }
}
