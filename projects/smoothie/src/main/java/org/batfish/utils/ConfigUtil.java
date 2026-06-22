package org.batfish.utils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.commons.lang3.tuple.Pair;
import org.batfish.datamodel.*;
import org.batfish.datamodel.bgp.Ipv4UnicastAddressFamily;
import org.batfish.datamodel.bgp.LocalOriginationTypeTieBreaker;
import org.batfish.datamodel.bgp.NextHopIpTieBreaker;
import org.batfish.datamodel.ospf.*;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.*;
import org.batfish.datamodel.routing_policy.statement.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.batfish.datamodel.Names.*;
import static org.batfish.datamodel.routing_policy.Common.suppressSummarizedPrefixes;

public class ConfigUtil {
  public static final String CISCO_LOOPBACK = "Loopback0";
  public static final String CISCO_ETH = "Ethernet";
  public static final String CISCO_NULL = "Null";
  public static final int CISCO_DEFAULT_LOCAL_BGP_WEIGHT = 32768;
  public static final String OSPF_PROCESS_NAME = "1";
  public static final long OSPF_AREA = 0L;

  public static Configuration router(String routerName, String loopbackIp) {
    Configuration c =
        Configuration.builder()
            .setHostname(routerName)
            .setConfigurationFormat(ConfigurationFormat.CISCO_IOS)
            .build();
    c.setExportBgpFromBgpRib(true);
    Vrf.builder().setOwner(c).setName(Configuration.DEFAULT_VRF_NAME).build();
    Interface.builder()
        .setName(CISCO_LOOPBACK)
        .setAddress(ConcreteInterfaceAddress.parse(loopbackIp))
        .setVrf(c.getDefaultVrf())
        .setOwner(c)
        .build();
    Interface.builder().setName(CISCO_NULL).setVrf(c.getDefaultVrf()).setOwner(c).build();
    return c;
  }

  public static Pair<Interface, Interface> edge(
      Configuration c1, Configuration c2, String ifaceName1, String ifaceName2, String subnet) {
    Interface iface1 =
        Interface.builder()
            .setName(ifaceName1)
            .setAddress(ConcreteInterfaceAddress.parse(subnet + ".0/31"))
            .setVrf(c1.getDefaultVrf())
            .setOwner(c1)
            .setDescription("TO " + c2.getHostname())
            .build();
    Interface iface2 =
        Interface.builder()
            .setName(ifaceName2)
            .setAddress(ConcreteInterfaceAddress.parse(subnet + ".1/31"))
            .setVrf(c2.getDefaultVrf())
            .setOwner(c2)
            .setDescription("TO " + c1.getHostname())
            .build();
    return Pair.of(iface1, iface2);
  }

  /** Set up ospf process for a router configuration */
  public static OspfProcess ospfProcess(Configuration c) {
    String hostname = c.getHostname();
    String vrfName = c.getDefaultVrf().getName();

    // set ospf interface setting for all ethernet and loopback interfaces
    c.getAllInterfaces()
        .values()
        .forEach(
            iface ->
                iface.setOspfSettings(
                    OspfInterfaceSettings.builder()
                        .setProcess(OSPF_PROCESS_NAME)
                        .setEnabled(true)
                        .setPassive(iface.getName().contains(CISCO_LOOPBACK))
                        .setCost(1)
                        .setAreaName(OSPF_AREA)
                        .build()));
    // initialize the ospf area
    OspfArea area =
        OspfArea.builder()
            .setNumber(0)
            .setInterfaces(ImmutableSet.copyOf(c.getAllInterfaces().keySet()))
            .build();
    // ospf neighbor configs
    Map<OspfNeighborConfigId, OspfNeighborConfig> neighborConfigs =
        c.getAllInterfaces().values().stream()
            .filter(iface -> iface.getName().contains(CISCO_ETH))
            .collect(
                Collectors.toMap(
                    iface ->
                        new OspfNeighborConfigId(
                            hostname,
                            vrfName,
                            OSPF_PROCESS_NAME,
                            iface.getName(),
                            (ConcreteInterfaceAddress) iface.getAddress()),
                    iface ->
                        OspfNeighborConfig.builder()
                            .setHostname(hostname)
                            .setVrfName(vrfName)
                            .setInterfaceName(iface.getName())
                            .setIp(((ConcreteInterfaceAddress) iface.getAddress()).getIp())
                            .setArea(OSPF_AREA)
                            .build()));
    // build the ospf process
    return OspfProcess.builder()
        .setProcessId(OSPF_PROCESS_NAME)
        .setVrf(c.getDefaultVrf())
        .setRouterId(getInterfaceIp(c, CISCO_LOOPBACK))
        .setReferenceBandwidth(10e9)
        .setAreas(ImmutableSortedMap.of(OSPF_AREA, area))
        .setNeighborConfigs(neighborConfigs)
        .build();
  }

