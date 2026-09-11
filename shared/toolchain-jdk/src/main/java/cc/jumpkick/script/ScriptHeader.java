// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.script;

import cc.jumpkick.model.Dependency;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parsed single-file script header from jk ({@code //jk …}), JBang, or Kotlin
 * {@code @file:DependsOn}/{@code @file:Repository} directives.
 */
public record ScriptHeader(
        List<Dependency> deps,
        @Nullable Integer release,
        List<URI> repos,
        List<String> features,
        List<String> javacOptions,
        List<String> javaOptions,
        List<String> sources,
        List<String> files,
        @Nullable String main,
        @Nullable String gav,
        @Nullable String description,
        @Nullable String kotlinVersion) {

    public ScriptHeader {
        deps = List.copyOf(Objects.requireNonNull(deps, "deps"));
        repos = List.copyOf(Objects.requireNonNull(repos, "repos"));
        features = List.copyOf(Objects.requireNonNull(features, "features"));
        javacOptions = List.copyOf(Objects.requireNonNull(javacOptions, "javacOptions"));
        javaOptions = List.copyOf(Objects.requireNonNull(javaOptions, "javaOptions"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    public static ScriptHeader empty() {
        return new ScriptHeader(
                List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null,
                null);
    }
}
