// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This class writes the named process-globals on purpose; do not report it for them.
 *
 * <p>Per named global, never a wildcard. A class that needs to leak more than one is worth a review
 * comment, and a blanket opt-out would turn the guard off exactly where it is most needed — the
 * classes that drive the production install paths are the ones {@code} was filed about.
 *
 * <p><b>No current users, and that is the expected state.</b> The two attributed globals —
 * {@code theme} and {@code terminal-size} — have no deliberate writers left in the tree: the tests
 * that touch them already restore what they changed. {@code session} is written by every CLI
 * command test by design and is therefore bounded without being attributed, so annotating a handful
 * of its 48 writers would imply the other 44 do not. Reach for this when a genuinely deliberate
 * writer of an attributed global appears.
 *
 * <p>The boundary still <em>restores</em> an opted-out global. Declaring it silences the
 * attribution report, not the cleanup: the point of the annotation is "this write is deliberate",
 * not "let it escape into the next class".
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InstallsGlobal {

    /** Names from {@link BoundedGlobal#name()}. */
    String[] value();
}
