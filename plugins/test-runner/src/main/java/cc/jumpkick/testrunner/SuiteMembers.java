// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * The run's suites as the engine sends them, one {@code --member=<any tags>|<no tags>|<class
 * regex>} each: a test runs when one clause holds for it, that is its class matches the regex, it
 * carries one of the first tags (when there are any) and none of the second. A test's tags are
 * its own and every enclosing container's, as JUnit's own tag filter reads them.
 */
final class SuiteMembers {

    private SuiteMembers() {}

    record Clause(Set<String> any, Set<String> none, Pattern classes) {

        boolean holds(String className, Set<String> tags) {
            if (!classes.matcher(className).matches()) return false;
            for (String tag : none) {
                if (tags.contains(tag)) return false;
            }
            if (any.isEmpty()) return true;
            for (String tag : any) {
                if (tags.contains(tag)) return true;
            }
            return false;
        }
    }

    /** One {@code --member} value. */
    static Clause parse(String raw) {
        String[] parts = raw.split("\\|", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("--member must be <any tags>|<no tags>|<class regex>: " + raw);
        }
        return new Clause(tags(parts[0]), tags(parts[1]), Pattern.compile("^(?:" + parts[2] + ")$"));
    }

    /** The filter keeping the tests some clause holds for; null when the run sent none. */
    static @Nullable PostDiscoveryFilter filter(List<Clause> clauses) {
        if (clauses.isEmpty()) return null;
        return descriptor -> {
            String className = className(descriptor);
            if (className == null) return FilterResult.included("no class to place in a suite");
            Set<String> tags = new LinkedHashSet<>();
            for (TestTag tag : descriptor.getTags()) tags.add(tag.getName());
            for (Clause clause : clauses) {
                if (clause.holds(className, tags)) return FilterResult.included("in a suite of the run");
            }
            return FilterResult.excluded("in no suite of the run");
        };
    }

    /** The binary name of the class holding {@code descriptor}: its own source's, else the nearest container's. */
    private static @Nullable String className(TestDescriptor descriptor) {
        for (Optional<TestDescriptor> at = Optional.of(descriptor);
                at.isPresent();
                at = at.get().getParent()) {
            TestSource source = at.get().getSource().orElse(null);
            if (source instanceof MethodSource method) return method.getClassName();
            if (source instanceof ClassSource type) return type.getClassName();
        }
        return null;
    }

    private static Set<String> tags(String csv) {
        Set<String> out = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>(List.of(csv.split(",")));
        for (String part : parts) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return Set.copyOf(out);
    }
}
