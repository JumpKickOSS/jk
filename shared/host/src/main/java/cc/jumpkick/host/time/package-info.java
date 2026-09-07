// SPDX-License-Identifier: Apache-2.0
/**
 * The one place jk reads the time. Everything that stamps, ages or measures takes a {@link
 * cc.jumpkick.host.time.Clock}; only {@code SystemClock} asks the JVM.
 */
@NullMarked
package cc.jumpkick.host.time;

import org.jspecify.annotations.NullMarked;
