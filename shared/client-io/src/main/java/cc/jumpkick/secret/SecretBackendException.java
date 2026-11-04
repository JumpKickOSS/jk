// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

/** A keychain that is there but refused: locked, denied, or answering in a way jk cannot read. */
public final class SecretBackendException extends RuntimeException {

    public SecretBackendException(String message) {
        super(message);
    }

    public SecretBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
