// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.discovery.DiscoveredTool;
import cc.jumpkick.discovery.SymlinkProvisioner;
import cc.jumpkick.discovery.ToolHealth;
import cc.jumpkick.discovery.ToolProvisioner;
import cc.jumpkick.discovery.ToolSpec;
import cc.jumpkick.http.Http;
import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Provision Maven/Gradle/Kotlin: healthy cache → discover+symlink → {@link ToolInstaller} download.
 */
public final class ToolProvisioning {

    private ToolProvisioning() {}

    /**
     * How a tool was obtained: {@code detail} is the discovered install for a link or the download
     * URI for a download; {@code verification} is, for a download, what vouched for the archive in
     * the words the {@code downloaded} line prints, else empty.
     */
    public record Result(InstalledTool tool, Source source, String detail, String verification) {
        public enum Source {
            CACHED,
            LINKED,
            DOWNLOADED
        }

        public Result {
            if (tool == null) throw new IllegalArgumentException("tool");
            if (source == null) throw new IllegalArgumentException("source");
            if (detail == null) detail = "";
            if (verification == null) verification = "";
        }
    }

    /**
     * What a provisioning run may do: skip the look for a host install, ignore a cached one, and
     * install an archive no checksum vouches for ({@link ToolRegistry#ACCEPT_FLAG}).
     */
    public record Policy(boolean noDiscover, boolean noCache, boolean acceptUnverified) {
        public static final Policy DEFAULT = new Policy(false, false, false);
    }

    /**
     * Provision the tool described by {@code distribution}, preferring a local install over a
     * download. Returns the resolved {@link InstalledTool} and a tag describing how it was obtained
     * (for log output).
     */
    public static Result provision(ToolDistribution distribution, ToolRegistry registry, Http http, Policy policy)
            throws IOException, InterruptedException {
        return provision(distribution, registry, http, policy, new ToolProvisioner());
    }

    static Result provision(
            ToolDistribution distribution, ToolRegistry registry, Http http, Policy policy, ToolProvisioner provisioner)
            throws IOException, InterruptedException {

        ToolSpec spec = specFor(distribution);

        // 1. Healthy cached install wins (skipped when noCache).
        Optional<InstalledTool> existing = registry.find(distribution.tool(), distribution.version());
        if (!policy.noCache()
                && existing.isPresent()
                && isHealthyEntry(spec, existing.get().home())) {
            return new Result(existing.get(), Result.Source.CACHED, "", "");
        }
        // 2. Broken cache entry — purge and continue. Through JkOwnership rather than a local
        // three-arm copy: link-vs-populated-directory is the same question got wrong
        // elsewhere, and having one answer is the point of the owner.
        if (existing.isPresent()) {
            JkOwnership.removeIfOwned(existing.get().home());
        }

        // 3. Probe the host for an existing install.
        if (!policy.noDiscover() && SymlinkProvisioner.canSymlink()) {
            Optional<DiscoveredTool> hit = provisioner.discover(spec);
            if (hit.isPresent()) {
                Path link = registry.installDir(distribution.tool(), distribution.version());
                SymlinkProvisioner.link(link, hit.get().home());
                return new Result(
                        new InstalledTool(distribution.tool(), distribution.version(), link),
                        Result.Source.LINKED,
                        hit.get().source() + " → " + hit.get().home(),
                        "");
            }
        }

        // 4. Download fallback.
        ToolInstaller.Installed installed =
                new ToolInstaller(http, registry).install(distribution, policy.acceptUnverified());
        return new Result(
                installed.tool(),
                Result.Source.DOWNLOADED,
                distribution.downloadUri().toString(),
                installed.verification());
    }

    /**
     * Symlinked entries get the full {@link ToolHealth} check (broken-link detection + version-pin
     * verification — catches silent upstream updates). Real directories we wrote ourselves get the
     * cheap "binary still there?" check — we trust the dir name to encode the version, so a full
     * {@code lib/maven-core-X.jar} probe is overkill.
     */
    private static boolean isHealthyEntry(ToolSpec spec, Path home) {
        if (Files.isSymbolicLink(home)) {
            return ToolHealth.isHealthy(spec, home);
        }
        return Files.exists(ToolHealth.requiredBinary(spec, home));
    }

    private static ToolSpec specFor(ToolDistribution distribution) {
        return new ToolSpec(distribution.tool().slug(), distribution.version(), null);
    }
}
