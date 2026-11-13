// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.lock.Lockfile;
import java.nio.file.Path;
import java.util.List;

/**
 * What {@code parse-build} declared for the steps after it: the module's lockfile view, compiler
 * settings, output trees and source lists, before any step adds generated sources or replaces the
 * classes tree.
 *
 * @param lockfile the lockfile, as this module sees it
 * @param javacArgs the javac args every compile of the module runs with
 * @param release the {@code --release} the module targets
 * @param classesDir the compiled main classes tree
 * @param testClassesDir the compiled test classes tree
 * @param javaSources the declared Java and Scala sources
 * @param kotlinSources the declared Kotlin sources
 * @param groovySources the declared Groovy sources
 */
public record ParsedBuild(
        Lockfile lockfile,
        List<String> javacArgs,
        int release,
        Path classesDir,
        Path testClassesDir,
        List<Path> javaSources,
        List<Path> kotlinSources,
        List<Path> groovySources) {

    public ParsedBuild {
        javacArgs = List.copyOf(javacArgs);
        javaSources = List.copyOf(javaSources);
        kotlinSources = List.copyOf(kotlinSources);
        groovySources = List.copyOf(groovySources);
    }
}