  /** Set up bgp process for a router configuration */
  public static BgpProcess bgpProcess(Configuration c, Set<Prefix> networks) {
    BgpProcess proc =
        BgpProcess.builder()
            .setEbgpAdminCost(100)
            .setIbgpAdminCost(100)
            .setLocalAdminCost(100)
            .setVrf(c.getDefaultVrf())
            .setRouterId(getInterfaceIp(c, CISCO_LOOPBACK))
            .setLocalOriginationTypeTieBreaker(LocalOriginationTypeTieBreaker.NO_PREFERENCE)
            .setNetworkNextHopIpTieBreaker(NextHopIpTieBreaker.HIGHEST_NEXT_HOP_IP)
            .setRedistributeNextHopIpTieBreaker(NextHopIpTieBreaker.HIGHEST_NEXT_HOP_IP)
            .build();
    bgpCommonExportPolicy(c);

    // announce prefixes
    networks.forEach(p -> bgpNetwork(c, p));
    bgpRedistributionPolicy(c);

    // set tie-breaker to router-id for deterministic simulation result;
    // use PATH_LENGTH multipath mode so routes with same-length but different AS paths still
    // fall through to the ROUTER_ID tiebreaker (EXACT_PATH would drop any route arriving second)
    proc.setTieBreaker(BgpTieBreaker.ROUTER_ID);
    proc.setMultipathEquivalentAsPathMatchMode(MultipathEquivalentAsPathMatchMode.PATH_LENGTH);

    return proc;
  }

  /**
   * Configure a bgp session between {@code c1} and {@code c2}.
   *
   * @param client whether {@code c2} is the route reflector client of {@code c2}.
   */
  public static void iBgpSession(Configuration c1, Configuration c2, long asn, boolean client) {
    Ip loopback1 = getInterfaceIp(c1, CISCO_LOOPBACK);
    Ip loopback2 = getInterfaceIp(c2, CISCO_LOOPBACK);
    BgpActivePeerConfig peer12 =
        BgpActivePeerConfig.builder()
            .setLocalAs(asn)
            .setLocalIp(loopback1)
            .setRemoteAs(asn)
            .setPeerAddress(loopback2)
            .setIpv4UnicastAddressFamily(
                Ipv4UnicastAddressFamily.builder()
                    .setRouteReflectorClient(client)
                    .setExportPolicy(
                        generatedBgpPeerExportPolicyName(
                            c1.getDefaultVrf().getName(), loopback2.toString()))
                    .build())
            .build();
    BgpActivePeerConfig peer21 =
        BgpActivePeerConfig.builder()
            .setLocalAs(asn)
            .setLocalIp(loopback2)
            .setRemoteAs(asn)
            .setPeerAddress(loopback1)
            .setIpv4UnicastAddressFamily(
                Ipv4UnicastAddressFamily.builder()
                    .setExportPolicy(
                        generatedBgpPeerExportPolicyName(
                            c1.getDefaultVrf().getName(), loopback1.toString()))
                    .build())
            .build();
    c1.getDefaultVrf().getBgpProcess().getActiveNeighbors().put(loopback2, peer12);
    c2.getDefaultVrf().getBgpProcess().getActiveNeighbors().put(loopback1, peer21);
    bgpNeighborSpecificExportPolicy(c1, loopback2);
    bgpNeighborSpecificExportPolicy(c2, loopback1);
  }

