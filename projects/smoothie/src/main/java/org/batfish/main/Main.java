package org.batfish.main;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Edge;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.dataplane.ibdp.IncrementalSimulator;
import org.batfish.utils.BatfishUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Main {
  private static final Logger LOGGER = LogManager.getLogger(Main.class);

  public static void fm2rr(String name) throws IOException {
    long start = System.nanoTime();
    LOGGER.error("{}-fm2rr starts", name);

    Path tracePath =
        Paths.get(System.getProperty("user.home"))
            .resolve("ANTS/snowcap/smoothie/zoo/traces")
            .resolve(name + "-FM2RR.json");
    Pair<List<Integer>, List<TraceParser.Step>> parsed = TraceParser.parse(tracePath);
    List<Integer> reflectors = parsed.getLeft();
    List<TraceParser.Step> steps = parsed.getRight();
    LOGGER.info("Loaded {} steps from trace, reflectors: {}", steps.size(), reflectors);

    Map<String, Configuration> initialConfigs = TopologyZoo.init(name, true, null);
    Pair<Path, Batfish> initialPair =
        BatfishUtil.getBatfishFromConfiguration(
            BatfishUtil.OUTPUT_BASE, name + "-initial", new TreeMap<>(initialConfigs), null, false);
    Batfish initialBatfish = initialPair.getRight();
    IncrementalSimulator simulator = new IncrementalSimulator(initialBatfish);
    simulator.computeInitialDataPlane();

    Map<String, Configuration> finalConfigs = TopologyZoo.init(name, false, reflectors.get(0));
    Pair<Path, Batfish> finalPair =
        BatfishUtil.getBatfishFromConfiguration(
            BatfishUtil.OUTPUT_BASE, name + "-final", new TreeMap<>(finalConfigs), null, false);
    Batfish finalBatfish = finalPair.getRight();
    finalBatfish.computeDataPlane(finalBatfish.getSnapshot());
    BgpTopology finalBgpTopology =
        finalBatfish.getTopologyProvider().getBgpTopology(finalBatfish.getSnapshot());

    TraceExecutor executor = new TraceExecutor(simulator, initialBatfish, finalBgpTopology);
    executor.execute(steps);

    // simulator.checkSafety();
    LOGGER.error("{}-fm2rr finish in {}s", name, (System.nanoTime() - start) / 1e9);
  }

  public static void igpx2(String name) {
    long start = System.nanoTime();
    LOGGER.info("{}-igpx2 starts", name);

    Map<String, Configuration> iConfigs = TopologyZoo.init(name, false, null);
    Pair<Path, Batfish> pair =
        BatfishUtil.getBatfishFromConfiguration(
            BatfishUtil.OUTPUT_BASE, name, new TreeMap<>(iConfigs), null, false);
    Batfish batfish = pair.getRight();
    IncrementalSimulator simulator = new IncrementalSimulator(batfish);

    // initial data plane
    simulator.computeInitialDataPlane();
    for (Edge edge : simulator.getLayer3Topology().getEdges()) {
      if (edge.getNode1().contains("er") || edge.getNode2().contains("er")) continue;
      LOGGER.info("doubling link weight for {}", edge);
      simulator.modifyOspfLinkWeightAndSimulate(edge, simulator.getOspfLinkWeight(edge) * 2);
      // simulator.checkSafety();
    }

    LOGGER.error("{}-igpx2 finish in {}s", name, (System.nanoTime() - start) / 1e9);
  }

  public static void main(String[] args) throws IOException {
    String name = args.length > 0 ? args[0] : "Aconet";
    fm2rr(name);
  }
}
