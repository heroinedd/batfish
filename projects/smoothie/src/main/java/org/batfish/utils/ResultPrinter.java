package org.batfish.utils;

import static org.batfish.utils.StorageUtil.getBufferedWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.Table;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.*;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.datamodel.bgp.LocalOriginationTypeTieBreaker;
import org.batfish.datamodel.bgp.NextHopIpTieBreaker;
import org.batfish.dataplane.rib.Bgpv4Rib;
import org.batfish.dataplane.rib.Rib;
import org.batfish.main.Batfish;

public class ResultPrinter {
  private static final Logger LOGGER = SmoothieLogger.LOGGER;

  private static final Rib RIB = new Rib();
  private static final Bgpv4Rib BGP_RIB =
      new Bgpv4Rib(
          null,
          BgpTieBreaker.ROUTER_ID,
          999,
          MultipathEquivalentAsPathMatchMode.PATH_LENGTH,
          false,
          LocalOriginationTypeTieBreaker.NO_PREFERENCE,
          NextHopIpTieBreaker.HIGHEST_NEXT_HOP_IP,
          NextHopIpTieBreaker.HIGHEST_NEXT_HOP_IP,
          ResolutionRestriction.alwaysTrue());

  private static int compareRoute(AbstractRoute r1, AbstractRoute r2) {
    return RIB.comparePreference(
        new AnnotatedRoute<>(r1, "default"), new AnnotatedRoute<>(r2, "default"));
  }

  public static void printSnapshotResult(
      Batfish batfish,
      Path outputSnapshot,
      boolean printViConfigs,
      boolean printL3Topology,
      boolean printBgpTopology,
      boolean printRibs,
      boolean printBgpRibs,
      boolean printFibs,
      boolean printPrefixes) {
    try {
      Path tmp = outputSnapshot.resolve("output").resolve("dan");

      BufferedWriter bw;
      if (printViConfigs) {
        Map<String, String> configs = printConfigurations(batfish);
        for (Map.Entry<String, String> entry : configs.entrySet()) {
          bw = getBufferedWriter(tmp.resolve("json_configs").resolve(entry.getKey() + ".json"));
          bw.write(entry.getValue());
          bw.close();
        }
      }

      if (printL3Topology) {
        List<String> layer3Topology = printLayer3Topology(batfish);
        bw = getBufferedWriter(tmp.resolve("batfish_layer3_topology.txt"));
        bw.write(String.join("\n", layer3Topology));
        bw.close();
      }

      if (printBgpTopology) {
        List<String> bgpTopology = printBgpTopology(batfish);
        bw = getBufferedWriter(tmp.resolve("batfish_bgp_topology.txt"));
        bw.write(String.join("\n", bgpTopology));
        bw.close();
      }

      if (printRibs) {
        Map<String, List<String>> ribs = printRib(batfish);
        for (Map.Entry<String, List<String>> entry : ribs.entrySet()) {
          bw = getBufferedWriter(tmp.resolve("ribs").resolve(entry.getKey() + ".txt"));
          bw.write(String.join("\n", entry.getValue()));
          bw.close();
        }
      }

      if (printBgpRibs) {
        Map<String, List<String>> bgpRibs = printBgpRib(batfish);
        for (Map.Entry<String, List<String>> entry : bgpRibs.entrySet()) {
          bw = getBufferedWriter(tmp.resolve("bgpRibs").resolve(entry.getKey() + ".txt"));
          bw.write(String.join("\n", entry.getValue()));
          bw.close();
        }
      }

      if (printFibs) {
        Map<String, List<String>> fibs = printFib(batfish);
        for (Map.Entry<String, List<String>> entry : fibs.entrySet()) {
          bw = getBufferedWriter(tmp.resolve("fibs").resolve(entry.getKey() + ".txt"));
          bw.write(String.join("\n", entry.getValue()));
          bw.close();
        }
      }

      if (printPrefixes) {
        List<String> prefixes = printPrefixes(batfish);
        bw = getBufferedWriter(tmp.resolve("prefixes.txt"));
        bw.write(String.join("\n", prefixes));
        bw.close();
      }
    } catch (IOException e) {
      LOGGER.error(e);
    }
  }

  public static String shortenHostName(String longName) {
    if (StringUtils.isNumeric(longName)) {
      return longName.length() > 6
          ? longName.substring(0, 3) + longName.substring(longName.length() - 3)
          : longName;
    } else {
      return longName;
    }
  }

  public static Map<String, String> printConfigurations(Batfish batfish) {
    // we want to keep null values and empty lists, so we use the verbose mapper
    ObjectMapper mapper =
        BatfishObjectMapper.verboseMapper().enable(SerializationFeature.INDENT_OUTPUT);

    Map<String, String> configs = new HashMap<>();
    for (Map.Entry<String, Configuration> entry :
        batfish.loadConfigurations(batfish.getSnapshot()).entrySet()) {
      String name = shortenHostName(entry.getKey());
      Configuration c = entry.getValue();
      String config = "";
      try {
        config = mapper.writeValueAsString(c);
        // config = BatfishObjectMapper.writePrettyString(c);
      } catch (IOException e) {
        LOGGER.error(e);
      }
      configs.put(name, config);
    }
    return configs;
  }