  public static void eBgpSession(
      Configuration c1,
      Configuration c2,
      String ifaceName1,
      String ifaceName2,
      long asn1,
      long asn2) {
    Ip ip1 = getInterfaceIp(c1, ifaceName1);
    Ip ip2 = getInterfaceIp(c2, ifaceName2);
    BgpActivePeerConfig peer12 =
        BgpActivePeerConfig.builder()
            .setLocalAs(asn1)
            .setLocalIp(ip1)
            .setRemoteAs(asn2)
            .setPeerAddress(ip2)
            .setIpv4UnicastAddressFamily(
                Ipv4UnicastAddressFamily.builder()
                    .setImportPolicy(
                        generatedBgpPeerImportPolicyName(
                            c1.getDefaultVrf().getName(), ip2.toString()))
                    .setExportPolicy(
                        generatedBgpPeerExportPolicyName(
                            c1.getDefaultVrf().getName(), ip2.toString()))
                    .build())
            .build();
    BgpActivePeerConfig peer21 =
        BgpActivePeerConfig.builder()
            .setLocalAs(asn2)
            .setLocalIp(ip2)
            .setRemoteAs(asn1)
            .setPeerAddress(ip1)
            .setIpv4UnicastAddressFamily(
                Ipv4UnicastAddressFamily.builder()
                    .setImportPolicy(
                        generatedBgpPeerImportPolicyName(
                            c2.getDefaultVrf().getName(), ip1.toString()))
                    .setExportPolicy(
                        generatedBgpPeerExportPolicyName(
                            c2.getDefaultVrf().getName(), ip1.toString()))
                    .build())
            .build();
    c1.getDefaultVrf().getBgpProcess().getActiveNeighbors().put(ip2, peer12);
    c2.getDefaultVrf().getBgpProcess().getActiveNeighbors().put(ip1, peer21);
    bgpNeighborSpecificExportPolicy(c1, ip2);
    bgpNeighborSpecificExportPolicy(c2, ip1);
    bgpNeighborSpecificImportPolicy(c1, ip2);
    bgpNeighborSpecificImportPolicy(c2, ip1);
  }

  private static void bgpNetwork(Configuration c, Prefix prefix) {
    c.getDefaultVrf().getBgpProcess().getOriginationSpace().addPrefix(prefix);
  }

  public static Ip getInterfaceIp(Configuration c, String ifaceName) {
    return ((ConcreteInterfaceAddress) c.getAllInterfaces().get(ifaceName).getAddress()).getIp();
  }

  /*
   * Create a common BGP export policy. This policy's only function is to prevent export of
   * suppressed routes (contributors to summary-only aggregates).
   */
  public static void bgpCommonExportPolicy(Configuration c) {
    RoutingPolicy.Builder bgpCommonExportPolicy =
        RoutingPolicy.builder()
            .setOwner(c)
            .setName(generatedBgpCommonExportPolicyName(c.getDefaultVrf().getName()));

    // Never export routes suppressed because they are more specific than summary-only aggregate
    Stream<Prefix> summaryOnlyNetworks =
        c.getDefaultVrf().getBgpProcess().getAggregates().entrySet().stream()
            .filter(e -> e.getValue().getSuppressionPolicy() != null)
            .map(Map.Entry::getKey);
    If suppressSummaryOnly =
        suppressSummarizedPrefixes(c, c.getDefaultVrf().getName(), summaryOnlyNetworks);
    if (suppressSummaryOnly != null) {
      bgpCommonExportPolicy.addStatement(suppressSummaryOnly);
    }

    // Finalize common export policy
    bgpCommonExportPolicy.addStatement(Statements.ReturnTrue.toStaticStatement()).build();
  }

