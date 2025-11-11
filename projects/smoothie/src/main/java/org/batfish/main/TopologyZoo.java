package org.batfish.main;

import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Prefix;
import org.batfish.utils.ConfigUtil;
import org.batfish.utils.GmlUtil;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
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
      Configuration c = router((node.isInternal() ? "r" : "er") + id, loopbackIp(id));
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

  static final class CiscoConfiguration {
    String loopbackIp;
    Map<String, String> ifaces = new TreeMap<>();
    Map<String, Boolean> ibgpPeers = new TreeMap<>();
    Map<String, Long> ebgpPeers = new TreeMap<>();
    Set<String> networks = new TreeSet<>();
    long asn;

    private String toMask(String len) {
      int length = Integer.parseInt(len);
      int tmp = 24;
      List<String> octs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        int oct = length > tmp ? length - tmp : 0;
        octs.add(0, (256 - (1 << (8 - oct))) + "");
        length -= oct;
        tmp -= 8;
      }
      return String.join(".", octs);
    }

    private String ebgpPeerIp(String iface) {
      String ifaceIp = ifaces.get(iface).split("/")[0];
      String[] parts = ifaceIp.split("\\.");
      parts[3] = Objects.equals(parts[3], "0") ? "1" : "0";
      return String.join(".", parts);
    }

    String toConfiguration(String routerName) {
      StringBuilder builder = new StringBuilder();

      // router name
      builder.append("!\n");
      builder.append("hostname " + routerName + "\n");
      builder.append("!\n");
      builder.append("!\n");

      // interfaces
      for (String iface : ifaces.keySet()) {
        builder.append("interface " + iface + "\n");
        String ipAddr = ifaces.get(iface);
        if (ipAddr != null) {
          String ip = ipAddr.split("/")[0];
          String mask = toMask(ipAddr.split("/")[1]);
          builder.append(String.format("  ip address %s %s\n", ip, mask));
        }
        builder.append("  negotiation auto\n");
        builder.append("!\n");
      }

      // ospf
      builder.append("!\n");
      builder.append("!\n");
      builder.append("router ospf 1\n");
      builder.append("  router-id " + loopbackIp.split("/")[0] + "\n");
      // builder.append("  redistribute connected subnets\n");
      // builder.append("  passive-interface " + CISCO_LOOPBACK + "\n");
      builder.append("  network 10.0.0.0 0.255.255.255 area 1\n");
      builder.append("  network 1.0.0.0 0.255.255.255 area 1\n");
      builder.append("!\n");
      builder.append("!\n");

      // bgp
      builder.append("!\n");
      builder.append("!\n");
      builder.append("router bgp " + asn + "\n");
      builder.append("  bgp router-id " + loopbackIp.split("/")[0] + "\n");
      for (String ibgp : ibgpPeers.keySet()) {
        String peerIp = ibgp.split("/")[0];
        builder.append("  neighbor " + peerIp + " remote-as " + asn + "\n");
        builder.append("  neighbor " + peerIp + " update-source " + CISCO_LOOPBACK + "\n");
      }
      for (String ebgp : ebgpPeers.keySet()) {
        String peerIp = ebgpPeerIp(ebgp);
        builder.append("  neighbor " + peerIp + " remote-as " + ebgpPeers.get(ebgp) + "\n");
      }
      // ipv4 address family
      builder.append("  !\n");
      builder.append("  address-family ipv4\n");
      for (String network : networks) {
        String ip = network.split("/")[0];
        String mask = toMask(network.split("/")[1]);
        builder.append(String.format("    network %s mask %s \n", ip, mask));
      }
      for (String ibgp : ibgpPeers.keySet()) {
        String peerIp = ibgp.split("/")[0];
        builder.append("    neighbor " + peerIp + " activate\n");
        builder.append("    neighbor " + peerIp + " send-community\n");
        if (ibgpPeers.get(ibgp)) {
          builder.append("    neighbor " + peerIp + " route-reflector-client\n");
        }
      }
      for (String ebgp : ebgpPeers.keySet()) {
        String peerIp = ebgpPeerIp(ebgp);
        builder.append("    neighbor " + peerIp + " activate\n");
        builder.append("    neighbor " + peerIp + " send-community\n");
      }
      builder.append("  exit-address-family\n");
      builder.append("!\n");
      builder.append("!\n");

      // static routes
      for (String network : networks) {
        String ip = network.split("/")[0];
        String mask = toMask(network.split("/")[1]);
        builder.append(String.format("ip route %s %s %s\n", ip, mask, CISCO_NULL));
      }

      builder.append("!\n");
      builder.append("!\n");
      builder.append("control-plane\n");
      builder.append("!\n");
      builder.append("!\n");
      builder.append("end\n");

      return builder.toString();
    }
  }

  public static Map<String, String> synthesizeCiscoConfigurations(
      String name, boolean fullMesh, Path outputBase) {
    SimpleWeightedGraph<GmlUtil.Node, GmlUtil.Edge> g = GmlUtil.readTopology(name, -1);

    Map<Integer, CiscoConfiguration> configurations = new TreeMap<>();
    Set<Integer> externals = new HashSet<>();

    // routers
    for (GmlUtil.Node node : g.vertexSet()) {
      int id = node.getId();
      CiscoConfiguration cc = new CiscoConfiguration();

      cc.loopbackIp = loopbackIp(id);
      cc.ifaces.put(CISCO_LOOPBACK, cc.loopbackIp);
      cc.ifaces.put(CISCO_NULL, null);
      cc.asn = node.isInternal() ? INT_ASN : EXT_ASN + id;

      configurations.put(id, cc);
      if (!node.isInternal()) externals.add(id);
    }

    // edges
    for (GmlUtil.Edge edge : g.edgeSet()) {
      GmlUtil.Node n1 = g.getEdgeSource(edge);
      GmlUtil.Node n2 = g.getEdgeTarget(edge);
      CiscoConfiguration c1 = configurations.get(n1.getId());
      CiscoConfiguration c2 = configurations.get(n2.getId());
      String subnet =
          String.format(
              "10.%d.%d.", Math.min(n1.getId(), n2.getId()), Math.max(n1.getId(), n2.getId()));
      String iface1 = CISCO_ETH + n2.getId();
      String iface2 = CISCO_ETH + n1.getId();
      String iface1Ip = subnet + (n1.getId() < n2.getId() ? 0 : 1) + "/31";
      String iface2Ip = subnet + (n1.getId() < n2.getId() ? 1 : 0) + "/31";
      c1.ifaces.put(iface1, iface1Ip);
      c2.ifaces.put(iface2, iface2Ip);
    }

    // ibgp sessions
    if (fullMesh) {
      List<CiscoConfiguration> cfgs = configurations.values().stream().toList();
      int N = cfgs.size();
      for (int i = 0; i < N; i++) {
        for (int j = i + 1; j < N; j++) {
          if (!externals.contains(i) && !externals.contains(j)) {
            cfgs.get(i).ibgpPeers.put(cfgs.get(j).loopbackIp, false);
            cfgs.get(j).ibgpPeers.put(cfgs.get(i).loopbackIp, false);
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
          configurations.get(rr).ibgpPeers.put(configurations.get(client).loopbackIp, true);
          configurations.get(client).ibgpPeers.put(configurations.get(rr).loopbackIp, false);
        }
      }
    }

    // ebgp sessions
    for (GmlUtil.Edge edge : g.edgeSet()) {
      int id1 = g.getEdgeSource(edge).getId();
      int id2 = g.getEdgeTarget(edge).getId();
      if (externals.contains(id1) || externals.contains(id2)) {
        configurations
            .get(id1)
            .ebgpPeers
            .put(CISCO_ETH + id2, externals.contains(id2) ? EXT_ASN + id2 : INT_ASN);
        configurations
            .get(id2)
            .ebgpPeers
            .put(CISCO_ETH + id1, externals.contains(id1) ? EXT_ASN + id1 : INT_ASN);
      }
    }

    // bgp networks
    externals.forEach(er -> configurations.get(er).networks.add("70.0.0.0/24"));

    Function<Integer, String> getRouterName = id -> (externals.contains(id) ? "er" : "r") + id;
    Map<String, String> vsbs =
        configurations.entrySet().stream()
            .collect(
                Collectors.toMap(
                    e -> getRouterName.apply(e.getKey()),
                    e -> e.getValue().toConfiguration(getRouterName.apply(e.getKey()))));
    if (outputBase != null) {
      for (String r : vsbs.keySet()) {
        try (BufferedWriter bw =
            new BufferedWriter(
                new FileWriter(outputBase.resolve("raw-configs").resolve(r + ".cfg").toFile()))) {
          bw.write(vsbs.get(r));
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    return vsbs;
  }

  private static String loopbackIp(int id) {
    return String.format("1.%d.%d.%d/32", id, id, id);
  }
}
