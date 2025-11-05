package org.batfish.main;

import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Prefix;
import org.batfish.utils.ConfigUtil;
import org.batfish.utils.GmlUtil;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;
import java.util.stream.Collectors;

import static org.batfish.utils.ConfigUtil.*;

public class TopologyZoo {
  private static final long INT_ASN = 55990;
  private static final long EXT_ASN = 10000;

  public static Map<String, Configuration> init(String name, boolean fullMesh) {
    SimpleWeightedGraph<GmlUtil.Node, GmlUtil.Edge> g = GmlUtil.readTopology(name, -1);

    Map<Integer, Configuration> configurations = new TreeMap<>();
    Set<Integer> externals = new HashSet<>();

    // routers
    for (GmlUtil.Node node : g.vertexSet()) {
      int id = node.getId();
      Configuration c =
          router(
              (node.isInternal() ? "r" : "er") + id,
              String.format("192.168.%d.%d/32", id / 256, id % 256));
      configurations.put(id, c);
      if (!node.isInternal()) externals.add(id);
    }

    // edges
    for (GmlUtil.Edge edge : g.edgeSet()) {
      GmlUtil.Node n1 = g.getEdgeSource(edge);
      GmlUtil.Node n2 = g.getEdgeTarget(edge);
      Configuration c1 = configurations.get(n1.getId());
      Configuration c2 = configurations.get(n2.getId());
      String subnet =
          String.format(
              "10.%d.%d", Math.min(n1.getId(), n2.getId()), Math.max(n1.getId(), n2.getId()));
      edge(c1, c2, CISCO_ETH + n2.getId(), CISCO_ETH + n1.getId(), subnet);
    }

    // ospf and bgp processes
    configurations.values().forEach(ConfigUtil::ospfProcess);
    Set<Prefix> prefixes = Set.of(Prefix.parse("70.0.0.0/24"));
    configurations.forEach(
        (key, value) ->
            bgpProcess(value, externals.contains(key) ? prefixes : Collections.emptySet()));

    // ibgp sessions
    if (fullMesh) {
      List<Configuration> cfgs = configurations.values().stream().toList();
      int N = cfgs.size();
      for (int i = 0; i < N; i++) {
        for (int j = i + 1; j < N; j++) {
          if (!externals.contains(i) && !externals.contains(j)) {
            iBgpSession(cfgs.get(i), cfgs.get(j), INT_ASN, false);
          }
        }
      }
    } else {
      int rr =
          g.vertexSet().stream()
              .filter(v -> !externals.contains(v.getId()))
              .sorted(Comparator.comparing(v -> g.degreeOf((GmlUtil.Node) v)).reversed())
              .iterator()
              .next()
              .getId();
      for (int client : configurations.keySet()) {
        if (!externals.contains(client) && client != rr) {
          iBgpSession(configurations.get(rr), configurations.get(client), INT_ASN, true);
        }
      }
    }

    // ebgp sessions
    for (GmlUtil.Edge edge : g.edgeSet()) {
      int id1 = g.getEdgeSource(edge).getId();
      int id2 = g.getEdgeTarget(edge).getId();
      if (externals.contains(id1) || externals.contains(id2)) {
        eBgpSession(
            configurations.get(id1),
            configurations.get(id2),
            CISCO_ETH + id2,
            CISCO_ETH + id1,
            externals.contains(id1) ? EXT_ASN + id1 : INT_ASN,
            externals.contains(id2) ? EXT_ASN + id2 : INT_ASN);
      }
    }

    // static routes
    externals.forEach(er -> prefixes.forEach(p -> staticRoute(configurations.get(er), p)));

    return configurations.values().stream()
        .collect(Collectors.toMap(Configuration::getHostname, c -> c));
  }
}
