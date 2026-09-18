// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.platform.launcher.LauncherDiscoveryListener;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * A launcher lifecycle listener the test classpath registers through {@code META-INF/services},
 * the way a framework readies a JVM for the tests it will run. It only counts: which launchers
 * fire the auto-registered session and discovery SPIs is what the counts prove.
 */
public final class LifecycleListenerFixture implements LauncherSessionListener, LauncherDiscoveryListener {

    private static final AtomicInteger SESSIONS_OPENED = new AtomicInteger();
    private static final AtomicInteger DISCOVERIES_STARTED = new AtomicInteger();

    public static void reset() {
        SESSIONS_OPENED.set(0);
        DISCOVERIES_STARTED.set(0);
    }

    public static int sessionsOpened() {
        return SESSIONS_OPENED.get();
    }

    public static int discoveriesStarted() {
        return DISCOVERIES_STARTED.get();
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        SESSIONS_OPENED.incrementAndGet();
    }

    @Override
    public void launcherDiscoveryStarted(LauncherDiscoveryRequest request) {
        DISCOVERIES_STARTED.incrementAndGet();
    }
}
