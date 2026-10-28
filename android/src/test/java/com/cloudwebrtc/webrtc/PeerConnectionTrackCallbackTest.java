package com.cloudwebrtc.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.RtpParametersFixture;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PeerConnectionTrackCallbackTest {
  private final ExecutorService signalingThread = Executors.newSingleThreadExecutor();
  private final PeerConnection connection = mock(PeerConnection.class);
  private final EventChannel.EventSink sink = mock(EventChannel.EventSink.class);
  private PeerConnectionObserver observer;

  @Before
  public void setUp() {
    PeerConnection.RTCConfiguration config =
        new PeerConnection.RTCConfiguration(Collections.emptyList());
    config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
    observer = new PeerConnectionObserver(config, mock(StateProvider.class),
        mock(BinaryMessenger.class), "test-pc");
    observer.setPeerConnection(connection);
    observer.onListen(null, sink);
    when(connection.getTransceivers()).thenAnswer(invocation -> {
      assertSame("Transceiver wrappers must share the platform thread",
          Looper.getMainLooper().getThread(), Thread.currentThread());
      return Collections.emptyList();
    });
  }

  @After
  public void tearDown() throws Exception {
    observer.dispose();
    signalingThread.shutdownNow();
    signalingThread.awaitTermination(2, TimeUnit.SECONDS);
    shadowOf(Looper.getMainLooper()).idle();
  }

  private RtpReceiver receiver(String id) {
    MediaStreamTrack track = mock(MediaStreamTrack.class);
    when(track.id()).thenReturn("track-" + id);
    when(track.kind()).thenReturn("video");
    when(track.enabled()).thenReturn(true);
    when(track.state()).thenReturn(MediaStreamTrack.State.LIVE);
    RtpReceiver receiver = mock(RtpReceiver.class);
    when(receiver.id()).thenReturn(id);
    when(receiver.track()).thenReturn(track);
    when(receiver.getParameters()).thenReturn(RtpParametersFixture.empty());
    return receiver;
  }

  private void addTrackFromNative(RtpReceiver receiver) throws Exception {
    // Main/platform thread is waiting here. A blocking dispatch would deadlock.
    signalingThread.submit(() -> observer.onAddTrack(receiver, new MediaStream[0]))
        .get(2, TimeUnit.SECONDS);
  }

  @Test
  public void trackCallbackSharesPlatformThreadWithMethodCalls() throws Exception {
    addTrackFromNative(receiver("receiver"));
    verify(connection, never()).getTransceivers();

    observer.getTransceivers(mock(Result.class));
    shadowOf(Looper.getMainLooper()).idle();

    verify(connection, times(2)).getTransceivers();
    verify(sink).success(any());
  }

  @Test
  public void queuedEventKeepsTrackAndTransceiverWithoutReusingNativeCallbackObjects()
      throws Exception {
    RtpReceiver callbackReceiver = receiver("receiver");
    RtpReceiver transceiverReceiver = receiver("receiver");
    RtpSender sender = mock(RtpSender.class);
    when(sender.id()).thenReturn("sender");
    when(sender.getParameters()).thenReturn(RtpParametersFixture.empty());
    RtpTransceiver transceiver = mock(RtpTransceiver.class);
    when(transceiver.getMid()).thenReturn("video-mid");
    when(transceiver.getDirection()).thenReturn(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY);
    when(transceiver.getReceiver()).thenReturn(transceiverReceiver);
    when(transceiver.getSender()).thenReturn(sender);
    when(connection.getTransceivers()).thenReturn(Collections.singletonList(transceiver));

    addTrackFromNative(callbackReceiver);
    when(callbackReceiver.id()).thenThrow(new IllegalStateException("callback returned"));
    when(callbackReceiver.track()).thenThrow(new IllegalStateException("callback returned"));
    shadowOf(Looper.getMainLooper()).idle();

    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(sink).success(event.capture());
    Map<?, ?> payload = (Map<?, ?>) event.getValue();
    assertEquals("onTrack", payload.get("event"));
    assertEquals("track-receiver", ((Map<?, ?>) payload.get("track")).get("id"));
    assertEquals("video-mid", ((Map<?, ?>) payload.get("transceiver")).get("mid"));
  }

  @Test
  public void closeCancelsQueuedTrackEventBeforeDisposingConnection() throws Exception {
    addTrackFromNative(receiver("receiver"));
    observer.dispose();
    shadowOf(Looper.getMainLooper()).idle();

    verify(connection, never()).getTransceivers();
    verify(sink, never()).success(any());
  }

  @Test
  public void callbackArrivingDuringCloseCannotAccessClosedConnection() throws Exception {
    RtpReceiver receiver = receiver("receiver");
    doAnswer(invocation -> {
      addTrackFromNative(receiver);
      return null;
    }).when(connection).close();
    observer.close();
    shadowOf(Looper.getMainLooper()).idle();

    verify(connection, never()).getTransceivers();
    verify(sink, never()).success(any());
  }
}
