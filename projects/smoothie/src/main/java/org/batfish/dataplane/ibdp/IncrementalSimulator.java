package org.batfish.dataplane.ibdp;

import com.google.common.collect.ImmutableMap;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.DataPlanePlugin;
import org.batfish.datamodel.*;
import org.batfish.datamodel.answers.IncrementalBdpAnswerElement;
import org.batfish.datamodel.bgp.AddressFamily;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.dataplane.ibdp.schedule.IbdpSchedule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.dataplane.rib.RibDelta;
import org.batfish.main.Batfish;

import java.util.*;
import java.util.stream.Collectors;

import static org.batfish.common.util.StreamUtil.toListInRandomOrder;
import static org.batfish.dataplane.ibdp.IncrementalBdpEngine.*;

/**
 * This is an ad hoc incremental version for Batfish, supporting only (1) insert or remove of BGP
 * sessions, and (2) change of OSPF link weight.
 */
public class IncrementalSimulator {
  private static final Logger LOGGER = LogManager.getLogger(IncrementalSimulator.class);

  public static boolean DEBUG_RIB_DIFF = true;

  Batfish batfish;
  IncrementalBdpEngine engine;
  IbdpResult initDataPlaneResult;
  IbdpResult currDataPlaneResult;

  public IncrementalSimulator(Batfish batfish) {
    this.batfish = batfish;
    this.engine = ((IncrementalDataPlanePlugin) batfish.getDataPlanePlugin()).getEngine();
  }

  public void computeInitialDataPlane() {
    DataPlanePlugin.ComputeDataPlaneResult result =
        batfish.getDataPlanePlugin().computeDataPlane(batfish.getSnapshot());
    if (!(result instanceof IbdpResult)) {
      throw new BatfishException(
          "Initial data plane result is not an IbdpResult, cannot execute incremental simulation upon the returned result.");
    }
    initDataPlaneResult = (IbdpResult) result;
    currDataPlaneResult = initDataPlaneResult;
  }

  /** Incremental simulation after inserting or removing a BGP session. */
  public void insertOrRemoveBgpSessionAndSimulate(BgpSession... sessions) {
    SortedMap<String, Node> nodes = new TreeMap<>(currDataPlaneResult.getNodes());
    List<VirtualRouter> vrs =
        toListInRandomOrder(nodes.values().stream().flatMap(n -> n.getVirtualRouters().stream()));

    // step1: update the topology
    TopologyContext topologyContext = (TopologyContext) currDataPlaneResult._topologies;
    ValueGraph<BgpPeerConfigId, BgpSessionProperties> originalGraph =
        topologyContext.getBgpTopology().getGraph();
    MutableValueGraph<BgpPeerConfigId, BgpSessionProperties> newGraph =
        ValueGraphBuilder.directed().allowsSelfLoops(false).build();
    originalGraph
        .edges()
        .forEach(edge -> newGraph.putEdgeValue(edge, originalGraph.edgeValue(edge).get()));
    for (BgpSession session : sessions) {
      if (session.properties == null) newGraph.removeEdge(session.id1, session.id2);
      else newGraph.putEdgeValue(session.id1, session.id2, session.properties);
    }
    TopologyContext updatedTopologyContext =
        topologyContext.toBuilder().setBgpTopology(new BgpTopology(newGraph)).build();

    // step2: let each bgp process update topology to identify inserted or removed sessions
    vrs.parallelStream()
        .filter(vr -> vr._bgpRoutingProcess != null)
        .forEach(
            vr -> vr._bgpRoutingProcess.updateTopology(updatedTopologyContext.getBgpTopology()));

    // step3: incremental BGP simulation
    simulateBgp(nodes, vrs, updatedTopologyContext);

    // step4: update the current data plane
    updateCurrentDataplane(nodes, vrs, updatedTopologyContext);
  }

