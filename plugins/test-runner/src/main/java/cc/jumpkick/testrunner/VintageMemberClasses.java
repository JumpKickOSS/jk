// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Drops a nested JUnit 3/4 class that Vintage found as a class of its own, as Surefire's default
 * {@code **}{@code /*$*} exclude does. Such a class is a helper its outer class's {@code suite()}
 * builds (a {@code TestCase} with no {@code TestCase(String)} constructor fails on its own), or a
 * member an enclosing runner already runs. Jupiter reports {@code @Nested} classes under their
 * outer class and is not touched.
 */
final class VintageMemberClasses {

    private VintageMemberClasses() {}

    /** The Vintage engine's id, the first segment of every unique id it reports. */
    static final String ENGINE_ID = "junit-vintage";

    /**
     * Excludes every test under a Vintage class the engine reports directly whose class is nested.
     * Judged at the test, not the class: the Platform applies a post-discovery filter to leaves
     * and prunes the containers they empty.
     */
    static final PostDiscoveryFilter FILTER = VintageMemberClasses::apply;

    static FilterResult apply(TestDescriptor descriptor) {
        if (!descriptor.getUniqueId().getEngineId().map(ENGINE_ID::equals).orElse(false)) {
            return FilterResult.included("not a Vintage test");
        }
        TestDescriptor engineClass = descriptor;
        for (TestDescriptor parent = descriptor.getParent().orElse(null);
                parent != null && !parent.isRoot();
                parent = parent.getParent().orElse(null)) {
            engineClass = parent;
        }
        if (engineClass.getSource().orElse(null) instanceof ClassSource source
                && source.getClassName().indexOf('$') >= 0) {
            return FilterResult.excluded(source.getClassName() + " is nested; its outer class runs it");
        }
        return FilterResult.included("under a top-level class");
    }
}
