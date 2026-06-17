package org.batfish.dataplane.ibdp;

import org.batfish.datamodel.BgpPeerConfig;
import org.batfish.datamodel.BgpPeerConfigId;
import org.batfish.datamodel.BgpSessionProperties;

public record BgpSession(
    BgpPeerConfigId localId,
    BgpPeerConfigId remoteId,
    BgpSessionProperties properties,
    BgpPeerConfig config) {

  public static BgpSession remove(BgpPeerConfigId local, BgpPeerConfigId remote) {
    return new BgpSession(local, remote, null, null);
  }
}
