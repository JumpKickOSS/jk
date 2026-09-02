// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.runtime.CompileSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The one compile set for a groovyc invocation: the explicit sources plus every {@code .java}
 * under the request's Java source roots (joint resolution needs the whole Java neighborhood on the
 * compile set) — deduped by absolute path, spec order first, root files sorted.
 *
 * <p>This set is what {@code ActionKey.forGroovyc} hashes <em>and</em> what rides the spec's
 * {@code SOURCE} lines, so the worker never walks a tree: its jar depends on {@code :host} +
 * {@code :plugin-sdk} only and must not grow a tree cache, and a set hashed here but enumerated
 * again over there is two answers to one question.
 */
public final class GroovycInputs {

    private GroovycInputs() {}

    /** See the class note. Engine-side walks go through the request-scoped collectors (VFS). */
    public static List<Path> compileSet(GroovycRequest request) throws IOException {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (Path src : request.sources()) {
            out.add(src.toAbsolutePath().normalize());
        }
        for (Path root : request.javaSourceRoots()) {
            List<Path> java = new ArrayList<>(CompileSupport.collectJavaSources(root));
            java.sort(Comparator.comparing(Path::toString));
            for (Path p : java) out.add(p.toAbsolutePath().normalize());
        }
        return List.copyOf(out);
    }
}
