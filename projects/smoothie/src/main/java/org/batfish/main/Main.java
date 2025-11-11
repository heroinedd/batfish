package org.batfish.main;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.datamodel.Configuration;
import org.batfish.utils.BatfishUtil;
import org.batfish.utils.GmlUtil;
import org.batfish.utils.ResultPrinter;
import org.jgrapht.graph.SimpleGraph;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.batfish.utils.GmlUtil.readTopology;

public class Main {
  private static final Logger LOGGER = LogManager.getLogger(Main.class);

  public static void gml() {
    Path path = Paths.get("networks", "topology-zoo");
    List<GmlUtil.GraphAttributes> attrs = new LinkedList<>();
    for (File file :
        Arrays.stream(Objects.requireNonNull(path.toFile().listFiles())).sorted().toList()) {
      try {
        Path gml = file.toPath().resolve(file.getName() + ".gml");
        Path weights = file.toPath().resolve(file.getName() + ".json");
        SimpleGraph<GmlUtil.Node, GmlUtil.Edge> g = readTopology(gml, weights, 0);
        List<Integer> degrees = g.vertexSet().stream().map(g::degreeOf).sorted().toList();
        int minDegree = degrees.get(0);
        int maxDegree = degrees.get(degrees.size() - 1);
        int avgDegree = degrees.stream().reduce(0, Integer::sum) / degrees.size();
        attrs.add(
            new GmlUtil.GraphAttributes(
                file.getName(),
                g.vertexSet().size(),
                g.edgeSet().size(),
                minDegree,
                maxDegree,
                avgDegree,
                (int) g.vertexSet().stream().filter(v -> !v.isInternal()).count()));
      } catch (Exception e) {
        LOGGER.error("{} error: {}", file.getName(), e);
      }
    }
    attrs.stream().sorted().forEach(System.out::println);
  }

  public static void zooFromVI(String name) {
    Map<String, Configuration> configurations = TopologyZoo.init(name, false);
    Pair<Path, Batfish> pair =
        BatfishUtil.getBatfishFromConfiguration(
            BatfishUtil.OUTPUT_BASE, name, new TreeMap<>(configurations), null, false);
    Batfish batfish = pair.getRight();
    batfish.computeDataPlane(batfish.getSnapshot());
    // DataPlane dp = batfish.loadDataPlane(batfish.getSnapshot());
    ResultPrinter.printSnapshotResult(
        batfish, pair.getKey(), true, true, true, true, true, false, false);
  }

  public static void zooFromVSB(String name) {
    Map<String, String> vsbs = TopologyZoo.synthesizeCiscoConfigurations(name, false, null);
    Pair<Path, Batfish> pair =
        BatfishUtil.getBatfishFromTestrigText(
            BatfishUtil.OUTPUT_BASE, name, BatfishUtil.timestamp(), vsbs, null, false);
    Batfish batfish = pair.getRight();
    batfish.computeDataPlane(batfish.getSnapshot());
    ResultPrinter.printSnapshotResult(
        batfish, pair.getKey(), true, true, true, true, true, false, false);
  }

  public static void example() {
    Map<String, String> configurations = new TreeMap<>();
    Path folder = Paths.get("/Users/wangdan/ANTS/batfish/networks/example/candidate/configs");
    for (String name : Objects.requireNonNull(folder.toFile().list())) {
      try (BufferedReader br = new BufferedReader(new FileReader(folder.resolve(name).toFile()))) {
        configurations.put(name.split("\\.")[0], br.lines().collect(Collectors.joining("\n")));
      } catch (Exception e) {
        LOGGER.error(e);
      }
    }
    Pair<Path, Batfish> pair =
        BatfishUtil.getBatfishFromTestrigText(
            BatfishUtil.OUTPUT_BASE.getParent(),
            "example",
            BatfishUtil.timestamp(),
            configurations,
            null,
            false);
    Batfish batfish = pair.getRight();
    batfish.computeDataPlane(batfish.getSnapshot());
    ResultPrinter.printSnapshotResult(
        batfish, pair.getKey(), true, true, true, false, false, false, false);
  }

  public static void main(String[] args) {
    zooFromVSB("Aconet");
    // example();
    TopologyZoo.synthesizeCiscoConfigurations(
        "Aconet", false, Paths.get("/Users/wangdan/ANTS/expresso/networks/aconet"));
  }
}
