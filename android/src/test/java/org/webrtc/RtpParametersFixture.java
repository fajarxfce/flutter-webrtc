package org.webrtc;

import java.util.Collections;

/** Builds native-free RTP parameters through WebRTC's package constructor. */
public final class RtpParametersFixture {
  public static RtpParameters empty() {
    return new RtpParameters("test", null, new RtpParameters.Rtcp("test", true),
        Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
  }
}
