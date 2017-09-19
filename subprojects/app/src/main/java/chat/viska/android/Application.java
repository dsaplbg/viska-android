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

public class Application extends android.app.Application {

  private void initializeNotificationChannels() {
    if (Build.VERSION.SDK_INT >= 26) {
      final NotificationManager manager = getSystemService(NotificationManager.class);

      final NotificationChannel systemChannel = new NotificationChannel(
          getString(R.string.api_notif_channel_system),
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
          getString(R.string.api_notif_channel_messaging),
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

  @Override
  public void onCreate() {
    super.onCreate();
    initializeNotificationChannels();
  }
}
