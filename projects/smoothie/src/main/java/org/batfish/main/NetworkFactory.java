package org.batfish.main;

import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.datamodel.Configuration;
import org.batfish.storage.StorageProvider;
import org.batfish.utils.BatfishUtil;
import org.batfish.utils.ResultPrinter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class NetworkFactory {
  private static final Logger LOGGER = LogManager.getLogger(NetworkFactory.class);

  public static Triple<Path, StorageProvider, Batfish> zooFromVI(String name, boolean fullMesh) {
    Map<String, Configuration> configurations = TopologyZoo.init(name, fullMesh, null);
    return BatfishUtil.getBatfishFromConfiguration(
        BatfishUtil.OUTPUT_BASE, name, null, new TreeMap<>(configurations), null, false);
  }

  public static Triple<Path, StorageProvider, Batfish> zooFromVSB(String name) {
    Map<String, String> vsbs = TopologyZoo.synthesizeCiscoConfigurations(name, false, null);
    return BatfishUtil.getBatfishFromTestrigText(
        BatfishUtil.OUTPUT_BASE, name, BatfishUtil.timestamp(), vsbs, null, false);
  }

  public static Triple<Path, StorageProvider, Batfish> from(String configs, String name)
      throws IOException {
    Path path = Paths.get(configs);
    List<String> files =
        Arrays.stream(Objects.requireNonNull(path.toFile().list()))
            .map(f -> path.resolve(f).toString())
            .toList();
    Map<String, String> vsbs = BatfishUtil.getTextFromConfigs(files);
    return BatfishUtil.getBatfishFromTestrigText(
        BatfishUtil.OUTPUT_BASE.getParent(), name, BatfishUtil.timestamp(), vsbs, null, false);
  }

  public static Triple<Path, StorageProvider, Batfish> example() {
    Map<String, String> configurations = new TreeMap<>();
    Path folder = Paths.get("/Users/wangdan/ANTS/batfish/networks/example/candidate/configs");
    for (String name : Objects.requireNonNull(folder.toFile().list())) {
      try (BufferedReader br = new BufferedReader(new FileReader(folder.resolve(name).toFile()))) {
        configurations.put(name.split("\\.")[0], br.lines().collect(Collectors.joining("\n")));
      } catch (Exception e) {
        LOGGER.error(e);
      }
    }
    return BatfishUtil.getBatfishFromTestrigText(
        BatfishUtil.OUTPUT_BASE.getParent(),
        "example",
        BatfishUtil.timestamp(),
        configurations,
        null,
        false);
  }

  public static void simulate(Triple<Path, StorageProvider, Batfish> pair) {
    long start = System.nanoTime();
    Batfish batfish = pair.getRight();
    batfish.computeDataPlane(batfish.getSnapshot());
    batfish.bddLoopDetection(batfish.getSnapshot());
    ResultPrinter.printSnapshotResult(
        batfish, pair.getLeft(), true, true, true, false, false, false, false);
    System.out.printf("%s, %f\n", batfish.getContainerName(), (System.nanoTime() - start) / 1e9);
  }

  public static void main(String[] args) throws IOException {
    // String name = args.length > 0 ? args[0] : "Aconet";
    // boolean fullMesh = args.length > 1 && args[1].equalsIgnoreCase("true");
    // simulate(zooFromVI(name, fullMesh));

    String internet2 =
        "/Users/wangdan/ANTS/batfish/projects/smoothie/inputs/internet2-bagpipe-cleaned/configs";
    String deltacom =
        "/Users/wangdan/ANTS/cornetto/dataset/main_dataset/scenario-055/final_configs/configs";
    simulate(from(deltacom, "deltacom"));
  }
}
