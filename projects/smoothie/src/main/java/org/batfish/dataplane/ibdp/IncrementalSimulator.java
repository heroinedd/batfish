package org.batfish.dataplane.ibdp;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import org.batfish.common.BatfishException;
import org.batfish.common.NetworkSnapshot;
import org.batfish.common.plugin.DataPlanePlugin;
import org.batfish.datamodel.*;
import org.batfish.datamodel.answers.ConvertConfigurationAnswerElement;
import org.batfish.datamodel.answers.IncrementalBdpAnswerElement;
import org.batfish.datamodel.bgp.AddressFamily;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.dataplane.ibdp.schedule.IbdpSchedule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.dataplane.rib.RibDelta;
import org.batfish.identifiers.SnapshotId;
import org.batfish.main.Batfish;
import org.batfish.storage.StorageProvider;

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

  public static boolean DEBUG_RIB_DIFF = false;
  public static boolean OPTIMIZE = true;

  Batfish batfish;
  StorageProvider storage;
  IncrementalBdpEngine engine;
  private final Set<PrefixSpace> prefixSpaces;

  IbdpResult initDataPlaneResult;
  private int idx = 0;
  NetworkSnapshot currSnapshot;
  IbdpResult currDataPlaneResult;
  LoopDetection detection;

  private long checkingTime = 0;
  private long ioTime = 0;

  public IncrementalSimulator(Batfish batfish, StorageProvider storage) {
    this.batfish = batfish;
    this.storage = storage;
    this.engine = ((IncrementalDataPlanePlugin) batfish.getDataPlanePlugin()).getEngine();
    this.prefixSpaces =
        batfish.loadConfigurations(batfish.getSnapshot()).values().stream()
            .flatMap(c -> c.getVrfs().values().stream())
            .filter(vr -> vr.getBgpProcess() != null)
            .map(vr -> vr.getBgpProcess().getOriginationSpace())
            .filter(ps -> !ps.isEmpty())
            .collect(Collectors.toSet());
    this.detection = new LoopDetection();
  }

  public void computeInitialDataPlane() {
    DataPlanePlugin.ComputeDataPlaneResult result =
        batfish.getDataPlanePlugin().computeDataPlane(batfish.getSnapshot());
    if (!(result instanceof IbdpResult)) {
      throw new BatfishException(
          "Initial data plane result is not an IbdpResult, cannot execute incremental simulation upon the returned result.");
    }
    initDataPlaneResult = (IbdpResult) result;
    currSnapshot = batfish.getSnapshot();
    currDataPlaneResult = initDataPlaneResult;
  }

  /** Incremental simulation after inserting or removing a BGP session. */
  public void insertOrRemoveBgpSessionAndSimulate(boolean expected, BgpSession... sessions) {
    SortedMap<String, Node> nodes = new TreeMap<>(currDataPlaneResult.getNodes());
    List<VirtualRouter> vrs =
        toListInRandomOrder(nodes.values().stream().flatMap(n -> n.getVirtualRouters().stream()));

    // step1: update the topology
    TopologyContext topologyContext = (TopologyContext) currDataPlaneResult._topologies;
    ValueGraph<BgpPeerConfigId, BgpSessionProperties> originalGraph =
        topologyContext.getBgpTopology().getGraph();
    // step1.1: collect edges
    Map<EndpointPair<BgpPeerConfigId>, BgpSessionProperties> edges =
        originalGraph.edges().stream()
            .collect(Collectors.toMap(edge -> edge, edge -> originalGraph.edgeValue(edge).get()));
    for (BgpSession session : sessions) {
      EndpointPair<BgpPeerConfigId> e = EndpointPair.ordered(session.id1, session.id2);
      if (session.properties == null) edges.remove(e);
      else edges.put(e, session.properties);
    }
    // step1.2: build new graph
    MutableValueGraph<BgpPeerConfigId, BgpSessionProperties> newGraph =
        ValueGraphBuilder.directed().allowsSelfLoops(false).build();
    edges.forEach(newGraph::putEdgeValue);
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
    checkSafety(expected);
  }

  /** Incremental simulation after modifying the OSPF link weight. */
  public void modifyOspfLinkWeightAndSimulate(
      boolean expected, Edge edge, Double oldWeight, Double newWeight) {
    SortedMap<String, Node> nodes = new TreeMap<>(currDataPlaneResult.getNodes());
    List<VirtualRouter> vrs =
        toListInRandomOrder(nodes.values().stream().flatMap(n -> n.getVirtualRouters().stream()));

    // step1: update the interface ospf cost
    Interface iface =
        nodes.get(edge.getNode1()).getConfiguration().getAllInterfaces().get(edge.getInt1());
    modifyOspfLinkWeight(iface, oldWeight, newWeight);
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
            vr ->
                vr.getMainRib().getRoutes().stream()
                    .filter(route -> route.getRoute() instanceof OspfRoute)
                    .forEach(route -> builders.get(vr).add(route)));

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
    checkSafety(expected);
  }

  /**
   * Incremental simulation after modifying the {@code node1}'s incoming / outgoing routing policy
   * from / to {@code node2}.
   */
  public void modifyRoutingPolicyAndSimulate(
      boolean expected, String r1, String r2, RoutingPolicy newPolicy, boolean incoming) {
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
    checkSafety(expected);
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

    // initialize a new snapshot
    NetworkSnapshot newSnapshot =
        new NetworkSnapshot(currSnapshot.getNetwork(), new SnapshotId("step" + idx++));
    if (!OPTIMIZE) {
      // store configurations and data plane into the new snapshot
      long start = System.nanoTime();
      try {
        storage.storeConfigurations(
            getConfigs(nodes),
            new ConvertConfigurationAnswerElement(),
            null,
            newSnapshot.getNetwork(),
            newSnapshot.getSnapshot());
      } catch (Exception e) {
        LOGGER.error(e.getMessage());
      }
      batfish.saveDataPlane(newSnapshot, incrementalDataPlane, topologyContext);
      ioTime += System.nanoTime() - start;
    }

    // debug rib diffs
    if (DEBUG_RIB_DIFF) {
      diffMainRibs((IncrementalDataPlane) currDataPlaneResult._dataPlane, incrementalDataPlane);
    }

    // update current data plane
    currSnapshot = newSnapshot;
    currDataPlaneResult =
        new IbdpResult(
            currDataPlaneResult._answerElement,
            incrementalDataPlane,
            topologyContext,
            nodes,
            currDataPlaneResult.getIpOwners());
  }

  public void checkSafety(boolean expected) {
    long start = System.nanoTime();
    // check control plane reachability
    boolean cp =
        currDataPlaneResult._dataPlane.getRibs().values().stream()
            .allMatch(
                rib ->
                    prefixSpaces.stream()
                        .allMatch(
                            ps ->
                                rib.getRoutes().stream()
                                    .anyMatch(route -> ps.containsPrefix(route.getNetwork()))));

    // detect data plane forwarding loop
    Set<Flow> loopFlows =
        OPTIMIZE
            ? detection.bddLoopDetection(
                batfish,
                currDataPlaneResult._dataPlane,
                getConfigs(currDataPlaneResult.getNodes()),
                currDataPlaneResult.getIpOwners())
            : batfish.bddLoopDetection(currSnapshot);
    boolean dp = loopFlows.isEmpty();

    if ((cp && dp) != expected) {
      System.err.printf(
          "unexpected property checking result, expected %s, got %s\n", expected, (cp & dp));
      if (cp != expected) {
        for (Table.Cell<String, String, FinalMainRib> cell :
            currDataPlaneResult._dataPlane.getRibs().cellSet()) {
          for (PrefixSpace ps : prefixSpaces) {
            boolean flag =
                cell.getValue().getRoutes().stream()
                    .anyMatch(route -> ps.containsPrefix(route.getNetwork()));
            if (!flag) {
              System.err.printf(
                  "Vrf(%s, %s) does not have route for %s\n",
                  cell.getRowKey(), cell.getColumnKey(), ps);
            }
          }
        }
      }
      if (dp != expected) {
        loopFlows.forEach(System.out::println);
      }
    }
    checkingTime += System.nanoTime() - start;
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

  public void modifyOspfLinkWeight(Interface iface, Double oldWeight, Double newWeight) {
    int oldW = iface.getOspfSettings().getCost();
    int newW = oldWeight == null ? newWeight.intValue() : (int) (oldW * newWeight / oldWeight);
    iface.getOspfSettings().setCost(newW);
    LOGGER.error("modify OSPF link weight from {} to {}", oldW, newW);
  }

  public Topology getLayer3Topology() {
    return initDataPlaneResult._topologies.getLayer3Topology();
  }

  public BgpTopology getBgpTopology() {
    return initDataPlaneResult._topologies.getBgpTopology();
  }

  private static Map<String, Configuration> getConfigs(Map<String, Node> nodes) {
    return nodes.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getConfiguration()));
  }

  public long getCheckingTime() {
    return checkingTime;
  }

  public long getIoTime() {
    return ioTime;
  }
}
