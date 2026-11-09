// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A preflight memo read that set aside the modules whose fingerprint moved ({@link
 * PreflightMemo#tryLoadPartial}). {@code fingerprints} holds every module's current fingerprint, the
 * changed ones included; {@code dirty} and {@code restoreNeeded} are the stored verdicts of the
 * others, which hold only outside the changed modules' downstream cone.
 */
public record PreflightPartial(
        Set<Path> changed, Set<Path> dirty, Map<Path, String> fingerprints, Set<Path> restoreNeeded) {}
