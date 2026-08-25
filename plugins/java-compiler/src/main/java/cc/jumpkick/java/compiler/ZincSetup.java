// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import sbt.internal.inc.Locate;
import xsbti.FileConverter;
import xsbti.Logger;
import xsbti.T2;
import xsbti.VirtualFile;
import xsbti.compile.CompileAnalysis;
import xsbti.compile.DefinesClass;
import xsbti.compile.PerClasspathEntryLookup;

/**
 * The parts of Zinc's {@code Setup}/{@code Inputs} jk supplies as constants: no per-entry analysis,
 * a silent logger, no extra key/value pairs, and the path-to-{@link VirtualFile} lift. Each is a
 * Zinc SPI obligation with no jk decision in it — grouped because the alternative is four files
 * whose whole content is "we have nothing to say here".
 */
final class ZincSetup {

    private ZincSetup() {}

    /** No cross-entry analysis: jk compiles one module per Zinc session. */
    static final class ClasspathLookup implements PerClasspathEntryLookup {
        @Override
        public Optional<CompileAnalysis> analysis(VirtualFile classpathEntry) {
            return Optional.empty();
        }

        @Override
        public DefinesClass definesClass(VirtualFile classpathEntry) {
            if ("rt.jar".equals(classpathEntry.name())) return name -> false;
            return Locate.definesClass(classpathEntry);
        }
    }

    /** Zinc's own chatter is not jk's output; diagnostics come through the reporter. */
    enum QuietLogger implements Logger {
        INSTANCE;

        @Override
        public void error(Supplier<String> msg) {}

        @Override
        public void warn(Supplier<String> msg) {}

        @Override
        public void info(Supplier<String> msg) {}

        @Override
        public void debug(Supplier<String> msg) {}

        @Override
        public void trace(Supplier<Throwable> exception) {}
    }

    @SuppressWarnings("unchecked")
    static T2<String, String>[] noExtra() {
        return new T2[0];
    }

    static VirtualFile[] virtual(List<Path> paths, FileConverter converter) {
        VirtualFile[] out = new VirtualFile[paths.size()];
        for (int i = 0; i < paths.size(); i++) out[i] = converter.toVirtualFile(paths.get(i));
        return out;
    }
}
