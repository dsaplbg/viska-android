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

package chat.viska.xmpp.plugins.webrtc;

import chat.viska.commons.DomUtils;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.Plugin;
import chat.viska.xmpp.Session;
import chat.viska.xmpp.Stanza;
import chat.viska.xmpp.StanzaErrorException;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.processors.FlowableProcessor;
import io.reactivex.processors.PublishProcessor;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.EventObject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

/**
 * Simple {@link Plugin} for demonstrating WebRTC. Sample stanzas:
 *
 * <pre>{@code
 * <iq type="set" id="..." to="jon@westeros.com/123">
 *   <webrtc xmlns="https://schemas.viska.chat/demo/webrtc" id="..." action="create">
 *     <sdp type="pranswer">
 *       <line>...</line>
 *       <line>...</line>
 *     </sdp>
 *     <ice-candidate sdpMid="..." sdpMLineIndex="...">
 *       <sdp>
 *         <line>...</line>
 *         <line>...</line>
 *       </sdp>
 *     </ice-candidate>
 *   </webrtc>
 * </iq>
 *
 * <iq type="set" id="..." to="jon@westeros.com/123">
 *   <webrtc xmlns="https://schemas.viska.chat/demo/webrtc" id="..." action="close"/>
 * </iq>
 * }</pre>
 */
public class WebRtcPlugin implements Plugin {

  public class IceCandidateReceivedEvent extends EventObject {

    private final IceCandidate candidate;
    private final String id;
    private final boolean creating;

    public IceCandidateReceivedEvent(@Nonnull final IceCandidate candidate,
                                     @Nonnull final String id,
                                     final boolean creating) {
      super(WebRtcPlugin.this);
      this.candidate = candidate;
      this.id = id;
      this.creating = creating;
    }

    @Nonnull
    public IceCandidate getCandidate() {
      return candidate;
    }

    @Nonnull
    public String getId() {
      return id;
    }
  }

  public class SdpReceivedEvent extends EventObject {

    private final SessionDescription sdp;
    private final String id;
    private final boolean creating;
    private final Jid remoteJid;

    public SdpReceivedEvent(@Nonnull final SessionDescription sdp,
                            @Nonnull final String id,
                            @Nonnull final Jid remoteJid,
                            final boolean creating) {
      super(WebRtcPlugin.this);
      this.sdp = sdp;
      this.id = id;
      this.creating = creating;
      this.remoteJid = remoteJid;
    }

    @Nonnull
    public SessionDescription getSdp() {
      return sdp;
    }

    @Nonnull
    public String getId() {
      return id;
    }

    public boolean isCreating() {
      return creating;
    }

    @Nonnull
    public Jid getRemoteJid() {
      return remoteJid;
    }
  }

  public class SessionClosingEvent extends EventObject {

    private final String id;

    public SessionClosingEvent(String id) {
      super(WebRtcPlugin.this);
      this.id = id;
    }

    @Nonnull
    public String getId() {
      return id;
    }
  }

  // Android still does not support \R from Perl 5
  private static final String REGEX_LINE_BREAK = "\\u000D\\u000A|[\\u000A\\u000B\\u000C\\u000D\\u0085\\u2028\\u2029]";
  public static final String XMLNS = "https://schemas.viska.chat/demo/webrtc";

  private final FlowableProcessor<EventObject> eventStream;
  private Session.PluginContext context;

  private void consumeIq(@Nonnull final Stanza iq) {
    final Element webrtcElement = (Element) iq.getXml().getDocumentElement().getFirstChild();
    final String id = webrtcElement.getAttribute("id");
    try {
      if (StringUtils.isBlank(id)) {
        throw new StanzaErrorException(
            iq,
            StanzaErrorException.Condition.BAD_REQUEST,
            StanzaErrorException.Type.MODIFY,
            "No session id found.",
            null,
            null,
            null
        );
      }
      final String actionAttribute = webrtcElement.getAttribute("action");
      if ("close".equals(actionAttribute)) {
        this.eventStream.onNext(new SessionClosingEvent(id));
      } else {
        final boolean creating = "create".equals(actionAttribute);
        for (Node node : DomUtils.convertToList(webrtcElement.getChildNodes())) {
          if ("sdp".equals(node.getLocalName())) {
            final SessionDescription.Type type;
            try {
              type = SessionDescription.Type.fromCanonicalForm(
                  ((Element) node).getAttribute("type")
              );
            } catch (Exception ex) {
              throw new StanzaErrorException(
                  iq,
                  StanzaErrorException.Condition.BAD_REQUEST,
                  StanzaErrorException.Type.MODIFY,
                  "SDP type unknown.",
                  null,
                  null,
                  null
              );
            }
            this.eventStream.onNext(new SdpReceivedEvent(
                new SessionDescription(type, convertSdpElementsToString(node)),
                id,
                iq.getSender(),
                creating
            ));
          } else if ("ice-candidate".equals(node.getLocalName())) {
            consumeIceCandidate((Element) node, id, creating);
          }
        }
      }
    } catch (StanzaErrorException ex) {
      this.context.sendError(ex);
    }
  }

