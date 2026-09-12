// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.builds.DeclaredDeps;
import cc.jumpkick.builds.DepFrequency;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic TOML emitter for {@link Lockfile}: sorted keys, LF newlines, two-space indent, no
 * comments, terminal newline.
 */
public final class LockfileWriter {

    private LockfileWriter() {}

    /** The directory that owns {@code file}. A lockfile at a filesystem root has no owner. */
    private static Path ownerOf(Path file) {
        Path owner = file.toAbsolutePath().normalize().getParent();
        if (owner == null) throw new IllegalArgumentException("jk-lock.toml must live in a directory: " + file);
        return owner;
    }

    public static void write(Lockfile lockfile, Path file) throws IOException {
        // Always stamp a live manifests digest so staleness survives git-clone mtimes.
        Path owner = ownerOf(file);
        write(lockfile, file, LockManifestDigest.compute(owner));
    }

    /**
     * As {@link #write(Lockfile, Path)} with a caller-captured {@code manifestsSha256} — capture it
     * when the manifests are first read so a manifest edited mid-resolution leaves a lock that
     * reads as stale, instead of stamping itself fresh from the live files.
     */
    public static void write(Lockfile lockfile, Path file, String manifestsSha256) throws IOException {
        Lockfile stamped = lockfile.withManifestsSha256(manifestsSha256);
        // Preserve / mint durable project-id: never drop on rewrite.
        Path owner = ownerOf(file);
        if (stamped.projectId() == null || stamped.projectId().isBlank()) {
            String existing = null;
            if (Files.isRegularFile(file)) {
                try {
                    existing = LockfileReader.read(file).projectId();
                } catch (Exception e) {
                    // torn or unreadable — mint/recover below
                    Log.debug("write: torn or unreadable", e);
                }
            }
            if (existing != null && !existing.isBlank()) {
                stamped = stamped.withProjectId(existing);
            } else {
                stamped = ProjectIdentity.ensureProjectId(stamped, owner);
            }
        }
        // Atomic (temp + rename): concurrent readers never observe a truncated lock.
        // Durable: the lockfile is the source of truth, not a cache. A torn target after power loss is
        // not recoverable by re-running — the resolve that produced it is gone.
        AtomicWrites.replaceDurably(file, render(stamped));
        // Materialize identity.toml so project= id resolves to a checkout without a prior build.
        try {
            LockfileReader.clearCache();
            ProjectIdentity identity = ProjectIdentity.resolve(owner);
            Path home = ProjectBuilds.projectHome(ProjectBuilds.buildsRoot(), identity);
            ProjectIdentity.IdentityFile.write(home, identity);
            // Host declared-dep frequency for the New wizard library picker.
            try {
                String id = identity.id();
                Set<String> deps = DeclaredDeps.collect(owner);
                DepFrequency.load().observe(id, deps).save();
            } catch (Exception ignoredFreq) {
                // never fail the lock write over frequency tracking
                Log.debug("write: never fail the lock write over frequency tracking", ignoredFreq);
            }
        } catch (Exception e) {
            // best-effort; lock is already durable
            Log.debug("write: best-effort", e);
        }
    }

    /**
     * The floor stamped into locks that have none: the oldest jk whose reader understands this
     * writer's output. Bumped by hand only when the lock format actually requires a newer
     * reader — never auto-bumped to the writing jk's own version, or an old CI binary dies
     * because someone ran a newer laptop.
     */
    static final String FORMAT_FLOOR = "0.12.0";

    public static String render(Lockfile lockfile) {
        StringBuilder out = new StringBuilder(256);
        writeHeader(out, lockfile);
        writeArtifacts(out, lockfile);
        writePlugins(out, lockfile);
        writeSdk(out, lockfile);
        writeModules(out, lockfile);
        return out.toString();
    }

    /** Top-level scalars, then the toolchain and native tables that must follow them. */
    private static void writeHeader(StringBuilder out, Lockfile lockfile) {
        out.append("version = ").append(lockfile.version()).append('\n');
        out.append("generated-by = ").append(quote(lockfile.generatedBy())).append('\n');
        out.append("resolution-algorithm = ")
                .append(quote(lockfile.resolutionAlgorithm()))
                .append('\n');
        if (lockfile.kotlin() != null) {
            out.append("kotlin = ").append(quote(lockfile.kotlin())).append('\n');
        }
        if (lockfile.scala() != null) {
            out.append("scala = ").append(quote(lockfile.scala())).append('\n');
        }
        // The jk floor is preserved, never auto-bumped; a floor-less lock gets the format floor.
        String jkMin = lockfile.jkMin() != null && !lockfile.jkMin().isBlank() ? lockfile.jkMin() : FORMAT_FLOOR;
        out.append("jk-min = ").append(quote(jkMin)).append('\n');
        if (lockfile.manifestsSha256() != null && !lockfile.manifestsSha256().isBlank()) {
            out.append("manifests-sha256 = ")
                    .append(quote(lockfile.manifestsSha256()))
                    .append('\n');
        }
        if (lockfile.projectId() != null && !lockfile.projectId().isBlank()) {
            out.append("project-id = ").append(quote(lockfile.projectId())).append('\n');
        }
        // Tables after top-level scalars: opening one would swallow any key written after it.
        // The [[artifact]] rows below close whatever table is open.
        writeToolchain(out, "jdk", lockfile.jdk());
        writeToolchain(out, "graal", lockfile.graal());
        Lockfile.NativeMetadata pin = lockfile.nativeMetadata();
        if (pin != null) {
            out.append("\n[native]\n");
            out.append("metadata-repository = ").append(quote(pin.version())).append('\n');
            if (pin.checksum() != null && !pin.checksum().isBlank()) {
                out.append("checksum = ").append(quote(pin.checksum())).append('\n');
            }
        }
    }

