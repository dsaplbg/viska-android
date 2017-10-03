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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import chat.viska.android.R;
import chat.viska.android.XmppService;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.Session;
import chat.viska.xmpp.plugins.BasePlugin;
import chat.viska.xmpp.plugins.RosterItem;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends ListActivity {

  private final ServiceConnection binding = new ServiceConnection() {

    @Override
    public void onServiceConnected(final ComponentName componentName, final IBinder binder) {
      MainActivity.this.service = ((XmppService.Binder) binder).getService();
      MainActivity.this.service
          .isSyncingAccounts()
          .getStream()
          .filter(it -> !it)
          .firstElement()
          .observeOn(AndroidSchedulers.mainThread())
          .subscribe(it -> refresh());
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
    }
  };

  private XmppService service;

  private void refresh() {
    final Account[] accounts = AccountManager.get(this).getAccountsByType(
        getString(R.string.api_account_type)
    );
    if (accounts.length == 0) {
      setListAdapter(null);
      return;
    }
    this.service.isSyncingAccounts().getStream().filter(it -> !it).firstElement().observeOn(
        AndroidSchedulers.mainThread()
    ).subscribe(it -> {
      final Jid jid = new Jid(accounts[0].name);
      final Session session = this.service.getSessions().get(jid);
      if (session == null) {
        setListAdapter(null);
        return;
      }
      session
          .getPluginManager()
          .getPlugin(BasePlugin.class)
          .queryRoster()
          .flattenAsObservable(list -> list)
          .map(RosterItem::getJid)
          .toList()
          .subscribeOn(Schedulers.io())
          .observeOn(AndroidSchedulers.mainThread())
          .subscribe(
              list -> setListAdapter(
                  new ArrayAdapter<>(this, R.layout.demo_roster_item, list)
              ),
              cause -> Toast.makeText(
                  this, "Failed to retrieve roster.", Toast.LENGTH_LONG
              ).show()
          );
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (this.service == null) {
      bindService(new Intent(this, XmppService.class), binding, BIND_AUTO_CREATE);
    } else {
      refresh();
    }
  }

  @Override
  protected void onDestroy() {
    unbindService(binding);
    super.onDestroy();
  }
}
