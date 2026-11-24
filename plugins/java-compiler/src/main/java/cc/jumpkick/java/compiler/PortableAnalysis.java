// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Classpaths;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import xsbti.VirtualFileRef;
import xsbti.compile.MiniSetup;
import xsbti.compile.analysis.ReadMapper;
import xsbti.compile.analysis.ReadWriteMappers;
import xsbti.compile.analysis.Stamp;
import xsbti.compile.analysis.WriteMapper;

/**
 * The spelling of a Zinc analysis that any checkout of the project can read: every path under the
 * project's root is written as {@link #MARK} and the rest of the path, and read back under the root
 * of the checkout reading it. Zinc stamps sources, products and libraries by content, so with its
 * paths rebased an analysis another checkout wrote describes this checkout's classes exactly when
 * their bytes agree, and the engine can lay it back beside the classes of a cache hit.
 *
 * <p>On Windows Zinc names a file both ways: a product as {@code C:/w/target/classes/a/B.class},
 * a classpath entry or javac option as {@code C:\w\lib}. Each spelling of the root has its own
 * mark, so a path reads back spelled as it was written and Zinc's ids still compare equal.
 *
 * <p>The engine names the root in the workdir's {@link #ROOT_FILE}. A workdir without one is read
 * and written with absolute paths, and so is a path outside the root (a jar in jk's store, a JDK):
 * on one host every checkout spells those the same.
 */
final class PortableAnalysis {

    /** The file in an incremental workdir that names the project root its analysis is spelled against. */
    static final String ROOT_FILE = "root";

    /** What a path under the root starts with in a written analysis. */
    static final String MARK = "${root}";

    /** What a path under the root starts with when it was written with {@code /} on a host whose separator is not. */
    static final String SLASHED_MARK = "${root/}";

    private PortableAnalysis() {}

    /** The root {@code workdir}'s {@link #ROOT_FILE} names, when it names one. */
    static Optional<Path> rootOf(Path workdir) {
        try {
            Path file = workdir.resolve(ROOT_FILE);
            if (!Files.isRegularFile(file)) return Optional.empty();
            String line = Files.readString(file, StandardCharsets.UTF_8).strip();
            return line.isEmpty() ? Optional.empty() : Optional.of(Path.of(line));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Mappers spelling paths under {@code root} portably; Zinc's identity mappers when there is no root. */
    static ReadWriteMappers mappers(@Nullable Path root) {
        if (root == null) return ReadWriteMappers.getEmptyMappers();
        return mappers(root.toAbsolutePath().normalize().toString(), File.separatorChar);
    }

    /** {@link #mappers(Path)} for a root spelled {@code abs} on a host whose separator is {@code separator}. */
    static ReadWriteMappers mappers(String abs, char separator) {
        String slashed = abs.replace(separator, '/');
        if (slashed.equals(abs)) return new ReadWriteMappers(new Spelling(MARK, abs), new Spelling(abs, MARK));
        return new ReadWriteMappers(
                new Spelling(MARK, abs, SLASHED_MARK, slashed), new Spelling(abs, MARK, slashed, SLASHED_MARK));
    }

    /**
     * {@code text} with every occurrence of {@code from} that ends a path segment replaced by {@code
     * to}: followed by a separator, a path-list separator or the end, so a sibling directory whose
     * name merely starts with the root's ({@code /src/jk2} beside {@code /src/jk}) is left alone.
     */
    static String replace(String text, String from, String to) {
        StringBuilder out = null;
        int at = 0;
        for (int i = text.indexOf(from); i >= 0; i = text.indexOf(from, i + from.length())) {
            int end = i + from.length();
            if (end < text.length() && !boundary(text.charAt(end))) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, at, i).append(to);
            at = end;
        }
        return out == null ? text : out.append(text, at, text.length()).toString();
    }

    private static boolean boundary(char c) {
        return c == '/' || c == File.separatorChar || Classpaths.SEPARATOR.indexOf(c) == 0;
    }

    /**
     * One direction of the rebase, {@code from} to {@code to} and then {@code from2} to {@code to2}.
     * Zinc's read and write mappers declare the same methods, so one class serves both; stamps are
     * content hashes and pass through.
     */
    private record Spelling(
            String from,
            String to,
            @Nullable String from2,
            @Nullable String to2) implements ReadMapper, WriteMapper {

        Spelling(String from, String to) {
            this(from, to, null, null);
        }

        private String map(String text) {
            String once = replace(text, from, to);
            return from2 == null || to2 == null ? once : replace(once, from2, to2);
        }

        private VirtualFileRef map(VirtualFileRef file) {
            String id = map(file.id());
            return id.equals(file.id()) ? file : VirtualFileRef.of(id);
        }

        private Path map(Path path) {
            String text = path.toString();
            String mapped = map(text);
            return mapped.equals(text) ? path : Path.of(mapped);
        }

        @Override
        public VirtualFileRef mapSourceFile(VirtualFileRef file) {
            return map(file);
        }

        @Override
        public VirtualFileRef mapBinaryFile(VirtualFileRef file) {
            return map(file);
        }

        @Override
        public VirtualFileRef mapProductFile(VirtualFileRef file) {
            return map(file);
        }

        @Override
        public Path mapOutputDir(Path dir) {
            return map(dir);
        }

        @Override
        public Path mapSourceDir(Path dir) {
            return map(dir);
        }

        @Override
        public Path mapClasspathEntry(Path entry) {
            return map(entry);
        }

        @Override
        public String mapJavacOption(String option) {
            return map(option);
        }

        @Override
        public String mapScalacOption(String option) {
            return map(option);
        }

        @Override
        public Stamp mapBinaryStamp(VirtualFileRef file, Stamp stamp) {
            return stamp;
        }

        @Override
        public Stamp mapSourceStamp(VirtualFileRef file, Stamp stamp) {
            return stamp;
        }

        @Override
        public Stamp mapProductStamp(VirtualFileRef file, Stamp stamp) {
            return stamp;
        }

        @Override
        public MiniSetup mapMiniSetup(MiniSetup setup) {
            return setup;
        }
    }
}
