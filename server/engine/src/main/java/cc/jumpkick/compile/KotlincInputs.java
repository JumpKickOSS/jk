// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.runtime.base.CompileSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The Java sources a kotlinc invocation reads: every {@code .java} under the request's Java source
 * roots ({@code -Xjava-source-roots}), which kotlinc parses for declarations and links against
 * without emitting bytecode for them — deduped by absolute path, sorted. The counterpart of
 * {@link GroovycInputs#compileSet} for the Kotlin lane: {@code ActionKey.forKotlinc} keys these
 * files by their declaration digest, so the set hashed is the set the worker sweeps.
 */
public final class KotlincInputs {

    private KotlincInputs() {}

    /** See the class note. Empty for a Kotlin-only module. */
    public static List<Path> javaSources(KotlincRequest request) throws IOException {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (Path root : request.javaSourceRoots()) {
            List<Path> java = new ArrayList<>(CompileSupport.collectJavaSources(root));
            java.sort(Comparator.comparing(Path::toString));
            for (Path p : java) out.add(p.toAbsolutePath().normalize());
        }
        return List.copyOf(out);
    }
}
