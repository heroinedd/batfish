package org.batfish.main;

import com.google.common.graph.EndpointPair;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.Logger;
import org.batfish.common.NetworkSnapshot;
import org.batfish.datamodel.*;
import org.batfish.datamodel.answers.ConvertConfigurationAnswerElement;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.LiteralLong;
import org.batfish.datamodel.routing_policy.statement.SetLocalPreference;
import org.batfish.datamodel.routing_policy.statement.Statements;
import org.batfish.dataplane.ibdp.BgpSession;
import org.batfish.dataplane.ibdp.IncrementalSimulator;
import org.batfish.identifiers.NetworkId;
import org.batfish.identifiers.SnapshotId;
import org.batfish.storage.StorageProvider;
import org.batfish.utils.BatfishUtil;
import org.batfish.utils.SmoothieLogger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

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
  private static final Logger LOGGER = SmoothieLogger.LOGGER;

  private final Path base;
  private final IncrementalSimulator simulator;

  private final Map<String, Configuration> initialConfigs;

  /** sourceHostname → targetHostname → BgpSession (from initial topology) */
  private final Map<String, Map<String, BgpSession>> initSessionCache = new HashMap<>();

  private final long initialTime;

  private final Map<String, Configuration> finalConfigs;

  /** sourceHostname → targetHostname → BgpSession (from final topology) */
  private final Map<String, Map<String, BgpSession>> finalSessionCache = new HashMap<>();

  private final long finalTime;

  public TraceExecutor(
      String name,
      Map<String, Configuration> initialCfgs,
      @Nullable Map<String, Configuration> finalCfgs) {
    initialConfigs = initialCfgs;
    finalConfigs = finalCfgs == null ? initialCfgs : finalCfgs;

    // combine routing policies here to avoid NullPointerException
    for (Map.Entry<String, Configuration> entry : initialConfigs.entrySet()) {
      Configuration initialC = entry.getValue();
      Configuration finalC = finalConfigs.get(entry.getKey());
      initialC.getRoutingPolicies().putAll(finalC.getRoutingPolicies());
    }

    Triple<Path, StorageProvider, Batfish> triple =
        BatfishUtil.getBatfishFromConfiguration(
            BatfishUtil.OUTPUT_BASE, name, "initial", new TreeMap<>(initialConfigs), null, false);
    base = triple.getLeft();
    Batfish batfish = triple.getRight();
    simulator = new IncrementalSimulator(batfish, triple.getMiddle());

    // simulate the initial control plane
    long start = System.nanoTime();
    simulator.computeInitialDataPlane();
    simulator.checkSafety(true);
    initialTime = System.nanoTime() - start;

    BgpTopology finalBgpTopology;
    if (finalCfgs == null) {
      finalBgpTopology = simulator.getBgpTopology();
      finalTime = 0;
    } else {
      // simulate the final control plane
      start = System.nanoTime();
      NetworkSnapshot finalSnapshot =
          new NetworkSnapshot(new NetworkId(name), new SnapshotId("final"));
      try {
        triple
            .getMiddle()
            .storeConfigurations(
                finalConfigs,
                new ConvertConfigurationAnswerElement(),
                null,
                finalSnapshot.getNetwork(),
                finalSnapshot.getSnapshot());
      } catch (IOException e) {
        LOGGER.error("Could not save final configurations for {}: {}", name, e.getMessage());
      }
      batfish.computeDataPlane(finalSnapshot);
      finalBgpTopology = batfish.getTopologyProvider().getBgpTopology(finalSnapshot);
      finalTime = System.nanoTime() - start;
    }

    buildSessionCache(initialConfigs, simulator.getBgpTopology(), initSessionCache);
    buildSessionCache(finalConfigs, finalBgpTopology, finalSessionCache);
  }

  private static void buildSessionCache(
      Map<String, Configuration> configurations,
      BgpTopology bgpTopology,
      Map<String, Map<String, BgpSession>> cache) {
    NetworkConfigurations nc = NetworkConfigurations.of(configurations);
    for (EndpointPair<BgpPeerConfigId> edge : bgpTopology.getGraph().edges()) {
      BgpPeerConfigId src = edge.source();
      BgpPeerConfigId tgt = edge.target();
      BgpSessionProperties properties = bgpTopology.getGraph().edgeValue(src, tgt).orElse(null);
      BgpPeerConfig cfg = nc.getBgpPeerConfig(src);
      BgpSession session = new BgpSession(src, tgt, properties, cfg);
      cache
          .computeIfAbsent(src.getHostname(), k -> new HashMap<>())
          .put(tgt.getHostname(), session);
    }
  }

  /** Executes all steps in the trace against the simulator. */
  public void execute(List<TraceParser.Step> steps) {
    for (TraceParser.Step step : steps) {
      executeStep(step.actions, step.isUndo, step.success);
    }
  }

  private void executeStep(List<TraceAction> actions, boolean isUndo, boolean expected) {
    for (TraceAction action : actions) {
      LOGGER.debug((isUndo ? "Undo " : "") + action.toString());
      if (action instanceof TraceAction.Remove) {
        TraceAction.ConfigExpr expr = ((TraceAction.Remove) action).expr;
        if (expr instanceof TraceAction.ConfigExpr.BgpSession bgp) {
          BgpSession fwd = lookupSession(initSessionCache, bgp.source, bgp.target);
          BgpSession rev = lookupSession(initSessionCache, bgp.target, bgp.source);
          if (!isUndo) {
            // Forward Remove: take both directions out of the network (look up init cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                expected,
                BgpSession.remove(fwd.localId(), fwd.remoteId()),
                BgpSession.remove(rev.localId(), rev.remoteId()));
          } else {
            // Undo Remove: restore both directions (look up init cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(expected, fwd, rev);
          }
        } else {
          LOGGER.warn("Skipping unsupported Remove expr: {}", expr.getClass().getSimpleName());
        }

      } else if (action instanceof TraceAction.Insert) {
        TraceAction.ConfigExpr expr = ((TraceAction.Insert) action).expr;
        if (expr instanceof TraceAction.ConfigExpr.BgpSession bgp) {
          BgpSession fwd = lookupSession(finalSessionCache, bgp.source, bgp.target);
          BgpSession rev = lookupSession(finalSessionCache, bgp.target, bgp.source);
          if (!isUndo) {
            // Forward Insert: add both directions (look up final cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(expected, fwd, rev);
          } else {
            // Undo Insert: remove both directions (look up final cache)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                expected,
                BgpSession.remove(fwd.localId(), fwd.remoteId()),
                BgpSession.remove(rev.localId(), rev.remoteId()));
          }
        } else if (expr instanceof TraceAction.ConfigExpr.BgpRouteMap rp) {
          RoutingPolicy setLocalPref =
              RoutingPolicy.builder()
                  .setName("set_local_pref_" + rp.localPref)
                  .addStatement(new SetLocalPreference(new LiteralLong(rp.localPref)))
                  .addStatement(Statements.ExitAccept.toStaticStatement())
                  .build();
          simulator.modifyRoutingPolicyAndSimulate(
              expected, toHostname(rp.router), toHostname(rp.neighbor), setLocalPref, rp.incoming);
        } else {
          LOGGER.warn("Skipping unsupported Insert expr: {}", expr.getClass().getSimpleName());
        }

      } else if (action instanceof TraceAction.Update update) {
        if (update.from instanceof TraceAction.ConfigExpr.BgpSession fromBgp) {
          TraceAction.ConfigExpr.BgpSession toBgp = (TraceAction.ConfigExpr.BgpSession) update.to;
          BgpSession oldFwd = lookupSession(initSessionCache, fromBgp.source, fromBgp.target);
          BgpSession oldRev = lookupSession(initSessionCache, fromBgp.target, fromBgp.source);
          BgpSession newFwd = lookupSession(finalSessionCache, toBgp.source, toBgp.target);
          BgpSession newRev = lookupSession(finalSessionCache, toBgp.target, toBgp.source);
          if (!isUndo) {
            // Forward Update: remove old both directions (init), add new both directions (final)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                null,
                BgpSession.remove(oldFwd.localId(), oldFwd.remoteId()),
                BgpSession.remove(oldRev.localId(), oldRev.remoteId()));
            simulator.insertOrRemoveBgpSessionAndSimulate(expected, newFwd, newRev);
          } else {
            // Undo Update: remove new both directions (final), restore old both directions (init)
            simulator.insertOrRemoveBgpSessionAndSimulate(
                null,
                BgpSession.remove(newFwd.localId(), newFwd.remoteId()),
                BgpSession.remove(newRev.localId(), newRev.remoteId()));
            simulator.insertOrRemoveBgpSessionAndSimulate(expected, oldFwd, oldRev);
          }
        } else if (update.from instanceof TraceAction.ConfigExpr.IgpLinkWeight from) {
          TraceAction.ConfigExpr.IgpLinkWeight to =
              (TraceAction.ConfigExpr.IgpLinkWeight) update.to;
          // When undoing, the ratio is inverted: restore from by applying (from/to) ratio
          executeIgpUpdate(isUndo ? to : from, isUndo ? from : to, expected);
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
      TraceAction.ConfigExpr.IgpLinkWeight from,
      TraceAction.ConfigExpr.IgpLinkWeight to,
      boolean expected) {
    String srcHostname = toHostname(from.source);
    String tgtHostname = toHostname(from.target);
    for (Edge edge : simulator.getLayer3Topology().getEdges()) {
      if (edge.getNode1().equals(srcHostname) && edge.getNode2().equals(tgtHostname)) {
        simulator.modifyOspfLinkWeightAndSimulate(expected, edge, from.weight, to.weight);
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
    String ir = "r" + id;
    if (initialConfigs.containsKey(ir) || finalConfigs.containsKey(ir)) return ir;
    String er = "er" + id;
    if (initialConfigs.containsKey(er) || finalConfigs.containsKey(er)) return er;
    throw new IllegalArgumentException("No router found for id " + id);
  }

  public long getInitialTime() {
    return initialTime;
  }

  public long getFinalTime() {
    return finalTime;
  }

  public long getCheckingTime() {
    return simulator.getCheckingTime();
  }

  public long getIoTime() {
    return simulator.getIoTime();
  }

  public void cleanOutput() {
    try {
      FileUtils.deleteDirectory(base.toFile());
    } catch (IOException e) {
      LOGGER.warn("Failed to clean output directory", e);
    }
  }
}
