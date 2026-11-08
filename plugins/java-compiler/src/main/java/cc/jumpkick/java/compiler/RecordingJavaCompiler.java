// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import xsbti.FileConverter;
import xsbti.Logger;
import xsbti.Reporter;
import xsbti.VirtualFile;
import xsbti.compile.IncToolOptions;
import xsbti.compile.JavaCompiler;
import xsbti.compile.Output;

/**
 * Records which sources javac was actually handed. Zinc decides the set per cycle and does not
 * report it, so the only place to observe it is at the tool boundary — hence a decorator rather
 * than a query.
 */
final class RecordingJavaCompiler implements JavaCompiler {

    private final JavaCompiler delegate;
    private final FileConverter converter;
    private final List<Path> compiledSources = new ArrayList<>();
    private final HashSet<Path> seen = new HashSet<>(); // O(1) dedup instead of O(n) contains

    /** The module's {@code module-info.java}, handed to javac with every subset; null when it has none. */
    private @Nullable VirtualFile descriptor;

    RecordingJavaCompiler(JavaCompiler delegate, FileConverter converter) {
        this.delegate = delegate;
        this.converter = converter;
    }

    /**
     * Compile {@code descriptor} with every set of sources Zinc hands over. An incremental cycle
     * passes only the sources it invalidated, and Zinc deletes the classes it holds stale before
     * javac runs, {@code module-info.class} among them; without the descriptor in the set nothing
     * writes it back, and the jar, and every module that requires this one, lose the module. With
     * it, the subset also compiles as part of the named module, as a full compile does.
     */
    void alwaysCompiling(@Nullable VirtualFile descriptor) {
        this.descriptor = descriptor;
    }

    List<Path> compiledSources() {
        return compiledSources;
    }

    @Override
    public boolean run(
            VirtualFile[] sources,
            String[] options,
            Output output,
            IncToolOptions incToolOptions,
            Reporter reporter,
            Logger log) {
        VirtualFile[] handed = withDescriptor(sources);
        for (VirtualFile vf : handed) {
            Path path = converter.toPath(vf);
            if (seen.add(path)) compiledSources.add(path);
        }
        return delegate.run(handed, options, output, incToolOptions, reporter, log);
    }

    private VirtualFile[] withDescriptor(VirtualFile[] sources) {
        VirtualFile mine = descriptor;
        if (mine == null || sources.length == 0) return sources;
        Path path = converter.toPath(mine);
        for (VirtualFile vf : sources) {
            if (converter.toPath(vf).equals(path)) return sources;
        }
        VirtualFile[] out = Arrays.copyOf(sources, sources.length + 1);
        out[sources.length] = mine;
        return out;
    }
}
