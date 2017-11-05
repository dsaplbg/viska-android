package chat.viska.android;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.TextInputLayout;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import chat.viska.R;
import chat.viska.commons.reactive.MutableReactiveObject;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.Session;
import chat.viska.xmpp.StandardSession;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.MaybeSubject;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/**
 * Login with a new or an existing account.
 *
 * <h2>Accepted {@link android.content.Intent} Extra Data</h2>
 *
 * <ul>
 *   <li>{@link #KEY_IS_UPDATING}</li>
 * </ul>
 */
public class LoginActivity extends AccountAuthenticatorActivity {

  private class EmptyTextWatcher implements TextWatcher {

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

    @Override
    public void afterTextChanged(Editable editable) {
      button.setEnabled(
          jidEditText.getText().length() != 0 && passwordEditText.getText().length() != 0
      );
    }
  }

  /**
   * Key to a {@link Boolean} indicating the {@link android.content.Intent} is to log in using an
   * existing account.
   */
  public final static String KEY_IS_UPDATING = "is-updating";

  private final MutableReactiveObject<Boolean> isLoggingIn = new MutableReactiveObject<>(false);
  private final MaybeSubject<XmppService> xmpp = MaybeSubject.create();
  private final AtomicReference<Disposable> loginSubscription = new AtomicReference<>();
  private ProgressBar progressBar;
  private EditText passwordEditText;
  private EditText jidEditText;
  private TextInputLayout jidTextLayout;
  private TextInputLayout passwordTextLayout;
  private Button button;
  private Jid jid;

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

  private void login() {
    try {
      jid = new Jid(jidEditText.getText().toString());
    } catch (Exception ex) {
      this.jidTextLayout.setError(getString(R.string.invalid_jid));
      isLoggingIn.changeValue(false);
      return;
    }
    final boolean duplicated = Observable.fromArray(
        AccountManager.get(this).getAccountsByType(getString(R.string.api_account_type))
    ).any(it -> jid.toString().equals(it.name)).blockingGet();
    if (duplicated) {
      onLoginFailed(new Exception(getString(R.string.duplicated_accounts)));
    }
    isLoggingIn.changeValue(true);
    bindService(new Intent(this, XmppService.class), binding, BIND_AUTO_CREATE);
    xmpp.subscribe(xmpp -> {
      final Disposable subscription = xmpp
          .login(jid, passwordEditText.getText().toString())
          .subscribeOn(Schedulers.io())
          .observeOn(AndroidSchedulers.mainThread())
          .subscribe(this::onLoginSucceeded, this::onLoginFailed);
      loginSubscription.set(subscription);
    });
  }

  private void onLoginSucceeded() {
    isLoggingIn.changeValue(false);
    final Account account = new Account(
        jidEditText.getText().toString(),
        getString(R.string.api_account_type)
    );
    final AccountManager manager = AccountManager.get(LoginActivity.this);
    final Bundle bundle = new Bundle();
    bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, getString(R.string.api_account_type));
    bundle.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
    if (getIntent().getBooleanExtra(KEY_IS_UPDATING, false)) {
      manager.setPassword(account, passwordEditText.getText().toString());
      setAccountAuthenticatorResult(bundle);
    } else {
      manager.addAccountExplicitly(account, passwordEditText.getText().toString(), null);
      setAccountAuthenticatorResult(bundle);
    }
    finish();
  }

  private void onLoginFailed(final Throwable cause) {
    isLoggingIn.changeValue(false);
    xmpp.subscribe(xmpp -> passwordTextLayout.setError(xmpp.convertToErrorMessage(cause)));
  }

  private void cancel() {
    this.button.setEnabled(false);
    loginSubscription.get().dispose();
    final StandardSession session = xmpp.getValue().getSessions().get(this.jid);
    if (session == null) {
      isLoggingIn.changeValue(false);
      button.setEnabled(true);
    } else {
      session.getState().getStream().filter(
          it -> it == Session.State.DISCONNECTED || it == Session.State.DISPOSED
      ).observeOn(AndroidSchedulers.mainThread()).subscribe(it -> {
        isLoggingIn.changeValue(false);
        button.setEnabled(true);
      });
    }
  }

  public void onButtonClicked(final View view) {
    if (isLoggingIn.getValue()) {
      cancel();
    } else {
      login();
    }
  }

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_login);

    if (getIntent().getBooleanExtra(KEY_IS_UPDATING, false)) {
      jidEditText.setText(getIntent().getStringExtra(AccountManager.KEY_ACCOUNT_NAME));
      jidEditText.setEnabled(false);
    }

    this.jidEditText = findViewById(R.id.login_EditText_jid);
    this.jidTextLayout = findViewById(R.id.login_TextInputLayout_jid);
    this.passwordEditText = findViewById(R.id.login_EditText_password);
    this.passwordTextLayout = findViewById(R.id.login_TextInputLayout_password);
    this.progressBar = findViewById(R.id.login_progress);
    this.button = findViewById(R.id.login_button);

    this.isLoggingIn.getStream().filter(Boolean::booleanValue).subscribe(it -> {
      progressBar.setVisibility(View.VISIBLE);
      button.setText(R.string.title_cancel);
      jidTextLayout.setError(null);
      passwordTextLayout.setError(null);
      jidEditText.setEnabled(false);
      passwordEditText.setEnabled(false);
    });
    this.isLoggingIn.getStream().filter(it -> !it).subscribe(it -> {
      progressBar.setVisibility(View.GONE);
      button.setText(R.string.title_login);
      jidEditText.setEnabled(true);
      passwordEditText.setEnabled(true);
    });

    final EmptyTextWatcher watcher = new EmptyTextWatcher();
    this.jidEditText.addTextChangedListener(watcher);
    this.passwordEditText.addTextChangedListener(watcher);

    this.passwordEditText.setOnEditorActionListener((textView, action, event) -> {
      if (!button.isEnabled()) {
        return false;
      } else if (action == EditorInfo.IME_ACTION_GO || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
        login();
        return true;
      } else {
        return false;
      }
    });
  }

  @Override
  protected void onDestroy() {
    this.isLoggingIn.complete();
    if (xmpp.hasValue()) {
      unbindService(binding);
    }
    super.onDestroy();
  }
}

