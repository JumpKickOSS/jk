// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.nio.file.Path;

/** JDK install from a probe: home, version, vendor, and source probe name. */
public record JdkHit(Path home, String version, JdkVendor vendor, String source) {}
