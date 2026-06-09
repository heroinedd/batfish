package org.batfish.dataplane.ibdp;

import org.batfish.datamodel.BgpPeerConfigId;
import org.batfish.datamodel.BgpSessionProperties;

public class BgpSession {
  public final BgpPeerConfigId id1;
  public final BgpPeerConfigId id2;
  public final BgpSessionProperties properties;

  public BgpSession(BgpPeerConfigId id1, BgpPeerConfigId id2, BgpSessionProperties properties) {
    this.id1 = id1;
    this.id2 = id2;
    this.properties = properties;
  }
}
