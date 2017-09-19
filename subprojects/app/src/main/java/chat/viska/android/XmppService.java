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

import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;
import chat.viska.android.demo.MainActivity;
import chat.viska.xmpp.Connection;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.NettyTcpSession;
import chat.viska.xmpp.NettyWebSocketSession;
import chat.viska.xmpp.Session;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;

public class XmppService extends Service {

  public class Binder extends android.os.Binder {

    @Nonnull
    public XmppService getService() {
      return XmppService.this;
    }
  }

  @GuardedBy("itself")
  private final HashMap<Jid, Session> sessions = new HashMap<>();
  private final IBinder binder = new Binder();
  private final OnAccountsUpdateListener accountsListener = accounts -> {
    final List<Jid> live =  Observable
        .fromArray(accounts)
        .filter(it -> getString(R.string.api_account_type).equals(it.type))
        .map(it -> new Jid(it.name))
        .onErrorReturnItem(new Jid("", "", ""))
        .toList().blockingGet();
    synchronized (sessions) {
      final Set<Jid> jidsToRemove = new HashSet<>(sessions.keySet());
      jidsToRemove.removeAll(live);
      for (Jid it : jidsToRemove) {
        final Session sessionToRemove = sessions.get(it);
        if (sessionToRemove != null) {
          sessionToRemove
              .dispose()
              .subscribeOn(Schedulers.io())
              .subscribe();
        }
      }
    }
  };
  private AccountManager manager;

  private boolean isNeeded() {
    return Observable.fromArray(manager.getAccountsByType(getString(R.string.api_account_type))).any(
        account -> "true".equals(
            manager.getUserData(account, getString(R.string.api_account_enabled))
        )
    ).blockingGet();
  }

  private void startForeground() {
    final Notification.Builder builder = Build.VERSION.SDK_INT >= 26
        ? new Notification.Builder(this, getString(R.string.api_notif_channel_system))
        : new Notification.Builder(this);
    builder
        .setContentTitle(getString(R.string.title_app_running))
        .setContentText(getString(R.string.desc_app_running, this.sessions.size()))
        .setContentIntent(PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT
        ))
        .setSmallIcon(R.drawable.icon)
        .setOngoing(true);
    startForeground(R.id.notif_foreground, builder.build());
  }

  @Nonnull
  private Session constructSession(final Jid jid, final Connection connection) throws Exception {
    final Session session;
    if (connection.getProtocol() == Connection.Protocol.TCP) {
      session = new NettyTcpSession(jid, null, connection, false);
    } else if (connection.getProtocol() == Connection.Protocol.WEBSOCKET) {
      session = new NettyWebSocketSession(jid, null, connection, false);
    } else {
      throw new Exception(getString(R.string.desc_server_uses_unsupported_protocol));
    }

    if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
      session.getLogger().setLevel(Level.WARNING);
    } else {
      session.getLogger().setLevel(Level.ALL);
    }
    final Handler loggingHandler = new LogcatHandler(session);
    loggingHandler.setLevel(Level.ALL);
    session.getLogger().addHandler(loggingHandler);

    session
        .getState()
        .getStream()
        .filter(it -> it == Session.State.DISPOSED)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(it -> {
          synchronized (sessions) {
            sessions.remove(jid);
            if (sessions.size() == 0) {
              stopForeground(true);
            }
          }
        });
    session
        .getState()
        .getStream()
        .filter(it -> it == Session.State.ONLINE)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(it -> {
          synchronized (sessions) {
            sessions.put(jid, session);
            startForeground();
          }
        });
    return session;
  }

  @Nonnull
  public Map<Jid, Session> getSessions() {
    return Collections.unmodifiableMap(sessions);
  }

  @Nonnull
  public Completable login(@Nonnull final Jid jid, @Nonnull final String password) {
    startForeground();
    return Connection
        .queryAll(jid.getDomainPart(), null)
        .flatMapMaybe(it -> Observable.fromIterable(it).filter(Connection::isTlsEnabled).firstElement())
        .doOnComplete(() -> {
          throw new Exception(getString(R.string.desc_server_has_no_secure_connection));
        })
        .map(connection -> constructSession(jid, connection))
        .flatMapCompletable(session -> session.login(password))
        .doOnError(ex -> stopForeground(true));
  }

  @Override
  public IBinder onBind(final Intent intent) {
    return binder;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    try {
      manager = AccountManager.get(this);
    } catch (SecurityException ex) {
      Toast.makeText(this, R.string.desc_permission_error_accounts, Toast.LENGTH_LONG).show();
      stopSelf();
      return;
    }
    if (isNeeded()) {
      startForeground();
    }
    manager.addOnAccountsUpdatedListener(accountsListener, null, true);
  }

  @Override
  public void onDestroy() {
    manager.removeOnAccountsUpdatedListener(accountsListener);
    super.onDestroy();
  }
}
