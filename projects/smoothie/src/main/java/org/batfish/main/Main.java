package org.batfish.main;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Logger;
import org.batfish.datamodel.Configuration;
import org.batfish.utils.SmoothieConfig;
import org.batfish.utils.SmoothieLogger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class Main {
  private static final Logger LOGGER = SmoothieLogger.LOGGER;

  public static boolean snowcap = true;

  /**
   * Parses a trace file named {@code <name>-<suffix>.json} and logs the result.
   *
   * @return a pair of (subnetworks, steps); each subnetwork is a list of node IDs with the first
   *     one being the reflector
   */
  private static Pair<List<List<Integer>>, List<TraceParser.Step>> loadTrace(
      String name, String suffix) {
    try {
      Path tracePath;
      TraceParser parser;
      if (snowcap) {
        tracePath = SmoothieConfig.SNOWCAP_TRACES_DIR.resolve(name + "-" + suffix + ".json");
        parser = new SnowcapTraceParser();
      } else {
        tracePath = SmoothieConfig.conpannaTracesDir().resolve(name + "-" + suffix + "-fd-plan.json");
        parser = new ConPannaTraceParser();
      }
      Pair<List<List<Integer>>, List<TraceParser.Step>> parsed = parser.parse(tracePath);
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

    TraceExecutor executor = new TraceExecutor(name, initialConfigs, finalConfigs);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - executor.getFinalTime()) / 1e9;
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

    TraceExecutor executor = new TraceExecutor(name, initialConfigs, finalConfigs);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - executor.getFinalTime()) / 1e9;
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
    TraceExecutor executor = new TraceExecutor(name, initialConfigs, finalConfigs);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - executor.getFinalTime()) / 1e9;
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
    TraceExecutor executor = new TraceExecutor(name, configs, null);
    executor.execute(steps);

    double duration = (System.nanoTime() - start - executor.getFinalTime()) / 1e9;
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
