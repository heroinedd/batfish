package org.batfish.main;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.common.NetworkSnapshot;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.answers.ConvertConfigurationAnswerElement;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.dataplane.ibdp.IncrementalSimulator;
import org.batfish.identifiers.NetworkId;
import org.batfish.identifiers.SnapshotId;
import org.batfish.storage.StorageProvider;
import org.batfish.utils.BatfishUtil;
import org.batfish.utils.SmoothieConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class Main {
  private static final Logger LOGGER = LogManager.getLogger(Main.class);

  private static final Path TRACES_DIR = SmoothieConfig.tracesDir();

  /**
   * Parses a trace file named {@code <name>-<suffix>.json} and logs the result.
   *
   * @return a pair of (subnetworks, steps); each subnetwork is a list of node IDs with the first
   *     one being the reflector
   */
  private static Pair<List<List<Integer>>, List<TraceParser.Step>> loadTrace(
      String name, String suffix) {
    try {
      Path tracePath = TRACES_DIR.resolve(name + "-" + suffix + ".json");
      Pair<List<List<Integer>>, List<TraceParser.Step>> parsed = TraceParser.parse(tracePath);
      LOGGER.info(
          "Loaded {} steps from {}, reflectors: {}",
          parsed.getRight().size(),
          tracePath.getFileName(),
          parsed.getLeft());
      return parsed;
    } catch (IOException e) {
      LOGGER.error(e);
      return null;
    }
  }

  private static long time = 0;

  private static TraceExecutor getExecutor(
      String name,
      Map<String, Configuration> initialConfigs,
      Map<String, Configuration> finalConfigs) {
    Triple<Path, StorageProvider, Batfish> triple =
        BatfishUtil.getBatfishFromConfiguration(
            BatfishUtil.OUTPUT_BASE, name, "initial", new TreeMap<>(initialConfigs), null, false);
    Batfish batfish = triple.getRight();
    IncrementalSimulator simulator = new IncrementalSimulator(batfish, triple.getMiddle());
    simulator.computeInitialDataPlane();
    simulator.checkSafety(true);

    NetworkSnapshot finalSnapshot =
        new NetworkSnapshot(new NetworkId(name), new SnapshotId("final"));
    try {
      triple
          .getMiddle()
          .storeConfigurations(
              finalConfigs == null ? initialConfigs : finalConfigs,
              new ConvertConfigurationAnswerElement(),
              null,
              finalSnapshot.getNetwork(),
              finalSnapshot.getSnapshot());
    } catch (IOException e) {
      LOGGER.error("Could not save final configurations for {}: {}", name, e.getMessage());
    }
    long start = System.nanoTime();
    batfish.computeDataPlane(finalSnapshot);
    time = System.nanoTime() - start;
    BgpTopology finalBgpTopology = batfish.getTopologyProvider().getBgpTopology(finalSnapshot);

    return new TraceExecutor(triple.getLeft().getParent(), simulator, batfish, finalBgpTopology);
  }

  private static void finishUp(
      String name, String suffix, TraceExecutor executor, double duration) {
    double ioTime = executor.getIoTime() / 1e9;
    double checkingTime = executor.getCheckingTime() / 1e9;
    LOGGER.info("{}-{} finish in {}s", name, suffix, duration);
    System.out.printf("%s-%s\t%f\t%f\t%f\n", name, suffix, ioTime, checkingTime, duration);
    executor.cleanOutput();
  }

  public static void fm2rr(String name) {
    long start = System.nanoTime();
    LOGGER.info("{}-FM2RR starts", name);

    Pair<List<List<Integer>>, List<TraceParser.Step>> parsed = loadTrace(name, "FM2RR");
    if (parsed == null) return;
    List<List<Integer>> subnetworks = parsed.getLeft();
    List<TraceParser.Step> steps = parsed.getRight();

    Map<String, Configuration> initialConfigs = TopologyZoo.init(name, true, null);
    Map<String, Configuration> finalConfigs =
        TopologyZoo.init(name, false, subnetworks.get(0).get(0));

    TraceExecutor executor = getExecutor(name, initialConfigs, finalConfigs);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - time) / 1e9;
    finishUp(name, "FM2RR", executor, duration);
  }

  public static void rrx2(String name) {
    long start = System.nanoTime();
    LOGGER.info("{}-RRx2 starts", name);

    Pair<List<List<Integer>>, List<TraceParser.Step>> parsed = loadTrace(name, "RRx2");
    if (parsed == null) return;
    List<List<Integer>> subnetworks = parsed.getLeft();
    List<TraceParser.Step> steps = parsed.getRight();

    Map<String, Configuration> initialConfigs =
        TopologyZoo.init(name, false, subnetworks.get(0).get(0));
    Map<String, Configuration> finalConfigs = TopologyZoo.doubleRouteReflectorFinalConfig(name);

    TraceExecutor executor = getExecutor(name, initialConfigs, finalConfigs);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - time) / 1e9;
    finishUp(name, "RRx2", executor, duration);
  }

  public static void netAcq(String name) {
    long start = System.nanoTime();
    LOGGER.info("{}-NetAcq starts", name);

    Pair<List<List<Integer>>, List<TraceParser.Step>> parsed = loadTrace(name, "NetAcq");
    if (parsed == null) return;
    List<List<Integer>> subnetworks = parsed.getLeft();
    List<TraceParser.Step> steps = parsed.getRight();

    Map<String, Configuration> initialConfigs =
        TopologyZoo.networkAcquisitionConfig(name, subnetworks, true);
    Map<String, Configuration> finalConfigs =
        TopologyZoo.networkAcquisitionConfig(name, subnetworks, false);

    // NetAcq traces have no BGP session changes, so finalBgpTopology == initial
    TraceExecutor executor = getExecutor(name, initialConfigs, finalConfigs);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - time) / 1e9;
    finishUp(name, "NetAcq", executor, duration);
  }

  public static void doubleIgpWeightOrLocalPref(String name, String suffix) {
    if (!(suffix.equalsIgnoreCase("lpx2") || suffix.equalsIgnoreCase("igpx2"))) {
      LOGGER.error("Unsupported scenario {}", suffix);
      return;
    }
    long start = System.nanoTime();
    LOGGER.info("{}-{} starts", name, suffix);

    Pair<List<List<Integer>>, List<TraceParser.Step>> parsed = loadTrace(name, suffix);
    if (parsed == null) return;
    int rrId = parsed.getLeft().get(0).get(0);
    List<TraceParser.Step> steps = parsed.getRight();

    Map<String, Configuration> configs = TopologyZoo.init(name, false, rrId);

    // IGPx2 and LPx2 traces have no BGP session changes, so finalBgpTopology == initial
    TraceExecutor executor = getExecutor(name, configs, null);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - time) / 1e9;
    finishUp(name, suffix, executor, duration);
  }

  public static void main(String[] args) {
    String name = args[0];
    String scenario = args[1];
    switch (scenario.toLowerCase()) {
      case "fm2rr" -> fm2rr(name);
      case "rrx2" -> rrx2(name);
      case "netacq" -> netAcq(name);
      case "igpx2" -> doubleIgpWeightOrLocalPref(name, "IGPx2");
      case "lpx2" -> doubleIgpWeightOrLocalPref(name, "LPx2");
      default -> throw new IllegalArgumentException("Scenario " + scenario + " not recognized");
    }
  }
}
