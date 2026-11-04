// SPDX-License-Identifier: Apache-2.0

/**
 * Where jk keeps a login's secret: the OS keychain behind {@link cc.jumpkick.secret.SecretBackend},
 * or the owner-only file the login's record lives in. {@link cc.jumpkick.secret.SecretVault} picks
 * the place and resolves a record's pointer back to the secret.
 */
@NullMarked
package cc.jumpkick.secret;

import org.jspecify.annotations.NullMarked;
