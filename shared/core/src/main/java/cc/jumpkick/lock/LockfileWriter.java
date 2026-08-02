// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Scope;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic TOML emitter for {@link Lockfile}: sorted keys, LF newlines, two-space indent, no
 * comments, terminal newline.
 */
public final class LockfileWriter {

    private LockfileWriter() {}

    public static void write(Lockfile lockfile, Path file) throws IOException {
        // Always stamp a live manifests digest so staleness survives git-clone mtimes.
        Path owner = file.toAbsolutePath().normalize().getParent();
        write(lockfile, file, LockManifestDigest.compute(owner));
    }

    /**
     * As {@link #write(Lockfile, Path)} with a caller-captured {@code manifestsSha256} — capture it
     * when the manifests are first read so a manifest edited mid-resolution leaves a lock that
     * reads as stale, instead of stamping itself fresh from the live files (JK-1357).
     */
    public static void write(Lockfile lockfile, Path file, String manifestsSha256) throws IOException {
        Lockfile stamped = lockfile.withManifestsSha256(manifestsSha256);
        // Atomic (temp + rename): concurrent readers never observe a truncated lock (JK-1356).
        cc.jumpkick.util.AtomicWrites.replace(file, render(stamped));
    }

    /** Engine-jar sha from {@code versions/<v>/manifest.toml}, or {@code ""} if absent. */
    private static String runningEngineSha(String version) {
        try {
            java.nio.file.Path manifest =
                    cc.jumpkick.util.JkDirs.versions().resolve(version).resolve("manifest.toml");
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.startsWith("engine-sha256")) {
                    int q = line.indexOf('"');
                    int e = line.lastIndexOf('"');
                    if (q >= 0 && e > q) return line.substring(q + 1, e);
                }
            }
        } catch (java.io.IOException ignored) {
            // no materialized manifest — dev builds
        }
        return "";
    }

    public static String render(Lockfile lockfile) {
        StringBuilder out = new StringBuilder(256);
        out.append("version = ").append(lockfile.version()).append('\n');
        out.append("generated-by = ").append(quote(lockfile.generatedBy())).append('\n');
        out.append("resolution-algorithm = ")
                .append(quote(lockfile.resolutionAlgorithm()))
                .append('\n');
        if (lockfile.jdk() != null) {
            out.append("jdk = ").append(quote(lockfile.jdk())).append('\n');
        }
        if (lockfile.kotlin() != null) {
            out.append("kotlin = ").append(quote(lockfile.kotlin())).append('\n');
        }
        // Stamp running jk toolchain when the lock has none yet.
        Lockfile.JkToolchain jk = lockfile.jk() != null
                ? lockfile.jk()
                : new Lockfile.JkToolchain(
                        cc.jumpkick.model.JkVersion.VERSION, runningEngineSha(cc.jumpkick.model.JkVersion.VERSION));
        out.append("jk = { version = ")
                .append(quote(jk.version()))
                .append(", sha256 = ")
                .append(quote(jk.sha256() == null ? "" : jk.sha256()))
                .append(" }\n");
        if (lockfile.manifestsSha256() != null && !lockfile.manifestsSha256().isBlank()) {
            out.append("manifests-sha256 = ")
                    .append(quote(lockfile.manifestsSha256()))
                    .append('\n');
        }

        List<Lockfile.Artifact> sorted = new ArrayList<>(lockfile.artifacts());
        sorted.sort(Comparator.comparing(Lockfile.Artifact::name).thenComparing(Lockfile.Artifact::version));

        for (Lockfile.Artifact pkg : sorted) {
            out.append('\n');
            out.append("[[artifact]]\n");
            out.append("name     = ").append(quote(pkg.name())).append('\n');
            out.append("version  = ").append(quote(pkg.version())).append('\n');
            out.append("source   = ").append(quote(pkg.source())).append('\n');
            if (pkg.checksum() != null) {
                out.append("checksum = ").append(quote(pkg.checksum())).append('\n');
            }
            if (pkg.sourcesChecksum() != null) {
                out.append("sources  = ").append(quote(pkg.sourcesChecksum())).append('\n');
            }
            if (pkg.pinnedBy() != null) {
                out.append("pinned-by = ").append(quote(pkg.pinnedBy())).append('\n');
            }
            if (pkg.path() != null) {
                out.append("path     = ").append(quote(pkg.path())).append('\n');
            }
            if (pkg.git() != null) {
                out.append("git      = ").append(quote(pkg.git().url())).append('\n');
                out.append("rev      = ").append(quote(pkg.git().rev())).append('\n');
                if (pkg.git().ref() != null) {
                    out.append("ref      = ").append(quote(pkg.git().ref())).append('\n');
                }
            }
            if (!pkg.scopes().isEmpty()) {
                List<Scope> sortedScopes = new ArrayList<>(pkg.scopes());
                sortedScopes.sort(Comparator.naturalOrder());
                out.append("scopes   = [");
                for (int i = 0; i < sortedScopes.size(); i++) {
                    if (i > 0) out.append(", ");
                    out.append(quote(sortedScopes.get(i).canonical()));
                }
                out.append("]\n");
            }
            if (!pkg.deps().isEmpty()) {
                List<String> deps = new ArrayList<>(pkg.deps());
                deps.sort(Comparator.naturalOrder());
                out.append("deps = [\n");
                for (String dep : deps) {
                    out.append("  ").append(quote(dep)).append(",\n");
                }
                out.append("]\n");
            }
        }

        List<Lockfile.PluginEntry> sortedPlugins = new ArrayList<>(lockfile.plugins());
        sortedPlugins.sort(
                Comparator.comparing(Lockfile.PluginEntry::coordinate).thenComparing(Lockfile.PluginEntry::version));

        for (Lockfile.PluginEntry p : sortedPlugins) {
            out.append('\n');
            out.append("[[plugin]]\n");
            out.append("coordinate = ").append(quote(p.coordinate())).append('\n');
            out.append("version    = ").append(quote(p.version())).append('\n');
            out.append("checksum   = ").append(quote(p.checksum())).append('\n');
        }

        List<Lockfile.SdkEntry> sortedSdk = new ArrayList<>(lockfile.sdk());
        sortedSdk.sort(Comparator.comparing(Lockfile.SdkEntry::component));
        for (Lockfile.SdkEntry e : sortedSdk) {
            out.append('\n');
            out.append("[[sdk]]\n");
            out.append("component = ").append(quote(e.component())).append('\n');
            out.append("revision  = ").append(quote(e.revision())).append('\n');
        }

        List<Lockfile.ModuleEntry> sortedModules = new ArrayList<>(lockfile.modules());
        sortedModules.sort(Comparator.comparing(Lockfile.ModuleEntry::path).thenComparing(Lockfile.ModuleEntry::name));
        for (Lockfile.ModuleEntry m : sortedModules) {
            out.append('\n');
            out.append("[[module]]\n");
            out.append("path    = ").append(quote(m.path())).append('\n');
            out.append("group   = ").append(quote(m.group())).append('\n');
            out.append("name    = ").append(quote(m.name())).append('\n');
            out.append("version = ").append(quote(m.version())).append('\n');
            if (m.jdk() != null && !m.jdk().isBlank()) {
                out.append("jdk     = ").append(quote(m.jdk())).append('\n');
            }
            if (m.java() != null && m.java() > 0) {
                out.append("java    = ").append(m.java()).append('\n');
            }
            if (m.kotlin() != null && !m.kotlin().isBlank()) {
                out.append("kotlin  = ").append(quote(m.kotlin())).append('\n');
            }
            if (m.groovy() != null && !m.groovy().isBlank()) {
                out.append("groovy  = ").append(quote(m.groovy())).append('\n');
            }
            if (m.description() != null && !m.description().isBlank()) {
                out.append("description = ").append(quote(m.description())).append('\n');
            }
            if (m.sources() != null && !m.sources().isBlank() && !"disabled".equals(m.sources())) {
                out.append("sources = ").append(quote(m.sources())).append('\n');
            }
            if (Boolean.TRUE.equals(m.m2install())) {
                out.append("m2install = true\n");
            }
            if (m.layout() != null && !m.layout().isBlank() && !"auto".equalsIgnoreCase(m.layout())) {
                out.append("layout  = ").append(quote(m.layout())).append('\n');
            }
        }

        return out.toString();
    }

    private static String quote(String value) {
        return MinimalToml.quote(value);
    }
}
