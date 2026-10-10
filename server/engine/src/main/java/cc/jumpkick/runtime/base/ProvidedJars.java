// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;

/**
 * The jars a module's batteries put on its classpaths beside the lock's rows ({@code
 * [[contribute.provided-classpath]]}: an Android platform jar, an IntelliJ distribution), for an
 * IDE model to list as libraries. The planner resolves them; this is how the model asks without
 * depending on it.
 */
@FunctionalInterface
public interface ProvidedJars {

    /** One jar: its library name, where it is, and whether only the test classpaths carry it. */
    record Jar(String name, Path path, boolean testOnly) {}

    /** No provided jars: a model that lists the lock's rows alone. */
    ProvidedJars NONE = (moduleDir, module) -> List.of();

    /** {@code module}'s provided jars, fetched as a build would; none when they do not resolve. */
    List<Jar> of(Path moduleDir, JkBuild module);
}
