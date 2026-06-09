package org.batfish.main;

import com.fasterxml.jackson.databind.JsonNode;

/** A modification to apply to the network configuration. Mirrors Rust's {@code ConfigModifier}. */
public abstract class TraceAction {

  // ---------------------------------------------------------------------------
  // SessionType
  // ---------------------------------------------------------------------------

  public enum SessionType {
    IBGP_PEER,
    IBGP_CLIENT,
    EBGP,
  }

  // ---------------------------------------------------------------------------
  // ConfigExpr — mirrors Rust's ConfigExpr enum
  // ---------------------------------------------------------------------------

  public abstract static class ConfigExpr {

    public static class IgpLinkWeight extends ConfigExpr {
      public final int source;
      public final int target;
      public final double weight;

      public IgpLinkWeight(int source, int target, double weight) {
        this.source = source;
        this.target = target;
        this.weight = weight;
      }
    }

    public static class BgpSession extends ConfigExpr {
      public final int source;
      public final int target;
      public final SessionType sessionType;

      public BgpSession(int source, int target, SessionType sessionType) {
        this.source = source;
        this.target = target;
        this.sessionType = sessionType;
      }
    }

    public static class BgpRouteMap extends ConfigExpr {
      public final int router;
      public final String direction;
      public final JsonNode map;

      public BgpRouteMap(int router, String direction, JsonNode map) {
        this.router = router;
        this.direction = direction;
        this.map = map;
      }
    }

    public static class StaticRoute extends ConfigExpr {
      public final int router;
      public final String prefix;
      public final int target;

      public StaticRoute(int router, String prefix, int target) {
        this.router = router;
        this.prefix = prefix;
        this.target = target;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // TraceAction subclasses
  // ---------------------------------------------------------------------------

  public static class Insert extends TraceAction {
    public final ConfigExpr expr;

    public Insert(ConfigExpr expr) {
      this.expr = expr;
    }
  }

  public static class Remove extends TraceAction {
    public final ConfigExpr expr;

    public Remove(ConfigExpr expr) {
      this.expr = expr;
    }
  }

  public static class Update extends TraceAction {
    public final ConfigExpr from;
    public final ConfigExpr to;

    public Update(ConfigExpr from, ConfigExpr to) {
      this.from = from;
      this.to = to;
    }
  }

}
