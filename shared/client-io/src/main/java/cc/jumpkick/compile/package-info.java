// SPDX-License-Identifier: Apache-2.0
/**
 * Compilation and packaging. Classpath composition is client-safe and lives here; the compilers,
 * packagers and SBOM writer are the engine's half of the same package (see package-owners.txt).
 *
 * <p>The marking sits in the lower tier so both halves inherit it, the way {@code cc.jumpkick.task}
 * is marked from {@code shared/core}.
 */
@NullMarked
package cc.jumpkick.compile;

import org.jspecify.annotations.NullMarked;
