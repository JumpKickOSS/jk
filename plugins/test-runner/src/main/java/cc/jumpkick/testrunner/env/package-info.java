// SPDX-License-Identifier: Apache-2.0

/**
 * The environment-read probe a test JVM starts with: an agent that sends every {@code
 * System.getenv} call a loaded class makes through a recorder, which writes what was looked up
 * when the JVM exits. JDK classes only, so it runs on the bootstrap class path of any suite.
 */
@NullMarked
package cc.jumpkick.testrunner.env;

import org.jspecify.annotations.NullMarked;
