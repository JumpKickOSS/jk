// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.ClassPathJar;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.LibDir;
import cc.jumpkick.model.PackageId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The lib-dir distribution of an application: a runnable copy of the thin jar whose manifest
 * {@code Class-Path} names each runtime jar under a directory beside it, and that directory. It is
 * Maven's {@code copy-dependencies} plus {@code maven-jar-plugin}'s {@code addClasspath} and
 * {@code classpathPrefix}; the runtime jars are the ones the fat jar would bundle.
 */
public final class LibDirLayout {

    private LibDirLayout() {}

    /** How often a jar churns, which is the layer it rides in an image. */
    public enum Kind {
        /** A locked release version. */
        RELEASE,
        /** A locked {@code -SNAPSHOT} version. */
        SNAPSHOT,
        /** A workspace sibling's thin jar: no lock row, rebuilt with that module. */
        SIBLING
    }

    /** One jar of the lib directory: where it comes from and the file name it ships under. */
    public record Lib(Path jar, String name, Kind kind) {}

    /**
     * The module's runtime jars as the layout ships them, in classpath order, less the ones {@code
     * config} excludes. A locked jar is named by its coordinate (see {@link #fileName}); a sibling
     * keeps its own file name.
     */
    public static List<Lib> libs(Path moduleDir, JkBuild project, Path lockFile, Cas cas, LibDir config)
            throws IOException {
        ClasspathResolver resolver = new ClasspathResolver(cas);
        List<Path> jars = ModuleRuntimeClasspath.jars(moduleDir, project, lockFile, resolver);
        Map<Path, Lockfile.Artifact> rows = new HashMap<>();
        if (Files.exists(lockFile)) {
            Lockfile lock = MemberRows.view(LockfileReader.read(lockFile), lockFile, moduleDir);
            for (ClasspathResolver.Entry entry :
                    resolver.entriesFor(lock, ClasspathResolver.RUNTIME, false, project, moduleDir)) {
                if (entry.jar() != null) rows.put(entry.jar(), entry.artifact());
            }
        }
        return libs(jars, rows, config);
    }

    /**
     * Pure half of {@link #libs(Path, JkBuild, Path, Cas, LibDir)}: {@code rows} names the
     * lock row of each locked jar; a jar without one is a sibling. Two rows that would share a file
     * name are both qualified with their group, and a collision past that fails rather than ship a
     * directory that silently lost a jar.
     */
    public static List<Lib> libs(List<Path> jars, Map<Path, Lockfile.Artifact> rows, LibDir config) throws IOException {
        List<Path> kept = new ArrayList<>();
        Map<Path, String> base = new LinkedHashMap<>();
        Map<String, Integer> uses = new HashMap<>();
        for (Path jar : jars) {
            Lockfile.Artifact row = rows.get(jar);
            if (row != null && config.excludes(row.moduleGroup(), row.moduleArtifact())) continue;
            String name = row == null ? String.valueOf(jar.getFileName()) : fileName(row, config.versions());
            kept.add(jar);
            base.put(jar, name);
            uses.merge(name, 1, Integer::sum);
        }
        List<Lib> out = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Path jar : kept) {
            Lockfile.Artifact row = rows.get(jar);
            String name = Objects.requireNonNull(base.get(jar));
            if (row != null && uses.getOrDefault(name, 0) > 1) name = row.moduleGroup() + "-" + name;
            if (!taken.add(name)) {
                throw new IOException("two runtime jars ship as " + name + " in the lib directory; add one of them to"
                        + " [application].lib-exclude, or set lib-versions = true if versions are what differ");
            }
            Kind kind = row == null ? Kind.SIBLING : row.version().contains("SNAPSHOT") ? Kind.SNAPSHOT : Kind.RELEASE;
            out.add(new Lib(jar, name, kind));
        }
        return out;
    }

    /**
     * {@code <artifact>-<version>[-<classifier>].jar}, Maven's {@code copy-dependencies} name, or
     * {@code <artifact>[-<classifier>].jar} without the version.
     */
    public static String fileName(Lockfile.Artifact row, boolean versions) {
        String classifier = "";
        if (PackageId.isMavenPackageKey(row.name())) {
            String c = PackageId.parse(row.name()).classifier();
            if (c != null) classifier = c;
        }
        return row.moduleArtifact() + (versions ? "-" + row.version() : "")
                + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
    }

    /** The {@code Class-Path} of the runnable jar: every lib under {@code dir}, in classpath order. */
    public static List<String> classPath(String dir, List<Lib> libs) {
        return ClassPathJar.under(dir, libs.stream().map(Lib::name).toList());
    }

    /**
     * Write the layout: {@code libs} copied into {@code libDir}, the jars an earlier layout left
     * there removed, and {@code appJar} written from {@code thinJar} with the {@code Class-Path}
     * naming them under {@code dirName}. Returns every file written, the jar first.
     */
    public static List<Path> write(
            Path thinJar, Path appJar, Path libDir, String dirName, List<Lib> libs, String mainClass)
            throws IOException {
        Files.createDirectories(libDir);
        List<Path> written = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Lib lib : libs) {
            Path to = libDir.resolve(lib.name());
            Files.copy(lib.jar(), to, StandardCopyOption.REPLACE_EXISTING);
            names.add(lib.name());
            written.add(to);
        }
        prune(libDir, names);
        ClassPathJar.write(thinJar, appJar, classPath(dirName, libs), mainClass);
        written.addFirst(appJar);
        return written;
    }

    /**
     * Delete each jar directly under {@code libDir} that {@code keep} does not name: a dependency
     * dropped since the last layout, or one a cache restore did not write.
     */
    public static void prune(Path libDir, Set<String> keep) throws IOException {
        List<Path> stale = new ArrayList<>();
        PathUtil.forEachChild(libDir, (child, attrs) -> {
            String name = String.valueOf(child.getFileName());
            if (attrs.isRegularFile() && name.endsWith(".jar") && !keep.contains(name)) stale.add(child);
            return true;
        });
        for (Path p : stale) Files.deleteIfExists(p);
    }
}
