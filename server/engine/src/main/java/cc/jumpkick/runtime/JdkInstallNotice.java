// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.task.RunNotices;

/**
 * The fact a JDK install leaves behind, once per JDK per run: {@code installed JDK
 * 25.0.4.1-tem-x64-linux to ~/.jdks/…}. The download itself shows on the run's board like any other;
 * this is the line that says where the JDK went once the rows are gone, the way {@code jk jdk
 * install} ends.
 */
public final class JdkInstallNotice {

    private JdkInstallNotice() {}

    /** Post the notice when {@code outcome} installed a JDK this run; otherwise do nothing. */
    public static void post(JdkEnsure.Outcome outcome) {
        if (outcome.source() != JdkEnsure.Source.INSTALLED) return;
        InstalledJdk jdk = outcome.jdk();
        if (jdk == null) return;
        RunNotices.warnOnce(
                "jdk-installed:" + jdk.home(), () -> "installed JDK " + jdk.identifier() + " to " + jdk.home());
    }
}
