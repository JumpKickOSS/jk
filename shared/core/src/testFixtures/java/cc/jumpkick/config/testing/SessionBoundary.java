// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Puts a boundary around {@link SessionContext}'s process-wide static so one test cannot hand its
 * session to the next.
 *
 * <p>Installing the resolved session on a static is the right design for a real {@code jk}
 * invocation — one process, one command, one session. Under a shared test JVM the same write has no
 * owner and outlives the class that made it: worker JVMs pull test classes off a shared queue, so
 * which class inherits a leak, and therefore whether anything fails, changes run to run. The
 * failure then surfaces in the victim carrying no trace of the culprit.
 *
 * <p>This restores the static's <em>prior value</em> rather than resetting to
 * {@link Session#defaults()}: a reset is not a restore, and would clobber a session an ambient
 * setup had legitimately installed. The snapshot comes from {@link SessionContext#installed()},
 * which reads the static alone — {@link SessionContext#current()} prefers the calling thread's
 * {@code ScopedValue} binding, and writing that back onto the shared field is how one thread's
 * session used to reach every other.
 *
 * <p>Containers are bounded as well as tests. An install from a {@code @BeforeAll} would
 * otherwise outlive its class and reach every class scheduled after it in the same worker — which
 * is precisely what the per-class {@code @AfterEach reset()} hooks were papering over.
 *
 * <p>Deliberately a platform {@link TestExecutionListener} rather than a Jupiter {@code Extension}:
 * extension autodetection is a single per-task switch, and the unit tier keeps it off on purpose so
 * {@code EngineTestExtension} stays unloaded (JK-2447). A listener is discovered from
 * {@code META-INF/services} unconditionally, so every tier gets the boundary and no tier gains an
 * engine. Cleanup is the default rather than something each new test class has to remember.
 */
public final class SessionBoundary implements TestExecutionListener {

    /** Keyed by unique id so nesting works: a container's snapshot outlives the tests inside it. */
    private final Map<String, Session> installed = new ConcurrentHashMap<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        installed.clear();
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        installed.put(testIdentifier.getUniqueId(), SessionContext.installed());
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        Session prior = installed.remove(testIdentifier.getUniqueId());
        if (prior != null) SessionContext.install(prior);
    }
}
