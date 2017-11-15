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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;
import chat.viska.R;
import chat.viska.android.demo.CallingActivity;
import chat.viska.commons.reactive.MutableReactiveObject;
import chat.viska.commons.reactive.ReactiveObject;
import chat.viska.xmpp.Connection;
import chat.viska.xmpp.DnsQueryException;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.Session;
import chat.viska.xmpp.StandardSession;
import chat.viska.xmpp.plugins.BasePlugin;
import chat.viska.xmpp.plugins.webrtc.WebRtcPlugin;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import java.net.InetAddress;
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
  private final HashMap<Jid, StandardSession> sessions = new HashMap<>();
  private final Binder binder = new Binder();
  private final MutableReactiveObject<Boolean> isSyncingAccounts = new MutableReactiveObject<>(false);
  private final MutableReactiveObject<Boolean> hasInternet = new MutableReactiveObject<>(false);
  private AccountManager accountManager;

  private final OnAccountsUpdateListener accountsListener = accounts -> {
    isSyncingAccounts().getStream().filter(it -> !it).firstElement().subscribe(it -> {
      if (hasInternet.getValue()) {
        syncAllAccounts();
      }
    });
  };
  private final ConnectivityManager.NetworkCallback networkListener = new ConnectivityManager.NetworkCallback() {

    @Override
    public void onAvailable(Network network) {
      super.onAvailable(network);
      hasInternet.setValue(true);
      syncAllAccounts();
    }

    @Override
    public void onLost(Network network) {
      super.onLost(network);
      hasInternet.setValue(false);
      for (StandardSession session : sessions.values()) {
        session.killConnection().subscribeOn(Schedulers.io()).subscribe();
      }
    }
  };

  @Nonnull
  public String convertToErrorMessage(@Nonnull final Throwable cause) {
    if (cause instanceof DnsQueryException) {
      return getString(R.string.dns_query_error);
    } else {
      return cause.getLocalizedMessage();
    }
  }

  /**
   * Puts the service into foreground and refresh the notification description.
   */
  private void startForeground() {
    final Notification.Builder builder = Build.VERSION.SDK_INT >= 26
        ? new Notification.Builder(this, Application.KEY_NOTIF_CHANNEL_SYSTEM)
        : new Notification.Builder(this);
    builder
        .setContentTitle(getString(R.string.title_app_running))
        .setSmallIcon(R.drawable.icon)
        .setOngoing(true);
    builder.setContentText(
        getString(
            R.string.app_running,
            Observable.fromIterable(this.sessions.values()).filter(
                it -> it.getState().getValue() == Session.State.ONLINE
            ).count().blockingGet()
        )
    );
    startForeground(R.id.notif_running, builder.build());
    startService(new Intent(this, this.getClass()));
  }

  /**
   * Constructs and logs in a {@link StandardSession}. If the specified {@code jid} already exists
   * in {@code this.sessions}, it simply returns the {@link StandardSession} corresponding to that
   * {@code jid}.
   * @throws UnsupportedOperationException If no {@link StandardSession} implementations supports
   * the {@link chat.viska.xmpp.Connection.Protocol} specified by {@code connection}.
   */
  @Nonnull
  private StandardSession constructSession(@Nonnull final Jid jid,
                                           @Nonnull final Connection connection) {
    final StandardSession session;
    synchronized (this.sessions) {
      try {
        session = StandardSession.getInstance(Collections.singleton(connection.getProtocol()));
      } catch (Exception ex) {
        throw new UnsupportedOperationException(
            getString(R.string.server_uses_unsupported_protocol)
        );
      }
      this.sessions.put(jid, session);
    }

    session.setConnection(connection);
    session.setLoginJid(jid);
    session.getPluginManager().apply(BasePlugin.class);

    session.getPluginManager().apply(WebRtcPlugin.class);
    final WebRtcPlugin webRtcPlugin = session.getPluginManager().getPlugin(WebRtcPlugin.class);
    webRtcPlugin.getEventStream().subscribe(it -> session.getLogger().info(it.toString()));
    webRtcPlugin.getEventStream().ofType(
        WebRtcPlugin.SdpReceivedEvent.class
    ).filter(
        WebRtcPlugin.SdpReceivedEvent::isCreating
    ).subscribe(it -> {
      final Intent intent = new Intent(this, CallingActivity.class);
      intent.setAction(CallingActivity.ACTION_CALL_INBOUND);
      intent.setData(Uri.fromParts("xmpp", it.getRemoteJid().toString(), null));
      intent.putExtra(CallingActivity.EXTRA_LOCAL_JID, session.getLoginJid().toString());
      intent.putExtra(CallingActivity.EXTRA_SESSION_ID, it.getId());
      intent.putExtra(CallingActivity.EXTRA_REMOTE_SDP, it.getSdp().description);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    });


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
          synchronized (this.sessions) {
            if (this.sessions.containsValue(session)) {
              this.sessions.remove(jid);
            }
            if (this.sessions.size() == 0) {
              stopForeground(true);
            }
          }
        });
    session
        .getState()
        .getStream()
        .filter(it -> it == Session.State.DISCONNECTED)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(it -> startForeground());
    return session;
  }

  @Nonnull
  private List<InetAddress> getDns() {
    final ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
        CONNECTIVITY_SERVICE
    );
    if (connectivityManager == null) {
      return Collections.emptyList();
    }
    return Observable.fromArray(
        connectivityManager.getAllNetworks()
    ).filter(it -> connectivityManager.getNetworkCapabilities(it).hasCapability(
        NetworkCapabilities.NET_CAPABILITY_INTERNET
    )).map(connectivityManager::getLinkProperties).flatMap(
        it -> Observable.fromIterable(it.getDnsServers())
    ).toList().blockingGet();
  }

  public void syncAllAccounts() {
    Completable.fromAction(() -> {
      if (this.isSyncingAccounts.getValue()) {
        return;
      }
      this.isSyncingAccounts.changeValue(true);
      final List<Jid> enabled = Observable.fromArray(
          this.accountManager.getAccountsByType(getString(R.string.api_account_type))
      ).map(it -> new Jid(it.name)).toList().blockingGet();

      final Set<Jid> toRemove;
      final Set<Jid> toLogin;
      final Set<Jid> toRemain;

      final Consumer<Throwable> errorConsumer = ex -> {
        Toast.makeText(this, ex.getLocalizedMessage(), Toast.LENGTH_LONG).show();
      };
      synchronized (this.sessions) {
        toRemove = new HashSet<>(this.sessions.keySet());
        toRemove.removeAll(enabled);
        toLogin = new HashSet<>(enabled);
        toLogin.removeAll(this.sessions.keySet());
        toRemain = new HashSet<>(this.sessions.keySet());
        toRemain.removeAll(toRemove);

        for (Jid it : toRemove) {
          this.sessions.get(it).dispose().subscribe();
        }
        for (Jid it : toLogin) {
          login(
              it,
              this.accountManager.getPassword(
                  new Account(it.toString(), getString(R.string.api_account_type))
              ),
              true
          ).observeOn(AndroidSchedulers.mainThread()).subscribe(() -> {}, errorConsumer);
        }
        for (Jid it : toRemain) {
          final StandardSession session = this.sessions.get(it);
          if (session.getState().getValue() == Session.State.DISCONNECTED) {
            login(
                it,
                this.accountManager.getPassword(
                    new Account(it.toString(), getString(R.string.api_account_type))
                ),
                false
            ).observeOn(AndroidSchedulers.mainThread()).subscribe(() -> {}, errorConsumer);
          }
        }
      }
      this.isSyncingAccounts.changeValue(false);
    }).subscribeOn(Schedulers.io()).subscribe();

  }

  @Nonnull
  public Map<Jid, StandardSession> getSessions() {
    return Collections.unmodifiableMap(sessions);
  }

  @Nonnull
  public ReactiveObject<Boolean> isSyncingAccounts() {
    return isSyncingAccounts;
  }

  /**
   * Submits an XMPP account and logs it in. May signal various {@link Exception}s.
   */
  @Nonnull
  public Completable login(@Nonnull final Jid jid,
                           @Nonnull final String password,
                           final boolean replaceExisting) {
    final Action cancellation = () -> {
      synchronized (sessions) {
        final StandardSession session = sessions.get(jid);
        if (session != null) {
          session.killConnection().subscribeOn(Schedulers.io()).subscribe();
        }
      }
    };
    startForeground();
    synchronized (sessions) {
      final StandardSession session = sessions.get(jid);
      if (session != null) {
        if (replaceExisting) {
          sessions.remove(jid);
          session.dispose().subscribeOn(Schedulers.io()).subscribe();
        } else {
          switch (session.getState().getValue()) {
            case ONLINE:
              return Completable.complete();
            case CONNECTING:
              return session.getState()
                  .getStream()
                  .filter(it -> it == Session.State.ONLINE)
                  .firstOrError()
                  .toCompletable();
            case HANDSHAKING:
              return session.getState()
                  .getStream()
                  .filter(it -> it == Session.State.ONLINE)
                  .firstOrError()
                  .toCompletable();
            default:
              return session.killConnection()
                  .andThen(session.login(password))
                  .doOnError(ex -> cancellation.run())
                  .doOnComplete(this::startForeground)
                  .doOnDispose(cancellation);
          }
        }
      }
    }
    return Connection
        .queryDns(jid.getDomainPart(), getDns())
        .flatMapMaybe(
            it -> Observable.fromIterable(it).filter(Connection::isTlsEnabled).firstElement()
        )
        .doOnComplete(() -> {
          throw new UnsupportedOperationException(
              getString(R.string.server_has_no_secure_connection)
          );
        })
        .map(connection -> constructSession(jid, connection))
        .flatMapCompletable(it -> it.login(password))
        .doOnError(ex -> cancellation.run())
        .doOnComplete(this::startForeground)
        .doOnDispose(cancellation);
  }

  @Override
  public Binder onBind(final Intent intent) {
    return binder;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    try {
      accountManager = AccountManager.get(this);
    } catch (SecurityException ex) {
      Toast.makeText(
          this,
          getString(R.string.permission_error_accounts, getString(R.string.title_app)),
          Toast.LENGTH_LONG
      ).show();
      stopSelf();
      return;
    }
    accountManager.addOnAccountsUpdatedListener(accountsListener, null, true);

    final ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
        Context.CONNECTIVITY_SERVICE
    );
    final NetworkRequest networkRequest = new NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build();
    try {
      connectivityManager.registerNetworkCallback(networkRequest, networkListener);
    } catch (Exception ex) {
      Toast.makeText(
          this,
          getString(R.string.permission_error_network, getString(R.string.title_app)),
          Toast.LENGTH_LONG
      ).show();
      stopSelf();
    }

    syncAllAccounts();
  }

  @Override
  public void onDestroy() {
    accountManager.removeOnAccountsUpdatedListener(accountsListener);
    ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(
        networkListener
    );
    isSyncingAccounts.complete();
    Observable.fromIterable(this.sessions.values()).observeOn(Schedulers.io()).subscribe(
        Session::close
    );
    super.onDestroy();
  }
}
