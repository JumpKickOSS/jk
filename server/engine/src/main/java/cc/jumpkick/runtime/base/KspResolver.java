// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PubGrubResolver;
import cc.jumpkick.resolver.Resolution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Resolves/fetches the KSP2 tool closure for {@code KSPJvmMain} (Maven via {@link PubGrubResolver}),
 * CAS-cached under {@code tools/ksp/}. The version is {@link #DEFAULT_VERSION} unless the module
 * pins another with {@code [build] ksp-version}; a build never asks the network which is newest.
 */
public final class KspResolver {

    /** The single root coordinate; its POM drags in api + common-deps + the embedded compiler. */
    public static final String KSP_AA_MODULE = "com.google.devtools.ksp:symbol-processing-aa-embeddable";

    /** The CLI entry point inside the closure (ships in {@code symbol-processing-aa-embeddable}). */
    public static final String KSP_MAIN = "com.google.devtools.ksp.cmdline.KSPJvmMain";

    /** jk's KSP2 release: a standalone version, independent of the Kotlin compiler's. */
    public static final String DEFAULT_VERSION = "2.3.12";

    private KspResolver() {}

    /** The KSP2 release {@code build}'s round runs: its {@code [build] ksp-version}, else {@link #DEFAULT_VERSION}. */
    public static String versionFor(JkBuild build) {
        String pinned = build.build().kspVersion();
        return pinned != null ? pinned : DEFAULT_VERSION;
    }

    /**
     * Resolve and fetch the KSP2 closure for {@code kspVersion}, returning local CAS jar paths for
     * the fork's classpath.
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String kspVersion)
            throws IOException, InterruptedException {
        Path cacheFile = cacheFile(cas, kspVersion);
        boolean refresh = SessionContext.current().config().forceOr(false);
        if (!refresh) {
            List<Path> cached = readCachedClosure(cacheFile, cas);
            if (cached != null) return cached;
        }

        Dependency root = new Dependency(KSP_AA_MODULE, VersionSelector.parse("=" + kspVersion));
        Resolution resolution = new PubGrubResolver(repos).resolve(List.of(root));
        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            // POM-only modules (BOMs/parents) carry no jar — omitted from the classpath.
            var hit = repos.tryFetchArtifact(mod.coordinate());
            if (hit.isEmpty()) continue;
            jars.add(hit.get().fetched().cachePath());
            shas.add(hit.get().fetched().sha256());
        }
        if (jars.isEmpty()) {
            throw new IOException(
                    "KSP closure for " + kspVersion + " resolved to no jars — is " + KSP_AA_MODULE + " reachable?");
        }
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, String.join("\n", shas), StandardCharsets.UTF_8);
        return jars;
    }

    private static Path cacheFile(Cas cas, String version) {
        return cas.root().resolve("tools").resolve("ksp").resolve(version).resolve("closure.shas");
    }

    private static @Nullable List<Path> readCachedClosure(Path cacheFile, Cas cas) {
        if (!Files.isRegularFile(cacheFile)) return null;
        try {
            List<Path> out = new ArrayList<>();
            for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                String sha = line.strip();
                if (sha.isEmpty()) continue;
                if (!cas.contains(sha)) return null; // evicted — re-resolve
                out.add(cas.pathFor(sha));
            }
            return out.isEmpty() ? null : out;
        } catch (IOException e) {
            return null;
        }
    }
}
