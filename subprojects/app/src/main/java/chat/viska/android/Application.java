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

package chat.viska.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import chat.viska.R;
import chat.viska.commons.DomUtils;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;

public class Application extends android.app.Application {

  public static final String KEY_NOTIF_CHANNEL_MESSAGES = "messages";
  public static final String KEY_NOTIF_CHANNEL_SYSTEM = "system";
  public static final String KEY_PREF_FIRST_RUN = "first-run";

  private final List<PeerConnection.IceServer> iceServers = new ArrayList<>();
  private PeerConnectionFactory webRtcFactory;

  private void initializeNotificationChannels() {
    if (Build.VERSION.SDK_INT >= 26) {
      final NotificationManager manager = getSystemService(NotificationManager.class);

      final NotificationChannel systemChannel = new NotificationChannel(
          KEY_NOTIF_CHANNEL_SYSTEM,
          getString(R.string.title_notif_channel_system),
          NotificationManager.IMPORTANCE_LOW
      );
      systemChannel.setDescription(getString(R.string.desc_notif_channel_system));
      systemChannel.enableVibration(false);
      systemChannel.enableLights(false);
      systemChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      systemChannel.setShowBadge(false);
      manager.createNotificationChannel(systemChannel);

      final NotificationChannel messagingChannel = new NotificationChannel(
          KEY_NOTIF_CHANNEL_MESSAGES,
          getString(R.string.title_notif_channel_messaging),
          NotificationManager.IMPORTANCE_HIGH
      );
      systemChannel.setDescription(getString(R.string.desc_notif_channel_messaging));
      systemChannel.enableVibration(true);
      systemChannel.enableLights(true);
      systemChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
      systemChannel.setShowBadge(true);
      manager.createNotificationChannel(messagingChannel);
    }
  }

  private void initializeIceServers() {
    final Document xml;
    try {
      xml = DomUtils.readDocument(getResources().openRawResource(R.raw.ice));
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    synchronized (iceServers) {
      iceServers.clear();
      for (Node it : DomUtils.convertToList(xml.getDocumentElement().getElementsByTagName("url"))) {
        final Element element = (Element) it;
        final PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(
            element.getTextContent()
        );
        final String username = element.getAttribute("username");
        if (StringUtils.isNotBlank(username)) {
          builder.setUsername(username);
        }
        final String password = element.getAttribute("password");
        if (StringUtils.isNotBlank(password)) {
          builder.setPassword(password);
        }
        iceServers.add(builder.createIceServer());
      }
    }
  }

  private void initializeWebRtc() {
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
    );
    this.webRtcFactory = new PeerConnectionFactory(new PeerConnectionFactory.Options());
  }

  @Nonnull
  public synchronized PeerConnectionFactory getWebRtcFactory() {
    if (webRtcFactory == null) {
      initializeWebRtc();
    }
    return webRtcFactory;
  }

  @Nonnull
  public List<PeerConnection.IceServer> getBuiltInIceServers() {
    synchronized (iceServers) {
      if (iceServers.isEmpty()) {
        initializeIceServers();
      }
      return iceServers;
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    initializeNotificationChannels();
  }
}
