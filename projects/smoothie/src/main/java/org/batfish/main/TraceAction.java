package org.batfish.main;

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

      @Override
      public String toString() {
        return "IgpLinkWeight(" + source + " → " + target + ", weight=" + weight + ")";
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

      @Override
      public String toString() {
        return "BgpSession(" + source + " → " + target + ", " + sessionType + ")";
      }
    }

    /**
     * This is an ad hoc implementation according to Snowcap's encoding of route-map, and the
     * updating action (just changing local preference).
     */
    public static class BgpRouteMap extends ConfigExpr {
      public final int router;
      public final boolean incoming;
      public final int neighbor;
      public final int localPref;

      public BgpRouteMap(int router, String direction, int neighbor, int localPref) {
        this.router = router;
        this.incoming = direction.equalsIgnoreCase("incoming");
        this.neighbor = neighbor;
        this.localPref = localPref;
      }

      @Override
      public String toString() {
        return "BgpRouteMap(router="
            + router
            + ", "
            + (incoming ? "incoming" : "outgoing")
            + ", neighbor="
            + neighbor
            + ", localPref="
            + localPref
            + ")";
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

      @Override
      public String toString() {
        return "StaticRoute(router=" + router + ", " + prefix + " → " + target + ")";
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

    @Override
    public String toString() {
      return "Insert(" + expr + ")";
    }
  }

  public static class Remove extends TraceAction {
    public final ConfigExpr expr;

    public Remove(ConfigExpr expr) {
      this.expr = expr;
    }

    @Override
    public String toString() {
      return "Remove(" + expr + ")";
    }
  }

  public static class Update extends TraceAction {
    public final ConfigExpr from;
    public final ConfigExpr to;

    public Update(ConfigExpr from, ConfigExpr to) {
      this.from = from;
      this.to = to;
    }

    @Override
    public String toString() {
      return "Update(" + from + " → " + to + ")";
    }
  }
}
