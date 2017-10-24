/*
 * Copyright (C) 2017 Kai-Chung Yan (殷啟聰)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package chat.viska.android.demo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.FloatingActionButton;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import chat.viska.android.Application;
import chat.viska.android.R;
import chat.viska.android.XmppService;
import chat.viska.commons.DisposablesBin;
import chat.viska.commons.reactive.MutableReactiveObject;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.plugins.webrtc.WebRtcPlugin;
import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.MaybeSubject;
import java.util.EventObject;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;

public class CallingActivity extends Activity {

  private class PeerConnectionObserver implements PeerConnection.Observer {

    @Override
    public void onSignalingChange(@Nonnull final PeerConnection.SignalingState state) {}

    @Override
    public void onIceConnectionChange(final PeerConnection.IceConnectionState state) {
      Log.d("WebRTC", "IceConnectionState is " + state);
      if (state == PeerConnection.IceConnectionState.CONNECTED
          && peerConnection.signalingState() == PeerConnection.SignalingState.STABLE
          && callingState.getValue() != CallingState.IDLE) {
        callingState.changeValue(CallingState.STREAMING);
        progressState.changeValue(ProgressState.IDLE);
      } else if (state == PeerConnection.IceConnectionState.CLOSED) {
        progressState.changeValue(ProgressState.ENDED);
        setResult(RESULT_OK);
        finish();
      }
    }

    @Override
    public void onIceConnectionReceivingChange(final boolean b) {}

    @Override
    public void onIceGatheringChange(@Nonnull final PeerConnection.IceGatheringState state) {
      Log.d("WebRTC", "IceGatheringState is " + state);
      if (state == PeerConnection.IceGatheringState.COMPLETE
          && peerConnection.iceConnectionState() != PeerConnection.IceConnectionState.CONNECTED
          && progressState.getValue() != ProgressState.ENDED) {
        progressState.changeValue(ProgressState.IDLE);
        bin.add(xmpp.subscribe(
            it -> it.getSessions().get(localJid).getPluginManager().getPlugin(WebRtcPlugin.class).sendSdp(
                remoteJid,
                id,
                peerConnection.getLocalDescription(),
                ACTION_CALL_OUTBOUND.equals(getIntent().getAction())
            ),
            CallingActivity.this::fail
        ));
      }
    }

    @Override
    public void onIceCandidate(@Nonnull final IceCandidate candidate) {}

    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates) {}

    @Override
    public void onAddStream(final MediaStream stream) {}

    @Override
    public void onRemoveStream(final MediaStream stream) {}

    @Override
    public void onDataChannel(final DataChannel dataChannel) {}

    @Override
    public void onRenegotiationNeeded() {}

    @Override
    public void onAddTrack(final RtpReceiver rtpReceiver, final MediaStream[] mediaStreams) {}
  }

  private class SdpObserver implements org.webrtc.SdpObserver {

    @Override
    public void onCreateSuccess(@Nonnull final SessionDescription sdp) {
      peerConnection.setLocalDescription(this, sdp);
    }

    @Override
    public void onSetSuccess() {}

    @Override
    public void onCreateFailure(String s) {
      fail(new Exception(s));
    }

    @Override
    public void onSetFailure(String s) {
      fail(new Exception(s));
    }
  }

  private enum CallingState {
    IDLE,
    LOCAL_RINGING,
    REMOTE_RINGING,
    STREAMING
  }

  private enum ProgressState {
    ENDED,
    IDLE,
    NEGOTIATING
  }

  private static final MediaConstraints CONSTRAINTS = new MediaConstraints();

  /**
   * {@link Intent} Action: Receive a VoIP call.
   */
  public static final String ACTION_CALL_INBOUND = "chat.viska.intent.action.CALL_INBOUND";

  /**
   * {@link Intent} Action: Perform a VoIP call.
   */
  public static final String ACTION_CALL_OUTBOUND = "chat.viska.intent.action.CALL_OUTBOUND";

  /**
   * {@link Intent} Extra: JID in {@link String} of the local end.
   */
  public static final String EXTRA_LOCAL_JID = "local-jid";

  /**
   * {@link Intent} Extra:
   * <a href="https://datatracker.ietf.org/doc/draft-ietf-rtcweb-jsep">JSEP</a> content in
   * {@link String} sent from the remote end when receiving a call.
   */
  public static final String EXTRA_REMOTE_SDP = "chat.viska.intent.extra.REMOTE_SDP";

  /**
   * {@link Intent} Extra: Session ID in {@link String}. Required for an inbound call.
   */
  public static final String EXTRA_SESSION_ID = "chat.viska.intent.extra.SESSION_ID";

  static {
    CONSTRAINTS.mandatory.add(new MediaConstraints.KeyValuePair("audio", "true"));
    CONSTRAINTS.mandatory.add(new MediaConstraints.KeyValuePair("video", "false"));
  }

  private final ServiceConnection binding = new ServiceConnection() {

    @Override
    public void onServiceConnected(@Nonnull final ComponentName componentName,
                                   @Nonnull final IBinder iBinder) {
      xmpp.onSuccess(((XmppService.Binder) iBinder).getService());
    }

    @Override
    public void onServiceDisconnected(final ComponentName componentName) {
      xmpp.onComplete();
    }
  };

  private final MutableReactiveObject<CallingState> callingState = new MutableReactiveObject<>(
      CallingState.IDLE
  );
  private final MutableReactiveObject<ProgressState> progressState = new MutableReactiveObject<>(
      ProgressState.IDLE
  );
  private final DisposablesBin bin = new DisposablesBin();
  private final SdpObserver sdpObserver = new SdpObserver();
  private final MaybeSubject<XmppService> xmpp = MaybeSubject.create();
  private String id;

  private PeerConnection peerConnection;
  private Jid localJid = Jid.EMPTY;
  private Jid remoteJid = Jid.EMPTY;

  private void initializeOutboundCall() {
    id = UUID.randomUUID().toString();

    final Application application = (Application) getApplication();
    final PeerConnectionFactory factory = application.getWebRtcFactory();

    final MediaStream mediaStream = factory.createLocalMediaStream(UUID.randomUUID().toString());
    mediaStream.addTrack(factory.createAudioTrack(
            UUID.randomUUID().toString(),
            factory.createAudioSource(CONSTRAINTS)
    ));

    peerConnection = factory.createPeerConnection(
        application.getBuiltInIceServers(),
        CONSTRAINTS,
        new PeerConnectionObserver()
    );
    peerConnection.addStream(mediaStream);
    peerConnection.createOffer(sdpObserver, CONSTRAINTS);
  }

  private void initializeInboundCall() {
    id = getIntent().getStringExtra(EXTRA_SESSION_ID);

    final Application application = (Application) getApplication();
    final PeerConnectionFactory factory = application.getWebRtcFactory();

    peerConnection = factory.createPeerConnection(
        application.getBuiltInIceServers(),
        CONSTRAINTS,
        new PeerConnectionObserver()
    );
    peerConnection.setRemoteDescription(
        sdpObserver,
        new SessionDescription(
            SessionDescription.Type.OFFER,
            getIntent().getStringExtra(EXTRA_REMOTE_SDP)
        )
    );
  }

  private void fail(@Nonnull final Throwable ex) {
    runOnUiThread(() -> {
      Toast.makeText(this, ex.getLocalizedMessage(), Toast.LENGTH_LONG).show();
      finish();
    });
  }

  private void hang() {
    progressState.changeValue(ProgressState.ENDED);
    setResult(RESULT_OK);
    finish();
  }

  protected void onAnswerButtonClicked(final View view) {
    progressState.changeValue(ProgressState.NEGOTIATING);

    final PeerConnectionFactory factory = ((Application) getApplication()).getWebRtcFactory();
    final MediaStream stream = factory.createLocalMediaStream(UUID.randomUUID().toString());
    stream.addTrack(factory.createAudioTrack(
        UUID.randomUUID().toString(),
        factory.createAudioSource(CONSTRAINTS)
    ));
    peerConnection.addStream(stream);
    peerConnection.createAnswer(sdpObserver, new MediaConstraints());
  }

  protected void onHangButtonClicked(final View view) {
    xmpp.subscribe(it -> {
      it.getSessions().get(localJid).getPluginManager().getPlugin(WebRtcPlugin.class).closeSession(
          remoteJid,
          id
      );
      hang();
    });
  }

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_calling);

    final FloatingActionButton hangButton = findViewById(R.id.calling_hang);
    final FloatingActionButton answerButton = findViewById(R.id.calling_answer);
    final TextView progressLabel = findViewById(R.id.calling_label_progress);
    final ProgressBar progressBar = findViewById(R.id.calling_progress);

    final ViewGroup.LayoutParams centerButtomParams = answerButton.getLayoutParams();
    final ViewGroup.LayoutParams sideButtonParams = hangButton.getLayoutParams();

    localJid = new Jid(getIntent().getStringExtra(EXTRA_LOCAL_JID));
    ((TextView) findViewById(R.id.calling_local)).setText(
        localJid.toBareJid().toString()
    );

    remoteJid = new Jid(getIntent().getData().getSchemeSpecificPart());
    ((TextView) findViewById(R.id.calling_remote)).setText(
        remoteJid.toBareJid().toString()
    );

    callingState.getStream().observeOn(AndroidSchedulers.mainThread()).subscribe(state -> {
      switch (state) {
        case STREAMING:
          hangButton.setLayoutParams(centerButtomParams);
          hangButton.setSize(FloatingActionButton.SIZE_NORMAL);
          answerButton.setVisibility(View.GONE);
          break;
        case LOCAL_RINGING:
          hangButton.setLayoutParams(sideButtonParams);
          hangButton.setSize(FloatingActionButton.SIZE_MINI);
          answerButton.setVisibility(View.VISIBLE);
          break;
        case REMOTE_RINGING:
          hangButton.setLayoutParams(centerButtomParams);
          hangButton.setSize(FloatingActionButton.SIZE_NORMAL);
          answerButton.setVisibility(View.GONE);
          break;
        default:
          break;
      }
    });
    progressState.getStream().observeOn(AndroidSchedulers.mainThread()).subscribe(state -> {
      switch (state) {
        case IDLE:
          progressLabel.setVisibility(View.GONE);
          progressBar.setVisibility(View.GONE);
          break;
        case NEGOTIATING:
          progressLabel.setText(R.string.desc_calling_negotiating);
          progressLabel.setVisibility(View.VISIBLE);
          progressBar.setVisibility(View.VISIBLE);
          break;
        case ENDED:
          progressLabel.setText(R.string.desc_calling_ended);
          progressLabel.setVisibility(View.VISIBLE);
          progressBar.setVisibility(View.VISIBLE);
          break;
        default:
          break;
      }
    });

    xmpp.subscribe(it -> {
      final Flowable<EventObject> events = it
          .getSessions()
          .get(localJid)
          .getPluginManager()
          .getPlugin(WebRtcPlugin.class)
          .getEventStream();
      bin.add(
          events.ofType(WebRtcPlugin.SdpReceivedEvent.class).filter(
              event -> event.getId().equals(id)
          ).map(WebRtcPlugin.SdpReceivedEvent::getSdp).subscribe(
              sdp -> peerConnection.setRemoteDescription(sdpObserver, sdp)
          )
      );
      bin.add(
          events.ofType(WebRtcPlugin.SessionClosingEvent.class).filter(
              event -> event.getId().equals(id)
          ).subscribe(event -> hang())
      );
    }, this::fail);

    if (ACTION_CALL_OUTBOUND.equals(getIntent().getAction())) {
      ((TextView) findViewById(R.id.calling_label_remote)).setText(R.string.title_outbound_call);
      initializeOutboundCall();
      callingState.changeValue(CallingState.REMOTE_RINGING);
      progressState.changeValue(ProgressState.NEGOTIATING);
    } else if (ACTION_CALL_INBOUND.equals(getIntent().getAction())) {
      initializeInboundCall();
      callingState.changeValue(CallingState.LOCAL_RINGING);
    }
    bindService(new Intent(this, XmppService.class), binding, BIND_AUTO_CREATE);
  }

  @Override
  protected void onDestroy() {
    peerConnection.close();
    peerConnection.dispose();
    if (xmpp.hasValue()) {
      unbindService(binding);
    }
    bin.clear();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    moveTaskToBack(true);
  }
}