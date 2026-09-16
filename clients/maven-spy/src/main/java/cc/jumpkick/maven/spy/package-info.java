// SPDX-License-Identifier: Apache-2.0
/**
 * A Maven core extension ({@code -Dmaven.ext.class.path}) that appends one line per reactor event
 * to the file named by {@code -Djk.mvn.events}; {@code jk mvn} turns that file into
 * {@code jk-results.md}. Nothing of jk's is on Maven's classpath, so the module depends on Maven
 * alone and the line format is the JDK's percent-encoding over tabs.
 */
@NullMarked
package cc.jumpkick.maven.spy;

import org.jspecify.annotations.NullMarked;
