// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    RecordingJavaCompiler(JavaCompiler delegate, FileConverter converter) {
        this.delegate = delegate;
        this.converter = converter;
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
        for (VirtualFile vf : sources) {
            Path path = converter.toPath(vf);
            if (seen.add(path)) compiledSources.add(path);
        }
        return delegate.run(sources, options, output, incToolOptions, reporter, log);
    }
}
