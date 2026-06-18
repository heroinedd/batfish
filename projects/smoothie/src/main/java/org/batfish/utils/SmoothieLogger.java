package org.batfish.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Single shared logger for all smoothie classes, configured under the name {@code "smoothie"}. */
public final class SmoothieLogger {
  public static final Logger LOGGER = LogManager.getLogger("smoothie");

  private SmoothieLogger() {}
}
