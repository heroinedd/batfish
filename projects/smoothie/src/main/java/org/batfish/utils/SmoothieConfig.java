package org.batfish.utils;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads runtime configuration from {@code smoothie.properties} on the classpath (placed under
 * {@code src/main/resources/} in the smoothie module).
 *
 * <p>If the file is absent or a required key is missing the program exits with an error. All paths
 * must be specified as absolute paths or with a leading {@code ~/} (expanded to the user home
 * directory). There is no implicit base directory so the config works correctly under any build
 * system, including Bazel.
 *
 * <p>Required keys:
 *
 * <pre>
 * smoothie.input.base
 * conpanna.traces.dir
 * smoothie.networks.cornetto
 * </pre>
 *
 * <p>Paths co-located in the smoothie module (under {@code topology-zoo/}) are hard-coded relative
 * to the workspace root and exposed as constants. Bazel makes them available on the runfiles path
 * during {@code bazel run}; when running via {@code java -jar}, the working directory must be the
 * workspace root.
 */
public final class SmoothieConfig {
  private static final Logger LOGGER = SmoothieLogger.LOGGER;
  private static final Properties PROPS = new Properties();

  static {
    try (InputStream is =
        SmoothieConfig.class.getClassLoader().getResourceAsStream("smoothie.properties")) {
      if (is == null) {
        LOGGER.fatal("smoothie.properties not found on classpath — cannot continue");
        System.exit(1);
      }
      PROPS.load(is);
      LOGGER.info("Loaded smoothie config from classpath:smoothie.properties");
    } catch (IOException e) {
      LOGGER.fatal("Failed to load smoothie.properties: {}", e.getMessage());
      System.exit(1);
    }
  }

  private SmoothieConfig() {}

  private static Path get(String key) {
    String value = PROPS.getProperty(key);
    if (value == null) {
      LOGGER.fatal(
          "Required property '{}' is not set in smoothie.properties — cannot continue", key);
      System.exit(1);
    }
    if (value.startsWith("~/")) {
      value = System.getProperty("user.home") + value.substring(1);
    }
    return Paths.get(value);
  }

  public static Path inputBase() {
    return get("smoothie.input.base");
  }

  public static final Path OUTPUT_BASE =
      Paths.get("projects/smoothie/outputs/topology-zoo");

  public static final Path TOPOLOGIES_DIR =
      Paths.get("projects/smoothie/topology-zoo/gml");

  public static final Path SNOWCAP_TRACES_DIR =
      Paths.get("projects/smoothie/topology-zoo/traces");

  public static final Path METIS_DIR =
      Paths.get("projects/smoothie/topology-zoo/metis");

  public static Path conpannaTracesDir() {
    return get("conpanna.traces.dir");
  }

  public static final Path NETWORKS_EXAMPLE =
      Paths.get("networks/example/candidate/configs");

  public static final Path NETWORKS_INTERNET2 =
      Paths.get("projects/smoothie/inputs/internet2-bagpipe-cleaned/configs");

  public static Path networksCornetto() {
    return get("smoothie.networks.cornetto");
  }
}