  /** Incremental simulation after modifying the OSPF link weight. */
  public void modifyOspfLinkWeightAndSimulate(Edge edge, int weight) {
    SortedMap<String, Node> nodes = new TreeMap<>(currDataPlaneResult.getNodes());
    List<VirtualRouter> vrs =
        toListInRandomOrder(nodes.values().stream().flatMap(n -> n.getVirtualRouters().stream()));

    // step1: update the interface ospf cost
    Objects.requireNonNull(
            nodes
                .get(edge.getNode1())
                .getConfiguration()
                .getAllInterfaces()
                .get(edge.getInt1())
                .getOspfSettings())
        .setCost(weight);
    TopologyContext topologyContext = (TopologyContext) currDataPlaneResult._topologies;

    // step2: remove all OSPF routes and re-simulation of OSPF
    Map<VirtualRouter, RibDelta.Builder<AnnotatedRoute<AbstractRoute>>> builders =
        vrs.stream().collect(Collectors.toMap(vr -> vr, vr -> RibDelta.builder()));
    vrs.parallelStream()
        .forEach(
            vr -> {
              Set<AnnotatedRoute<AbstractRoute>> routes =
                  vr.getMainRib().getRoutes().stream()
                      .filter(route -> route.getRoute() instanceof OspfRoute)
                      .collect(Collectors.toSet());
              routes.forEach(
                  route -> builders.get(vr).from(vr.getMainRib().removeRouteGetDelta(route)));
            });
    engine.computeIgpDataPlane(nodes, vrs, topologyContext, new IncrementalBdpAnswerElement());
    vrs.parallelStream()
        .forEach(
            vr -> {
              vr.getMainRib().getRoutes().stream()
                  .filter(route -> route.getRoute() instanceof OspfRoute)
                  .forEach(route -> builders.get(vr).add(route));
            });

    Map<VirtualRouter, RibDelta<AnnotatedRoute<AbstractRoute>>> deltas =
        builders.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().build()));

    // step3: update BGP route resolution
    vrs.stream()
        .filter(vr -> vr._bgpRoutingProcess != null)
        .filter(vr -> !deltas.get(vr).isEmpty())
        .forEach(
            vr -> {
              BgpRoutingProcess bgp = vr.getBgpRoutingProcess();
              bgp.updateResolvableRoutes(deltas.get(vr));
              Set<Bgpv4Route> routes = bgp._ibgpv4Rib.getBestPathRoutes();
              // re-insert the previous BGP best routes to find out best route changes
              routes.forEach(
                  route -> {
                    bgp.processMergeOrRemoveInEbgpOrIbgpRib(route, false, false);
                    bgp.processMergeOrRemoveInBgpRib(route, false);
                    bgp.processMergeOrRemoveInEbgpOrIbgpRib(route, false, true);
                    bgp.processMergeOrRemoveInBgpRib(route, true);
                  });
            });

    // step4: incremental BGP simulation
    simulateBgp(nodes, vrs, topologyContext);

    // step4: update the current data plane
    updateCurrentDataplane(nodes, vrs, topologyContext);
  }

  /**
   * Incremental simulation after modifying the {@code node1}'s incoming / outgoing routing policy
   * from / to {@code node2}.
   */
  public void modifyRoutingPolicyAndSimulate(
      String r1, String r2, RoutingPolicy newPolicy, boolean incoming) {
    String receiver = incoming ? r1 : r2;
    String sender = incoming ? r2 : r1;

    SortedMap<String, Node> nodes = new TreeMap<>(currDataPlaneResult.getNodes());
    List<VirtualRouter> vrs =
        toListInRandomOrder(nodes.values().stream().flatMap(n -> n.getVirtualRouters().stream()));

    NetworkConfigurations nc =
        NetworkConfigurations.of(
            nodes.entrySet().stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        Map.Entry::getKey, e -> e.getValue().getConfiguration())));
    TopologyContext topologyContext =
        ((TopologyContext) currDataPlaneResult._topologies).toBuilder().build();

    // step1: find out the impacted bgp edge
    EndpointPair<BgpPeerConfigId> edge =
        topologyContext.getBgpTopology().getGraph().edges().stream()
            .filter(
                e ->
                    e.source().getHostname().equals(sender)
                        && e.target().getHostname().equals(receiver))
            .findFirst()
            .get();

    // step2: update the routing policy in Node
    Configuration c1 = nodes.get(r1).getConfiguration();
    if (incoming) {
      // changing node1's (receiver) import policy from node2
      String policyName =
          nc.getBgpPeerConfig(edge.target())
              .getAddressFamily(AddressFamily.Type.IPV4_UNICAST)
              .getImportPolicy();
      c1.getRoutingPolicies().get(policyName).setStatements(newPolicy.getStatements());
    } else {
      // changing node1's (sender) export policy to node2
      String policyName =
          nc.getBgpPeerConfig(edge.source())
              .getAddressFamily(AddressFamily.Type.IPV4_UNICAST)
              .getExportPolicy();
      // todo the following logic may contain bugs
      RoutingPolicy oldPolicy = c1.getRoutingPolicies().get(policyName);
      List<Statement> oldStatements = oldPolicy.getStatements();
      oldStatements.addAll(newPolicy.getStatements());
      oldPolicy.setStatements(newPolicy.getStatements());
    }

    // step3: let the neighbor resend all bgp routes
    BgpRoutingProcess bgp =
        nodes.get(receiver).getVirtualRouter("default").get().getBgpRoutingProcess();
    BgpTopology.EdgeId eId = new BgpTopology.EdgeId(edge.source(), edge.target());
    bgp.removeRoutesFromSession(eId);
    bgp.pullV4UnicastMessages(topologyContext.getBgpTopology(), nc, nodes, eId, true);

    // step4: incremental BGP simulation
    simulateBgp(nodes, vrs, topologyContext);

    // step5: update the current data plane
    updateCurrentDataplane(nodes, vrs, topologyContext);
  }

  private void simulateBgp(
      SortedMap<String, Node> nodes, List<VirtualRouter> vrs, TopologyContext topologyContext) {
    int numIterations = 0;
    do {
      numIterations++;
      IbdpSchedule schedule =
          IbdpSchedule.getSchedule(
              engine._settings, engine._settings.getScheduleName(), nodes, topologyContext);
      vrs.parallelStream().forEach(VirtualRouter::reinitForNewIteration);

      int nodeSet = 0;
      while (schedule.hasNext()) {
        Map<String, Node> iterationNodes = schedule.next();
        List<VirtualRouter> iterationVrs =
            toListInRandomOrder(
                iterationNodes.values().stream().flatMap(n -> n.getVirtualRouters().stream()));
        String iterationLabel = String.format("Iteration %d Schedule %d", numIterations, nodeSet);
        computeIterationOfBgpRoutes(iterationLabel, nodes, iterationVrs);
        iterationVrs.parallelStream().forEach(VirtualRouter::endOfEgpInnerRound);
        ++nodeSet;
      }
    } while (vrs.parallelStream().anyMatch(VirtualRouter::isDirty));
  }

  private void updateCurrentDataplane(
      SortedMap<String, Node> nodes, List<VirtualRouter> vrs, TopologyContext topologyContext) {
    PartialDataplane partialDataplane =
        engine.nextDataplane(topologyContext, nodes, vrs, currDataPlaneResult.getIpOwners());
    IncrementalDataPlane incrementalDataPlane =
        IncrementalDataPlane.builder()
            .setNodes(nodes)
            .setPartialDataplane(partialDataplane)
            .build();
    // batfish.saveDataPlane(batfish.getSnapshot(), incrementalDataPlane, topologyContext);
    if (DEBUG_RIB_DIFF) {
      diffMainRibs((IncrementalDataPlane) currDataPlaneResult._dataPlane, incrementalDataPlane);
    }
    currDataPlaneResult =
        new IbdpResult(
            currDataPlaneResult._answerElement,
            incrementalDataPlane,
            topologyContext,
            nodes,
            currDataPlaneResult.getIpOwners());
  }

  private void diffMainRibs(IncrementalDataPlane prev, IncrementalDataPlane next) {
    boolean[] identical = {true};
    next.getRibs()
        .cellSet()
        .forEach(
            cell -> {
              String hostname = cell.getRowKey();
              String vrfName = cell.getColumnKey();
              FinalMainRib nextRib = cell.getValue();
              FinalMainRib prevRib = prev.getRibs().get(hostname, vrfName);

              Set<AbstractRoute> nextRoutes = nextRib.getRoutes();
              Set<AbstractRoute> prevRoutes =
                  prevRib == null ? Collections.emptySet() : prevRib.getRoutes();

              Set<AbstractRoute> added =
                  nextRoutes.stream()
                      .filter(r -> !prevRoutes.contains(r))
                      .collect(Collectors.toCollection(HashSet::new));
              Set<AbstractRoute> removed =
                  prevRoutes.stream()
                      .filter(r -> !nextRoutes.contains(r))
                      .collect(Collectors.toCollection(HashSet::new));

              if (!added.isEmpty() || !removed.isEmpty()) {
                LOGGER.info("[RIB diff] {}/{}", hostname, vrfName);
                added.forEach(r -> LOGGER.info("  + {}", r));
                removed.forEach(r -> LOGGER.info("  - {}", r));
                identical[0] = false;
              }
            });
    if (identical[0]) LOGGER.info("[RIB diff] none");
  }

  public int getOspfLinkWeight(Edge edge) {
    return initDataPlaneResult
        .getNodes()
        .get(edge.getNode1())
        .getConfiguration()
        .getAllInterfaces()
        .get(edge.getInt1())
        .getOspfSettings()
        .getCost();
  }

  public Topology getLayer3Topology() {
    return initDataPlaneResult._topologies.getLayer3Topology();
  }

  public BgpTopology getBgpTopology() {
    return initDataPlaneResult._topologies.getBgpTopology();
  }
}