  private void consumeIceCandidate(@Nonnull final Element iceCandidateElement,
                                   @Nonnull final String id,
                                   final boolean creating) {
    final Node sdpElement = Observable.fromIterable(
        DomUtils.convertToList(iceCandidateElement.getChildNodes())
    ).filter(it -> "sdp".equals(it.getLocalName())).firstElement().blockingGet();
    final String sdp = sdpElement == null ? "" : convertSdpElementsToString(sdpElement);
    this.eventStream.onNext(new IceCandidateReceivedEvent(
        new IceCandidate(
            iceCandidateElement.getAttribute("sdpMid"),
            Integer.getInteger(iceCandidateElement.getAttribute("sdpMLineIndex")),
            sdp
        ),
        id,
        creating
    ));
  }

  @Nonnull
  private String convertSdpElementsToString(@Nonnull final Node sdpElement) {
    final StringBuilder sdp = new StringBuilder();
    Observable.fromIterable(
        DomUtils.convertToList(sdpElement.getChildNodes())
    ).filter(
        it -> "line".equals(it.getLocalName())
    ).filter(
        it -> StringUtils.isNotBlank(it.getTextContent())
    ).map(Node::getTextContent).subscribe(it -> {
      sdp.append(it);
      sdp.append(System.lineSeparator());
    });
    return sdp.toString();
  }

  public WebRtcPlugin() {
    final FlowableProcessor<EventObject> unsafeStream = PublishProcessor.create();
    this.eventStream = unsafeStream.toSerialized();
  }

  @Nonnull
  public Completable sendSdp(@Nonnull final Jid recipient,
                             @Nonnull final String id,
                             @Nonnull final SessionDescription sdp,
                             final boolean creating) {
    final Document iq = Stanza.getIqTemplate(
        Stanza.IqType.SET,
        UUID.randomUUID().toString(),
        getSession().getNegotiatedJid(),
        recipient
    );
    final Element webrtcElement = (Element) iq.getDocumentElement().appendChild(
        iq.createElementNS(XMLNS, "webrtc")
    );
    webrtcElement.setAttribute("id", id);
    if (creating) {
      webrtcElement.setAttribute("action", "create");
    }
    final Element sdpElement = (Element) webrtcElement.appendChild(iq.createElement("sdp"));
    if (sdp.type != null) {
      sdpElement.setAttribute("type", sdp.type.canonicalForm());
    }
    for (String line : sdp.description.split(REGEX_LINE_BREAK)) {
      final Node node = sdpElement.appendChild(iq.createElement("line"));
      node.setTextContent(line);
    }
    return this.context.sendIq(new Stanza(iq)).getResponse().toSingle().toCompletable();
  }

  @Nonnull
  public Completable sendIceCandidates(@Nonnull final Jid recipient,
                                       @Nonnull final String id,
                                       @Nonnull final Collection<IceCandidate> candidates) {
    final Document iq = Stanza.getIqTemplate(
        Stanza.IqType.SET,
        UUID.randomUUID().toString(),
        getSession().getNegotiatedJid(),
        recipient
    );
    final Element webrtc = (Element) iq.getDocumentElement().appendChild(
        iq.createElementNS(XMLNS, "webrtc")
    );
    webrtc.setAttribute("id", id);
    for (IceCandidate candidate : candidates) {
      final Element candidateElement = (Element) webrtc.appendChild(iq.createElement(
          "ice-candidate")
      );
      candidateElement.setAttribute("sdpMLineIndex", Integer.toString(candidate.sdpMLineIndex));
      if (StringUtils.isNotBlank(candidate.sdpMid)) {
        candidateElement.setAttribute("sdpMid", candidate.sdpMid);
      }
      if (StringUtils.isNotBlank(candidate.sdp)) {
        final Element sdpElement = (Element) candidateElement.appendChild(
            iq.createElement("sdp")
        );
        for (String line : candidate.sdp.split(REGEX_LINE_BREAK)) {
          final Node node = sdpElement.appendChild(iq.createElement("line"));
          node.setTextContent(line);
        }
      }
    }
    return this.context.sendIq(new Stanza(iq)).getResponse().toSingle().toCompletable();
  }

  @Nonnull
  public Completable closeSession(@Nonnull final Jid recipient, @Nonnull final String id) {
    final Document iq = Stanza.getIqTemplate(
        Stanza.IqType.SET,
        UUID.randomUUID().toString(),
        getSession().getNegotiatedJid(),
        recipient
    );
    final Element webrtcElement = (Element) iq.getDocumentElement().appendChild(
        iq.createElementNS(XMLNS, "webrtc")
    );
    webrtcElement.setAttribute("id", id);
    webrtcElement.setAttribute("action", "close");
    return this.context.sendIq(new Stanza(iq)).getResponse().toSingle().toCompletable();
  }

  @Nonnull
  public Flowable<EventObject> getEventStream() {
    return eventStream;
  }

  @Nonnull
  @Override
  public Set<Class<? extends Plugin>> getDependencies() {
    return Collections.emptySet();
  }

  @Nonnull
  @Override
  public Set<String> getFeatures() {
    return Collections.singleton(XMLNS);
  }

  @Nonnull
  @Override
  public Set<Map.Entry<String, String>> getSupportedIqs() {
    return Collections.singleton(new AbstractMap.SimpleImmutableEntry<>(XMLNS, "webrtc"));
  }

  @Override
  public void onApplying(@Nonnull final Session.PluginContext context) {
    this.context = context;
    context.getInboundStanzaStream()
        .filter(it -> it.getIqType() == Stanza.IqType.SET)
        .filter(it -> XMLNS.equals(it.getIqNamespace()))
        .filter(it -> "webrtc".equals(it.getIqName()))
        .subscribe(this::consumeIq);
  }

  @Nonnull
  @Override
  public Session getSession() {
    return context.getSession();
  }
}
