package org.batfish.main;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;

/** Parses a trace file into subnetworks and a list of {@link Step}s. */
public interface TraceParser {

  /**
   * One atomic step in the trace: a list of actions, whether this step is an undo, and whether it
   * succeeded.
   */
  class Step {
    public final List<TraceAction> actions;
    public final boolean isUndo;
    public final boolean success;

    public Step(List<TraceAction> actions, boolean isUndo, boolean success) {
      this.actions = actions;
      this.isUndo = isUndo;
      this.success = success;
    }
  }

  /**
   * Parses the trace file at {@code path} and returns a pair of (subnetworks, steps). Each
   * subnetwork is a list of node IDs; the first node in each list is that subnetwork's reflector.
   */
  Pair<List<List<Integer>>, List<Step>> parse(Path path) throws IOException;
}
