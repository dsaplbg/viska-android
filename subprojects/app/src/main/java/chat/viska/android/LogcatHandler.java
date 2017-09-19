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

import android.util.Log;
import chat.viska.xmpp.Session;
import chat.viska.xmpp.SessionAware;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.annotation.Nonnull;

public class LogcatHandler extends Handler implements SessionAware {

  private final Session session;
  private boolean closed = false;

  public static int toAndroidLevel(final Level level) {
    Objects.requireNonNull(level);
    if (level.intValue() >= Level.SEVERE.intValue()) {
      return Log.ERROR;
    } else if (level.intValue() >= Level.WARNING.intValue()) {
      return Log.WARN;
    } else if (level.intValue() >= Level.INFO.intValue()) {
      return Log.INFO;
    } else if (level.intValue() >= Level.FINE.intValue()) {
      return Log.DEBUG;
    } else {
      return Log.VERBOSE;
    }
  }

  public LogcatHandler(final Session session) {
    Objects.requireNonNull(session);
    this.session = session;
  }

  @Override
  public void close() throws SecurityException {
    closed = true;
  }

  @Override
  public void flush() {}

  @Override
  public void publish(LogRecord record) {
    if (closed || !isLoggable(record)) {
      return;
    }
    final int level = toAndroidLevel(record.getLevel());
    final String tag = session.getJid() == null ? "Anonymous JID" : session.getJid().toString();
    if (level == Log.ERROR) {
      Log.e(tag, record.getMessage(), record.getThrown());
    } else if (level == Log.WARN) {
      Log.w(tag,record.getMessage(), record.getThrown());
    } else if (level == Log.INFO) {
      Log.i(tag,record.getMessage(), record.getThrown());
    } else if (level == Log.DEBUG) {
      Log.d(tag,record.getMessage(), record.getThrown());
    } else if (level == Log.VERBOSE) {
      Log.v(tag,record.getMessage(), record.getThrown());
    }
  }

  @Nonnull
  @Override
  public Session getSession() {
    return session;
  }
}
