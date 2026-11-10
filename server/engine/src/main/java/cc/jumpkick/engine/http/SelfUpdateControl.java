// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.Optional;

/** The engine's update, as the dashboard's Update pill reads and drives it. */
public interface SelfUpdateControl {

    /** The release to offer a person now: downloaded, newer than this engine, not snoozed. */
    Optional<String> offer();

    /** Whether an install a person asked for is under way. */
    boolean installing();

    /** Install the downloaded release now; false when there is none to install. */
    boolean installNow();

    /** "Not now" to the offered release, on every surface until the next check; false when none is offered. */
    boolean snooze();

    /** An engine that never updates itself (a checkout, a snapshot, a test). */
    SelfUpdateControl NONE = new SelfUpdateControl() {
        @Override
        public Optional<String> offer() {
            return Optional.empty();
        }

        @Override
        public boolean installing() {
            return false;
        }

        @Override
        public boolean installNow() {
            return false;
        }

        @Override
        public boolean snooze() {
            return false;
        }
    };
}
