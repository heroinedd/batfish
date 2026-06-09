package org.batfish.main;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.dataplane.ibdp.IncrementalSimulator;
import org.batfish.utils.BatfishUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Main {
  private static final Logger LOGGER = LogManager.getLogger(Main.class);

  private static final Path TRACES_DIR =
      Paths.get(System.getProperty("user.home")).resolve("ANTS/snowcap/smoothie/zoo/traces");

  /**
   * Parses a trace file named {@code <name>-<suffix>.json} and logs the result.
   *
   * @return a pair of (reflector IDs, steps)
   */
  private static Pair<List<Integer>, List<TraceParser.Step>> loadTrace(String name, String suffix)
      throws IOException {
    Path tracePath = TRACES_DIR.resolve(name + "-" + suffix + ".json");
    Pair<List<Integer>, List<TraceParser.Step>> parsed = TraceParser.parse(tracePath);
    LOGGER.info(
        "Loaded {} steps from {}, reflectors: {}",
        parsed.getRight().size(),
        tracePath.getFileName(),
        parsed.getLeft());
    return parsed;
  }

  public static void fm2rr(String name) throws IOException {
    long start = System.nanoTime();
    LOGGER.info("{}-fm2rr starts", name);

    Pair<List<Integer>, List<TraceParser.Step>> parsed = loadTrace(name, "FM2RR");
    List<Integer> reflectors = parsed.getLeft();
    List<TraceParser.Step> steps = parsed.getRight();

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
    double duration = (System.nanoTime() - start) / 1e9;
    LOGGER.info("{}-fm2rr finish in {}s", name, duration);
    System.out.printf("%s-FM2RR\t%f\n", name, duration);
  }

  public static void igpx2(String name) throws IOException {
    long start = System.nanoTime();
    LOGGER.info("{}-igpx2 starts", name);

    Pair<List<Integer>, List<TraceParser.Step>> parsed = loadTrace(name, "IGPx2");
    List<TraceParser.Step> steps = parsed.getRight();

    Map<String, Configuration> configs = TopologyZoo.init(name, false, null);
    Pair<Path, Batfish> pair =
        BatfishUtil.getBatfishFromConfiguration(
            BatfishUtil.OUTPUT_BASE, name + "-igpx2", new TreeMap<>(configs), null, false);
    Batfish batfish = pair.getRight();
    IncrementalSimulator simulator = new IncrementalSimulator(batfish);
    simulator.computeInitialDataPlane();

    // IGPx2 traces have no BGP session changes, so finalBgpTopology == initial
    TraceExecutor executor = new TraceExecutor(simulator, batfish, simulator.getBgpTopology());
    executor.execute(steps);

    LOGGER.info("{}-igpx2 finish in {}s", name, (System.nanoTime() - start) / 1e9);
  }

  public static void main(String[] args) {
    List<String> files =
        Arrays.stream(
                Objects.requireNonNull(
                    new File("/Users/wangdan/ANTS/snowcap/eval_sigcomm2021/topology_zoo").list()))
            .sorted()
            .toList();
    for (String file : files) {
      if (file.toLowerCase().contains("example")) continue;
      String name = file.split("\\.")[0];
      try {
        fm2rr(name);
      } catch (IOException ignored) {
      }
    }
  }
}