    /** One {@code [[artifact]]} row per artifact, sorted by name then version. */
    private static void writeArtifacts(StringBuilder out, Lockfile lockfile) {
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
    }

    private static void writePlugins(StringBuilder out, Lockfile lockfile) {
        List<Lockfile.PluginEntry> sortedPlugins = new ArrayList<>(lockfile.plugins());
        sortedPlugins.sort(
                Comparator.comparing(Lockfile.PluginEntry::coordinate).thenComparing(Lockfile.PluginEntry::version));

        for (Lockfile.PluginEntry p : sortedPlugins) {
            out.append('\n');
            out.append("[[plugin]]\n");
            out.append("coordinate = ").append(quote(p.coordinate())).append('\n');
            out.append("version    = ").append(quote(p.version())).append('\n');
            String checksum = p.checksum();
            String path = p.path();
            if (checksum != null)
                out.append("checksum   = ").append(quote(checksum)).append('\n');
            if (path != null) out.append("path       = ").append(quote(path)).append('\n');
        }
    }

    private static void writeSdk(StringBuilder out, Lockfile lockfile) {
        List<Lockfile.SdkEntry> sortedSdk = new ArrayList<>(lockfile.sdk());
        sortedSdk.sort(Comparator.comparing(Lockfile.SdkEntry::component));
        for (Lockfile.SdkEntry e : sortedSdk) {
            out.append('\n');
            out.append("[[sdk]]\n");
            out.append("component = ").append(quote(e.component())).append('\n');
            out.append("revision  = ").append(quote(e.revision())).append('\n');
        }
    }

    /** One {@code [[module]]} row per workspace module, sorted by path then name; blank fields left out. */
    private static void writeModules(StringBuilder out, Lockfile lockfile) {
        List<Lockfile.ModuleEntry> sortedModules = new ArrayList<>(lockfile.modules());
        sortedModules.sort(Comparator.comparing(Lockfile.ModuleEntry::path).thenComparing(Lockfile.ModuleEntry::name));
        for (Lockfile.ModuleEntry m : sortedModules) {
            out.append('\n');
            out.append("[[module]]\n");
            out.append("path    = ").append(quote(m.path())).append('\n');
            out.append("group   = ").append(quote(m.group())).append('\n');
            out.append("name    = ").append(quote(m.name())).append('\n');
            out.append("version = ").append(quote(m.version())).append('\n');
            if (m.java() != null && m.java() > 0) {
                out.append("java    = ").append(m.java()).append('\n');
            }
            if (m.kotlin() != null && !m.kotlin().isBlank()) {
                out.append("kotlin  = ").append(quote(m.kotlin())).append('\n');
            }
            if (m.groovy() != null && !m.groovy().isBlank()) {
                out.append("groovy  = ").append(quote(m.groovy())).append('\n');
            }
            if (m.scala() != null && !m.scala().isBlank()) {
                out.append("scala   = ").append(quote(m.scala())).append('\n');
            }
            if (m.description() != null && !m.description().isBlank()) {
                out.append("description = ").append(quote(m.description())).append('\n');
            }
            if (m.sources() != null && !m.sources().isBlank() && !"disabled".equals(m.sources())) {
                out.append("sources = ").append(quote(m.sources())).append('\n');
            }
            if (Boolean.FALSE.equals(m.m2integration())) {
                out.append("m2.integration = false\n");
            }
            if (Boolean.FALSE.equals(m.m2install())) {
                out.append("m2.install = false\n");
            }
        }
    }

    /**
     * One toolchain table. Each axis is written on exactly one side — a required vendor makes the
     * suggested one noise — and a blank field is left out rather than written empty.
     */
    private static void writeToolchain(StringBuilder out, String table, Lockfile.@Nullable ToolchainPin pin) {
        if (pin == null || pin.isEmpty()) return;
        out.append('\n').append('[').append(table).append(']').append('\n');
        field(out, "suggested-vendor", pin.suggestedVendor());
        field(out, "suggested-version", pin.suggestedVersion());
        field(out, "required-vendor", pin.requiredVendor());
        field(out, "required-version", pin.requiredVersion());
    }

    private static void field(StringBuilder out, String key, @Nullable String value) {
        if (value == null || value.isEmpty()) return;
        out.append(key).append(" = ").append(quote(value)).append('\n');
    }

    private static String quote(String value) {
        return MinimalToml.quote(value);
    }
}
