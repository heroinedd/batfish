package org.batfish.utils;

import org.apache.logging.log4j.LogManager;
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
 * smoothie.output.base
 * smoothie.traces.dir
 * smoothie.topologies.dir
 * smoothie.networks.example
 * smoothie.networks.internet2
 * smoothie.networks.cornetto
 * </pre>
 */
public final class SmoothieConfig {

  private static final Logger LOGGER = LogManager.getLogger(SmoothieConfig.class);
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

  public static Path outputBase() {
    return get("smoothie.output.base");
  }

  public static Path tracesDir() {
    return get("smoothie.traces.dir");
  }

  public static Path topologiesDir() {
    return get("smoothie.topologies.dir");
  }

  public static Path networksExample() {
    return get("smoothie.networks.example");
  }

  public static Path networksInternet2() {
    return get("smoothie.networks.internet2");
  }

  public static Path networksCornetto() {
    return get("smoothie.networks.cornetto");
  }
}
