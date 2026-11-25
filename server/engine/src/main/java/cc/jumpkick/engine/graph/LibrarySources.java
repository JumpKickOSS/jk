// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.repo.ArtifactLocator;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.runtime.LockPipeline;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.runtime.workspace.SyncPlans;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The one {@code -sources.jar} a library read needs: the locked jar that declares the type a {@code
 * read} names is found by its class entries, as the graph worker resolves the name, and its sources
 * jar is fetched when this machine holds none, so the answer carries Javadoc without a {@code jk sync
 * --sources} first.
 */
final class LibrarySources {

    private LibrarySources() {}

    /** A type or member address: {@code ObjectMapper}, {@code ObjectMapper.readValue}, {@code com.acme.Shop}. */
    private static final Pattern ADDRESS = Pattern.compile("[\\p{L}_$][\\w$]*(?:\\.[\\p{L}_$][\\w$]*)*");

    /** A file name, which {@code read} takes as a path rather than a symbol. */
    private static final Pattern FILE =
            Pattern.compile("\\.(?:java|kt|kts|groovy|gradle|scala|xml|toml|properties|json|ya?ml|md|adoc|sql)$");

    /** One fetch: the sources jar on disk after it, and whether the repository said it has none. */
    private record Fetched(@Nullable Path sources, boolean unpublished) {}

    /** Rows whose repository answered it serves no sources jar, by {@code source \t coord}, asked again after an hour. */
    private static final StampedMemo<String, Boolean, Fetched> UNPUBLISHED =
            StampedMemo.<String, Boolean, Fetched>bounded(4096)
                    .keeping(Fetched::unpublished)
                    .expiring(Duration.ofHours(1));

    /**
     * The single type or member address a {@code read} names, the only shape the worker answers from a
     * library; {@code null} for files, several targets, globs or line ranges.
     */
    static @Nullable String target(List<String> args) {
        if (args.size() != 1) return null;
        String a = args.getFirst().strip();
        return ADDRESS.matcher(a).matches() && !FILE.matcher(a).find() ? a : null;
    }

    /**
     * Of {@code jars} (each locked artifact and its jar), the one that declares the type {@code
     * target} names, or the type its last segment is a member of: a qualified name before a name
     * that only ends with it, as the worker resolves it. {@code null} when none or several types
     * answer, which the worker refuses or answers from the workspace.
     */
    static <A> @Nullable A declaring(Map<A, Path> jars, String target) {
        Map<String, A> types = types(jars, target);
        if (types.isEmpty() && target.indexOf('.') > 0)
            types = types(jars, target.substring(0, target.lastIndexOf('.')));
        return types.size() == 1 ? types.values().iterator().next() : null;
    }

    /** The types {@code type} names by dotted name, each with the jar that declares it first. */
    private static <A> Map<String, A> types(Map<A, Path> jars, String type) {
        Map<String, A> qualified = new LinkedHashMap<>();
        Map<String, A> partial = new LinkedHashMap<>();
        String simple = type.substring(type.lastIndexOf('.') + 1);
        for (Map.Entry<A, Path> jar : jars.entrySet()) {
            for (String dotted : classes(jar.getValue(), simple)) {
                if (dotted.equals(type)) qualified.putIfAbsent(dotted, jar.getKey());
                else if (dotted.endsWith("." + type)) partial.putIfAbsent(dotted, jar.getKey());
            }
        }
        return qualified.isEmpty() ? partial : qualified;
    }

    /** The dotted names of the named classes in {@code jar} whose simple name is {@code simple}. */
    private static Set<String> classes(Path jar, String simple) {
        Set<String> out = new LinkedHashSet<>();
        String plain = "/" + simple + ".class";
        String nested = "$" + simple + ".class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.startsWith("META-INF/")) continue;
                if (!name.endsWith(plain) && !name.endsWith(nested) && !name.equals(simple + ".class")) continue;
                out.add(name.substring(0, name.length() - ".class".length())
                        .replace('/', '.')
                        .replace('$', '.'));
            }
        } catch (IOException unreadable) {
            Log.debug("graph: unreadable jar " + jar, unreadable);
        }
        return out;
    }

    /**
     * Fetch the row's sources jar on demand. An unpinned sources jar missing from its
     * repository is remembered for an hour.
     */
    static @Nullable Path fetch(
            Path root, Cas cas, boolean m2, Lockfile locked, Lockfile.Artifact row, ArtifactLocator locator) {
        return UNPUBLISHED
                .get(
                        row.source() + "\t" + row.displayCoord(),
                        Boolean.TRUE,
                        () -> sync(root, cas, m2, locked, row, locator))
                .sources();
    }

    private static Fetched sync(
            Path root, Cas cas, boolean m2, Lockfile locked, Lockfile.Artifact row, ArtifactLocator locator) {
        AtomicBoolean none = new AtomicBoolean();
        try {
            var project = SyncPlans.parseBuildIfPresent(root);
            var repos = project == null
                    ? RepoGroupBuilder.buildDefault(cas)
                    : LockPipeline.repositoriesForLock(project, root, null, cas);
            new CacheSync(cas, new Http(), m2)
                    .withRepositories(repos)
                    .syncSources(locked.withArtifacts(List.of(row)), new CacheSync.ProgressObserver() {
                        @Override
                        public void skipped(Lockfile.Artifact pkg) {
                            none.set(true);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, @Nullable String error) {
                            Log.debug("graph: no sources for " + pkg.displayCoord() + ": " + error);
                        }
                    });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched(null, false);
        } catch (UserConfigParseException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException unreachable) {
            Log.debug("graph: no sources for " + row.displayCoord(), unreachable);
            return new Fetched(null, false);
        }
        return new Fetched(locator.locateSources(row).orElse(null), none.get());
    }
}
