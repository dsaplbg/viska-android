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

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public class AuthenticatorService extends Service {

  public class Authenticator extends AbstractAccountAuthenticator {

    public Authenticator() {
      super(AuthenticatorService.this);
    }

    @Override
    public Bundle editProperties(final AccountAuthenticatorResponse response, final String s) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Bundle addAccount(final AccountAuthenticatorResponse response,
                             final String accountType,
                             final String authTokenType,
                             final String[] requiredFeatures,
                             final Bundle bundle) throws NetworkErrorException {
      if (!getString(R.string.api_account_type).equals(accountType)) {
        throw new IllegalArgumentException(getString(R.string.desc_unsupported_account_type));
      }
      final Bundle result = new Bundle(1);
      final Intent intent = new Intent(AuthenticatorService.this, LoginActivity.class);
      intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
      intent.putExtra(LoginActivity.KEY_IS_ADDING, true);
      result.putParcelable(AccountManager.KEY_INTENT, intent);
      return result;
    }

    @Override
    public Bundle confirmCredentials(final AccountAuthenticatorResponse response,
                                     final Account account,
                                     final Bundle bundle) throws NetworkErrorException {
      throw new UnsupportedOperationException();
      //DOTO: Implement this.
    }

    @Override
    public Bundle getAuthToken(final AccountAuthenticatorResponse response,
                               final Account account,
                               final String authTokenType,
                               final Bundle bundle) throws NetworkErrorException {
      throw new UnsupportedOperationException(getString(R.string.desc_no_auth_token));
    }

    @Override
    public String getAuthTokenLabel(final String authTokenType) {
      throw new UnsupportedOperationException(getString(R.string.desc_no_auth_token));
    }

    @Override
    public Bundle updateCredentials(final AccountAuthenticatorResponse response,
                                    final Account account,
                                    final String accountType,
                                    final Bundle bundle) throws NetworkErrorException {
      if (!getString(R.string.api_account_type).equals(accountType)) {
        throw new IllegalArgumentException(getString(R.string.desc_unsupported_account_type));
      }
      final Bundle result = new Bundle(1);
      final Intent intent = new Intent(AuthenticatorService.this, LoginActivity.class);
      intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
      intent.putExtra(LoginActivity.KEY_IS_UPDATING, true);
      intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, account);
      result.putParcelable(AccountManager.KEY_INTENT, intent);
      return result;
    }

    @Override
    public Bundle hasFeatures(final AccountAuthenticatorResponse response,
                              final Account account,
                              final String[] features) throws NetworkErrorException {
      if (!getString(R.string.api_account_type).equals(account.type)) {
        throw new IllegalArgumentException(getString(R.string.desc_unsupported_account_type));
      }
      final Bundle bundle = new Bundle(1);
      bundle.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, features.length == 0);
      return bundle;
    }
  }

  private final Authenticator authenticator = new Authenticator();

  @Override
  public IBinder onBind(final Intent intent) {
    return authenticator.getIBinder();
  }
}