  public static List<String> printLayer3Topology(Batfish batfish) {
    Topology layer3Topology =
        batfish.getTopologyProvider().getLayer3Topology(batfish.getSnapshot());
    Map<String, Configuration> cMap = batfish.loadConfigurations(batfish.getSnapshot());
    layer3Topology
        .getEdges()
        .forEach(
            edge -> {
              Configuration c1 = cMap.get(edge.getHead().getHostname());
              Interface if1 = c1.getAllInterfaces().get(edge.getHead().getInterface());
              Configuration c2 = cMap.get(edge.getTail().getHostname());
              Interface if2 = c2.getAllInterfaces().get(edge.getTail().getInterface());
              if (if1.getChannelGroup() != null || if2.getChannelGroup() != null) {
                System.out.println(edge);
              }
            });
    return layer3Topology.getEdges().stream()
        .map(
            edge ->
                String.join(
                    "\t",
                    shortenHostName(edge.getTail().getHostname()),
                    edge.getTail().getInterface(),
                    shortenHostName(edge.getHead().getHostname()),
                    edge.getHead().getInterface()))
        .sorted()
        .collect(Collectors.toList());
  }

  public static List<String> printBgpTopology(Batfish batfish) {
    BgpTopology bgpTopology = batfish.getTopologyProvider().getBgpTopology(batfish.getSnapshot());
    return bgpTopology.getGraph().edges().stream()
        .map(
            pair ->
                String.format(
                        "Vrf(%s, %s)",
                        shortenHostName(pair.source().getHostname()), pair.source().getVrfName())
                    + "\t"
                    + pair.target().getRemotePeerPrefix().getStartIp()
                    + "\t"
                    + String.format(
                        "Vrf(%s, %s)",
                        shortenHostName(pair.target().getHostname()), pair.target().getVrfName())
                    + "\t"
                    + pair.source().getRemotePeerPrefix().getStartIp())
        .sorted()
        .collect(Collectors.toList());
  }

  public static Map<String, List<String>> printRib(Batfish batfish) {
    Map<String, List<String>> map = new HashMap<>();
    DataPlane dataPlane = batfish.loadDataPlane(batfish.getSnapshot());
    Table<String, String, FinalMainRib> ribs = dataPlane.getRibs();
    for (Table.Cell<String, String, FinalMainRib> cell : ribs.cellSet()) {
      String hostname = shortenHostName(cell.getRowKey());
      String vrfname = cell.getColumnKey();
      List<String> list = new LinkedList<>();
      list.add(vrfname + ":\n");
      list.addAll(
          cell.getValue().getRoutes().stream()
              .sorted((r1, r2) -> -compareRoute(r1, r2))
              .map(AbstractRoute::toString)
              .toList());
      list.add("\n");
      map.computeIfAbsent(hostname, x -> new LinkedList<>()).addAll(list);
    }
    return map;
  }

  public static Map<String, List<String>> printBgpRib(Batfish batfish) {
    Map<String, List<String>> map = new HashMap<>();
    DataPlane dataPlane = batfish.loadDataPlane(batfish.getSnapshot());
    Table<String, String, Set<Bgpv4Route>> bgpRoutes = dataPlane.getBgpRoutes();
    for (Map.Entry<String, Map<String, Set<Bgpv4Route>>> e1 : bgpRoutes.rowMap().entrySet()) {
      String hostName = shortenHostName(e1.getKey());
      List<String> list = new LinkedList<>();
      for (Map.Entry<String, Set<Bgpv4Route>> e2 : e1.getValue().entrySet()) {
        String vrfName = e2.getKey();
        list.add(vrfName);
        list.addAll(
            e2.getValue().stream()
                .sorted((r1, r2) -> -BGP_RIB.comparePreference(r1, r2))
                .map(Bgpv4Route::toString)
                .toList());
        list.add("\n");
      }
      map.put(hostName, list);
    }
    return map;
  }

  public static Map<String, List<String>> printFib(Batfish batfish) {
    Map<String, List<String>> map = new HashMap<>();
    DataPlane dataPlane = batfish.loadDataPlane(batfish.getSnapshot());
    Map<String, Map<String, Fib>> fibs = dataPlane.getFibs();
    for (Map.Entry<String, Map<String, Fib>> e1 : fibs.entrySet()) {
      String hostName = shortenHostName(e1.getKey());
      List<String> list = new LinkedList<>();
      for (Map.Entry<String, Fib> e2 : e1.getValue().entrySet()) {
        String vrfName = e2.getKey();
        list.add(vrfName);
        list.addAll(
            e2.getValue().allEntries().stream()
                .map(
                    entry ->
                        entry.getTopLevelRoute().getNetwork()
                            + ",\t"
                            + entry.getAction()
                            + ",\t"
                            + entry.getTopLevelRoute())
                .toList());
        list.add("\n");
      }
      map.put(hostName, list);
    }
    return map;
  }

  public static List<String> printPrefixes(Batfish batfish) {
    Set<String> prefixes = new HashSet<>();
    Map<String, Configuration> configurations = batfish.loadConfigurations(batfish.getSnapshot());

    for (Configuration c : configurations.values()) {
      for (Interface intf : c.getAllInterfaces().values()) {
        for (ConcreteInterfaceAddress addr : intf.getAllConcreteAddresses()) {
          prefixes.add(addr.getPrefix().toString());
        }
      }
      for (Vrf vrf : c.getVrfs().values()) {
        for (StaticRoute sr : vrf.getStaticRoutes()) {
          prefixes.add(sr.getNetwork().toString());
        }
        if (vrf.getBgpProcess() == null) continue;
        vrf.getBgpProcess()
            .getOriginationSpace()
            .getPrefixRanges()
            .forEach(pr -> prefixes.add(pr.getPrefix().toString()));
      }
    }

    return prefixes.stream().sorted().collect(Collectors.toList());
  }
}
