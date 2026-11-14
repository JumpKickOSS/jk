// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.util.List;

/**
 * Which tests a run keeps by tag: the {@code --include-tags} / {@code --exclude-tags} JUnit tag
 * expressions, and the run's suites ({@link SuiteMembers}), which place a test by its class and
 * tags together.
 */
record TagFilters(List<String> include, List<String> exclude, List<SuiteMembers.Clause> members) {

    static final TagFilters NONE = new TagFilters(List.of(), List.of(), List.of());

    TagFilters {
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
        members = List.copyOf(members);
    }
}