  /** Create BGP redistribution policy to import main RIB routes into BGP RIB. */
  public static void bgpRedistributionPolicy(Configuration c) {
    BgpProcess proc = c.getDefaultVrf().getBgpProcess();

    String redistPolicyName = generatedBgpRedistributionPolicyName(c.getDefaultVrf().getName());
    RoutingPolicy.Builder redistributionPolicy =
        RoutingPolicy.builder().setOwner(c).setName(redistPolicyName);

    // For IOS, local routes have a default weight of 32768.
    redistributionPolicy.addStatement(
        new SetWeight(new LiteralInt(CISCO_DEFAULT_LOCAL_BGP_WEIGHT)));

    // create origination prefilter from listed advertised networks
    if (!proc.getOriginationSpace().isEmpty()) {
      Conjunction exportNetworkConditions = new Conjunction();
      exportNetworkConditions
          .getConjuncts()
          .add(
              new MatchPrefixSet(
                  DestinationNetwork.instance(),
                  new ExplicitPrefixSet(proc.getOriginationSpace())));
      exportNetworkConditions
          .getConjuncts()
          .add(
              new Not(
                  new MatchProtocol(
                      RoutingProtocol.BGP, RoutingProtocol.IBGP, RoutingProtocol.AGGREGATE)));
      redistributionPolicy.addStatement(
          new If(
              "Add network statement routes to BGP",
              exportNetworkConditions,
              ImmutableList.of(
                  new SetOrigin(new LiteralOrigin(OriginType.IGP, null)),
                  Statements.ExitAccept.toStaticStatement())));
    }

    // Finalize redistribution policy and attach to process
    redistributionPolicy.addStatement(Statements.ExitReject.toStaticStatement()).build();
    proc.setRedistributionPolicy(redistPolicyName);
  }

  public static void bgpNeighborSpecificExportPolicy(Configuration c, Ip peerIp) {
    List<Statement> exportPolicyStatements = new ArrayList<>();
    // Conditions for exporting regular routes (not spawned by default-originate)
    List<BooleanExpr> peerExportConjuncts = new ArrayList<>();
    peerExportConjuncts.add(
        new CallExpr(generatedBgpCommonExportPolicyName(c.getDefaultVrf().getName())));
    exportPolicyStatements.add(
        new If(
            "peer-export policy main conditional: exitAccept if true / exitReject if false",
            new Conjunction(peerExportConjuncts),
            ImmutableList.of(Statements.ExitAccept.toStaticStatement()),
            ImmutableList.of(Statements.ExitReject.toStaticStatement())));
    RoutingPolicy.builder()
        .setOwner(c)
        .setName(generatedBgpPeerExportPolicyName(c.getDefaultVrf().getName(), peerIp.toString()))
        .setStatements(exportPolicyStatements)
        .build();
  }

  public static void bgpNeighborSpecificImportPolicy(Configuration c, Ip peerIp) {
    List<Statement> importPolicyStatements = new ArrayList<>();
    importPolicyStatements.add(Statements.ExitAccept.toStaticStatement());
    RoutingPolicy.builder()
        .setOwner(c)
        .setName(generatedBgpPeerImportPolicyName(c.getDefaultVrf().getName(), peerIp.toString()))
        .setStatements(importPolicyStatements)
        .build();
  }

  public static void staticRoute(Configuration c, Prefix prefix) {
    c.getDefaultVrf()
        .getStaticRoutes()
        .add(
            StaticRoute.builder()
                .setNetwork(prefix)
                .setNextHopInterface(CISCO_NULL)
                .setAdministrativeCost(1)
                .build());
  }
}
