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
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;
import chat.viska.xmpp.Connection;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.Session;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;

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
  private final OnAccountsUpdateListener accountsListener = accounts -> syncAllAccounts();
  private AccountManager accountManager;

  private final ConnectivityManager.NetworkCallback networkListener = new ConnectivityManager.NetworkCallback() {

    @Override
    public void onLinkPropertiesChanged(final Network network, final LinkProperties linkProperties) {
      super.onLinkPropertiesChanged(network, linkProperties);
      initializeNetwork();
    }

    @Override
    public void onAvailable(Network network) {
      super.onAvailable(network);
      syncAllAccounts();
    }
  };

  private boolean isNeeded() {
    return Observable.fromArray(accountManager.getAccountsByType(getString(R.string.api_account_type))).any(
        account -> "true".equals(
            accountManager.getUserData(account, getString(R.string.api_account_enabled))
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
        .setSmallIcon(R.drawable.icon)
        .setOngoing(true);
    startForeground(R.id.notif_running, builder.build());
    startService(new Intent(this, this.getClass()));
  }

  /**
   * Constructs and logs in a {@link Session}.
   * @throws UnsupportedOperationException If no {@link Session} implementations supports the
   * {@link chat.viska.xmpp.Connection.Protocol} specified by {@code connection}.
   */
  @Nonnull
  private Session constructSession(final Jid jid, final Connection connection)
      throws UnsupportedOperationException {
    final Session session;
    try {
      session = Session.getInstance(Collections.singleton(connection.getProtocol()));
    } catch (Exception ex) {
      throw new UnsupportedOperationException(
          getString(R.string.desc_server_uses_unsupported_protocol)
      );
    }

    session.setConnection(connection);
    session.setLoginJid(jid);

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
            if (sessions.containsValue(session)) {
              sessions.remove(jid);
            }
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

  private void initializeNetwork() {
    final ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
        CONNECTIVITY_SERVICE
    );
    final List<InetAddress> dns = Observable.fromArray(
        connectivityManager.getAllNetworks()
    ).filter(it ->
        connectivityManager.getNetworkCapabilities(it).hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    ).map(connectivityManager::getLinkProperties).flatMap(
        it -> Observable.fromIterable(it.getDnsServers())
    ).toList().blockingGet();
    final List<String> dnsNames = Observable
        .fromIterable(dns)
        .map(InetAddress::toString)
        .toList()
        .blockingGet();
    final List<? extends Resolver> resolvers = Observable.fromIterable(dns).map(it -> {
      final SimpleResolver resolver = new SimpleResolver("localhost");
      resolver.setAddress(it);
      return resolver;
    }).toList().blockingGet();
    final ExtendedResolver resolver;
    try {
      Lookup.setDefaultResolver(new ExtendedResolver(resolvers.toArray(new Resolver[resolvers.size()])));
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void syncAllAccounts() {
  }

  @Nonnull
  public Map<Jid, Session> getSessions() {
    return Collections.unmodifiableMap(sessions);
  }

  /**
   * Submits an XMPP account and logs it in. If a {@link Session} with the same {@link Jid} already
   * exists, it is disposed of and removed first. Signals {@link UnsupportedOperationException} if
   * no secure XMPP connection methods are found. May signal other {@link Exception}s.
   * @throws IllegalArgumentException If {@code relaceExisting} is set to {@code false} and there is
   *         another {@link Session} with the same {@link Jid} currently logged in.
   */
  @Nonnull
  @CheckReturnValue
  public Completable login(final Jid jid, final String password, boolean replaceExisting) {
    synchronized (sessions) {
      final Session existingSession = sessions.get(jid);
      if (existingSession != null) {
        if (replaceExisting) {
          existingSession.dispose().subscribeOn(Schedulers.io()).subscribe();
          sessions.remove(jid);
        } else {
          throw new IllegalArgumentException(getString(R.string.desc_duplicated_account));
        }
      }
    }
    startForeground();
    return Connection
        .queryDns(jid.getDomainPart())
        .flatMapMaybe(it -> Observable.fromIterable(it).filter(Connection::isTlsEnabled).firstElement())
        .doOnComplete(() -> {
          throw new UnsupportedOperationException(
              getString(R.string.desc_server_has_no_secure_connection)
          );
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
      accountManager = AccountManager.get(this);
    } catch (SecurityException ex) {
      Toast.makeText(
          this,
          getString(R.string.desc_permission_error_accounts, getString(R.string.title_app_name)),
          Toast.LENGTH_LONG
      ).show();
      stopSelf();
      return;
    }
    if (isNeeded()) {
      startForeground();
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
    } catch (SecurityException ex) {
      Toast.makeText(
          this,
          getString(R.string.desc_permission_error_network, getString(R.string.title_app_name)),
          Toast.LENGTH_LONG
      ).show();
      stopSelf();
      return;
    }

  }

  @Override
  public void onDestroy() {
    accountManager.removeOnAccountsUpdatedListener(accountsListener);
    ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(
        networkListener
    );
    super.onDestroy();
  }
}
