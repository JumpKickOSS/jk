// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/**
 * A JUnit 3 suite's nested runner — a {@code TestCase} its outer {@code suite()} builds, with no
 * {@code TestCase(String)} constructor — is not run as a class of its own, as under Surefire.
 */
class VintageMemberClassesTest {

    @Test
    void a_test_under_a_nested_class_vintage_reports_directly_is_excluded() {
        TestDescriptor test = testUnder("junit-vintage", "com.example.RegularFileTest$RegularFileTestRunner");
        assertThat(VintageMemberClasses.apply(test).excluded()).isTrue();
    }

    @Test
    void a_test_under_a_top_level_class_runs() {
        TestDescriptor test = testUnder("junit-vintage", "com.example.RegularFileTest");
        assertThat(VintageMemberClasses.apply(test).included()).isTrue();
    }

    @Test
    void a_nested_class_its_outer_runner_reports_runs() {
        // @RunWith(Enclosed.class): the members are children of the outer class's runner.
        TestDescriptor outer = testUnder("junit-vintage", "com.example.OuterTest");
        TestDescriptor inner = child(outer, "inner", ClassSource.from("com.example.OuterTest$Inner"), true);
        TestDescriptor test = child(inner, "m", null, false);
        assertThat(VintageMemberClasses.apply(test).included()).isTrue();
    }

    @Test
    void other_engines_are_untouched() {
        TestDescriptor test = testUnder("junit-jupiter", "com.example.Outer$StaticNested");
        assertThat(VintageMemberClasses.apply(test).included()).isTrue();
    }

    /** A leaf test under a class the engine {@code engineId} reports directly. */
    private static TestDescriptor testUnder(String engineId, String className) {
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine(engineId), engineId);
        TestDescriptor cls = child(engine, "runner", ClassSource.from(className), true);
        return child(cls, "test", null, false);
    }

    private static TestDescriptor child(
            TestDescriptor parent, String segment, @Nullable TestSource source, boolean container) {
        TestDescriptor child = new Node(parent.getUniqueId().append(segment, segment), source, container);
        parent.addChild(child);
        return child;
    }

    private static final class Node extends AbstractTestDescriptor {
        private final boolean container;

        Node(UniqueId id, @Nullable TestSource source, boolean container) {
            super(id, id.getLastSegment().getValue(), source);
            this.container = container;
        }

        @Override
        public Type getType() {
            return container ? Type.CONTAINER : Type.TEST;
        }
    }
}
