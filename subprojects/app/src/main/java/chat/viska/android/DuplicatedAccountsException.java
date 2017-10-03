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

/**
 * Indicates trying to login an account that is already logged in.
 */
public class DuplicatedAccountsException extends IllegalArgumentException {

  public DuplicatedAccountsException() {
  }

  public DuplicatedAccountsException(String message) {
    super(message);
  }

  public DuplicatedAccountsException(String message, Throwable cause) {
    super(message, cause);
  }

  public DuplicatedAccountsException(Throwable cause) {
    super(cause);
  }
}