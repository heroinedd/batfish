package org.batfish.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.logging.log4j.Logger;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.gml.GmlImporter;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class GmlUtil {
  private static final Logger LOGGER = SmoothieLogger.LOGGER;
  protected static final int MAX_WEIGHT = 100;

  public static final class Node {
    private final int id;
    private boolean internal;
    private final Map<String, Attribute> attributes;

    public Node(int id) {
      this.id = id;
      this.attributes = new HashMap<>();
    }

    public void addAttribute(String key, Attribute attribute) {
      if (key.equalsIgnoreCase("internal")) {
        this.internal = !attribute.getValue().equals("0");
      }
      attributes.put(key, attribute);
    }

    public int getId() {
      return id;
    }

    public boolean isInternal() {
      return internal;
    }

    public Map<String, Attribute> getAttributes() {
      return attributes;
    }

    @Override
    public String toString() {
      return String.format("v%d", id);
    }
  }

  public static final class Edge {
    private final HashMap<String, Attribute> attributes = new HashMap<>();

    public void addAttribute(String key, Attribute attribute) {
      attributes.put(key, attribute);
    }

    public HashMap<String, Attribute> getAttributes() {
      return attributes;
    }
  }

  public static SimpleWeightedGraph<Node, Edge> readTopology(String name, int seed) {
    return readTopology(
        SmoothieConfig.TOPOLOGIES_DIR.resolve(name + ".gml"),
        BatfishUtil.INPUT_BASE.resolve(name).resolve(name + ".json"),
        seed);
  }

  public static SimpleWeightedGraph<Node, Edge> readTopology(
      Path gmlPath, Path weightPath, int seed) {
    // read gml file content, escape illegal lines
    Reader reader = null;
    try {
      File gml = gmlPath.toFile();
      BufferedReader in = new BufferedReader(new FileReader(gml));
      List<String> lines =
          in.lines()
              .filter(line -> !line.toLowerCase().contains("id \""))
              .collect(Collectors.toList());
      reader = new StringReader(String.join("\n", lines));
    } catch (FileNotFoundException e) {
      LOGGER.error(e);
      System.exit(1);
    }

    // prepare graph
    SimpleWeightedGraph<Node, Edge> g = new SimpleWeightedGraph<>(Edge.class);
    g.setEdgeSupplier(Edge::new);

    // gml importer
    GmlImporter<Node, Edge> importer = new GmlImporter<>();
    importer.setVertexFactory(Node::new);
    importer.addVertexAttributeConsumer(
        (p, a) -> {
          if (p.getFirst() != null) p.getFirst().addAttribute(p.getSecond(), a);
        });
    importer.addEdgeAttributeConsumer(
        (p, a) -> {
          // it seems that for a duplicated edge, p.getFirst() = null, just ignore it
          if (p.getFirst() != null) p.getFirst().addAttribute(p.getSecond(), a);
        });
    importer.importGraph(g, reader);

    // read edge weights
    if (seed == -1) uniformEdgeWeight(g);
    else readEdgeWeights(weightPath, seed, g);
    return g;
  }

  protected static void readEdgeWeights(Path path, int seed, SimpleWeightedGraph<Node, Edge> g) {
    try {
      Map<Integer, GmlUtil.Node> nodes =
          g.vertexSet().stream().collect(Collectors.toMap(Node::getId, v -> v));
      JsonParser parser = new JsonParser();
      JsonObject root = (JsonObject) parser.parse(new FileReader(path.toFile()));
      root.get("" + seed)
          .getAsJsonObject()
          .entrySet()
          .forEach(
              entry -> {
                String edgeId = entry.getKey();
                GmlUtil.Node src = nodes.get(Integer.parseInt(edgeId.split("\\|")[0].strip()));
                GmlUtil.Node dst = nodes.get(Integer.parseInt(edgeId.split("\\|")[1].strip()));
                int weight = entry.getValue().getAsInt();
                GmlUtil.Edge edge =
                    ObjectUtils.firstNonNull(g.getEdge(src, dst), g.getEdge(dst, src));
                g.setEdgeWeight(edge, weight);
              });
    } catch (FileNotFoundException e) {
      randomEdgeWeight(g);
    }

    for (Edge edge : g.edgeSet()) {
      if (edge.getAttributes().containsKey("weight")) {
        g.setEdgeWeight(
            edge, (int) Float.parseFloat(edge.getAttributes().get("weight").getValue()));
      } else if (!g.getEdgeSource(edge).isInternal() || !g.getEdgeTarget(edge).isInternal()) {
        g.setEdgeWeight(edge, 1);
      }
    }
  }

  protected static void randomEdgeWeight(SimpleWeightedGraph<Node, Edge> g) {
    Random random = new Random();
    Set<Integer> seen = new HashSet<>();
    for (GmlUtil.Edge edge : g.edgeSet()) {
      int weight = random.nextInt(MAX_WEIGHT) + 1;
      while (seen.contains(weight)) {
        weight = random.nextInt(MAX_WEIGHT) + 1;
      }
      seen.add(weight);
      g.setEdgeWeight(edge, weight);
    }
  }

  protected static void uniformEdgeWeight(SimpleWeightedGraph<Node, Edge> g) {
    for (GmlUtil.Edge edge : g.edgeSet()) {
      g.setEdgeWeight(edge, 1);
    }
  }

  public record GraphAttributes(
      String name,
      int numVertices,
      int numEdges,
      int minDegree,
      int maxDegree,
      int avgDegree,
      int numExternals)
      implements Comparable<GraphAttributes> {

    @Override
    public int compareTo(@Nonnull GmlUtil.GraphAttributes o) {
      return Comparator.comparing(GraphAttributes::numVertices)
          .thenComparing(GraphAttributes::numEdges)
          .thenComparing(GraphAttributes::numExternals)
          .thenComparing(GraphAttributes::minDegree)
          .thenComparing(GraphAttributes::maxDegree)
          .thenComparing(GraphAttributes::avgDegree)
          .thenComparing(GraphAttributes::name)
          .compare(this, o);
    }

    @Override
    public String toString() {
      return String.format(
          "%s: number nodes - %d, number edges - %d, number external routers - %d, min degree - %d, max degree - %d, avg degree - %d",
          name, numVertices, numEdges, numExternals, minDegree, maxDegree, avgDegree);
    }
  }
}
