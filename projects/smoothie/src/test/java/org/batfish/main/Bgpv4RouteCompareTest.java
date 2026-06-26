package org.batfish.main;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.batfish.datamodel.AsPath;
import org.batfish.datamodel.BgpTieBreaker;
import org.batfish.datamodel.Bgpv4Route;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.OriginMechanism;
import org.batfish.datamodel.OriginType;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.ReceivedFromIp;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.bgp.LocalOriginationTypeTieBreaker;
import org.batfish.datamodel.bgp.NextHopIpTieBreaker;
import org.batfish.datamodel.ResolutionRestriction;
import org.batfish.dataplane.rib.Bgpv4Rib;
import org.junit.Test;

public class Bgpv4RouteCompareTest {

  private static Bgpv4Route buildRoute(String nextHopIp, String originatorIp) {
    return Bgpv4Route.testBuilder()
        .setNetwork(Prefix.parse("70.0.0.0/24"))
        .setAdmin(100)
        .setTag(-1L)
        .setAsPath(AsPath.ofSingletonAsSets(10010L))
        .setLocalPreference(100)
        .setNextHopIp(Ip.parse(nextHopIp))
        .setOriginatorIp(Ip.parse(originatorIp))
        .setOriginMechanism(OriginMechanism.LEARNED)
        .setOriginType(OriginType.IGP)
        .setProtocol(RoutingProtocol.IBGP)
        .setReceivedFrom(ReceivedFromIp.of(Ip.parse(originatorIp)))
        .setReceivedFromRouteReflectorClient(false)
        .setSrcProtocol(RoutingProtocol.IBGP)
        .build();
  }

  private static final Bgpv4Rib RIB =
      new Bgpv4Rib(
          null,
          BgpTieBreaker.ROUTER_ID,
          1,
          null,
          false,
          LocalOriginationTypeTieBreaker.NO_PREFERENCE,
          NextHopIpTieBreaker.HIGHEST_NEXT_HOP_IP,
          NextHopIpTieBreaker.HIGHEST_NEXT_HOP_IP,
          ResolutionRestriction.alwaysTrue());

  @Test
  public void testRoutesAreNotEqual() {
    Bgpv4Route route1 = buildRoute("10.7.10.1", "1.7.7.7");
    Bgpv4Route route2 = buildRoute("10.10.14.0", "1.14.14.14");

    assertThat(route1, equalTo(route1));
    assertThat(route2, equalTo(route2));
    assertThat(route1, not(equalTo(route2)));
  }

  private static Bgpv4Route buildEbgpRoute(String nextHopIp, String originatorIp, long asn) {
    return Bgpv4Route.testBuilder()
        .setNetwork(Prefix.parse("70.0.0.0/24"))
        .setAdmin(100)
        .setTag(-1L)
        .setAsPath(AsPath.ofSingletonAsSets(asn))
        .setLocalPreference(100)
        .setNextHopIp(Ip.parse(nextHopIp))
        .setOriginatorIp(Ip.parse(originatorIp))
        .setOriginMechanism(OriginMechanism.LEARNED)
        .setOriginType(OriginType.IGP)
        .setProtocol(RoutingProtocol.BGP)
        .setReceivedFrom(ReceivedFromIp.of(Ip.parse(nextHopIp)))
        .setReceivedFromRouteReflectorClient(false)
        .setSrcProtocol(RoutingProtocol.BGP)
        .build();
  }

  @Test
  public void testComparePreferenceEbgp() {
    // _protocol=1 (BGP/eBGP), different AS numbers
    Bgpv4Route routeA = buildEbgpRoute("10.10.14.0", "1.10.10.10", 10010L);
    Bgpv4Route routeB = buildEbgpRoute("10.5.14.0", "1.5.5.5", 10005L);

    int cmp = RIB.comparePreference(routeA, routeB);
    System.out.printf("comparePreference(routeA, routeB) = %d%n", cmp);
    System.out.printf("  routeA nextHop=10.10.14.0 originator=1.10.10.10 asn=10010%n");
    System.out.printf("  routeB nextHop=10.5.14.0  originator=1.5.5.5   asn=10005%n");
    if (cmp > 0) {
      System.out.println("  => routeA is preferred");
    } else if (cmp < 0) {
      System.out.println("  => routeB is preferred");
    } else {
      System.out.println("  => routes are equally preferred");
    }

    assertThat(RIB.comparePreference(routeA, routeA), equalTo(0));
    assertThat(RIB.comparePreference(routeB, routeB), equalTo(0));
  }

  @Test
  public void testComparePreference() {
    Bgpv4Route route1 = buildRoute("10.7.10.1", "1.7.7.7");
    Bgpv4Route route2 = buildRoute("10.10.14.0", "1.14.14.14");

    int cmp = RIB.comparePreference(route1, route2);
    System.out.printf(
        "comparePreference(route1, route2) = %d%n", cmp);
    System.out.printf(
        "  route1 nextHop=10.7.10.1  originator=1.7.7.7%n");
    System.out.printf(
        "  route2 nextHop=10.10.14.0 originator=1.14.14.14%n");
    if (cmp > 0) {
      System.out.println("  => route1 is preferred");
    } else if (cmp < 0) {
      System.out.println("  => route2 is preferred");
    } else {
      System.out.println("  => routes are equally preferred");
    }

    // Self-comparison must be 0
    assertThat(RIB.comparePreference(route1, route1), equalTo(0));
    assertThat(RIB.comparePreference(route2, route2), equalTo(0));
  }
}
