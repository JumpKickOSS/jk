// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.PomReactorScan;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.mvn.FrontendCollector;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.mvn.PomShadow;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The engine's {@link ManifestPaths.ShadowSource}: a module with a {@code pom.xml} and no {@code
 * jk.toml} gets its manifest rendered from the effective POM into {@link ManifestPaths#shadowDir},
 * and rendered again whenever a POM it read (or the running jk) changes. A reactor is rendered
 * from its root: the root's shadow lists the leaves as a workspace and every leaf's shadow lands
 * under the leaf's own shadow directory, whichever of them is read first. Every reader of a
 * module's manifest goes through {@link ManifestPaths#manifestIn}, so a shadow is materialized by
 * the first reader and shared by the rest.
 *
 * <p>Rendering happens once per POM change; the rows of the import report that {@code jk import}
 * would grade Tier 3 are parked per module until the next build's parse step {@linkplain
 * #drainTier3 drains} them into its warnings, so a build says once what the shadow does not carry.
 * The reactor's own rows ride with the first leaf that drains. A render that failed on a read —
 * a repository that stopped answering — is {@linkplain ShadowRenderFailures remembered}, so the
 * job's next reader of the same POM gets the failure at once rather than waiting the stall window
 * out again.
 */
public final class ShadowManifests {

    private ShadowManifests() {}

    private static final Map<Path, List<String>> PENDING_TIER3 = new ConcurrentHashMap<>();

    /** What a rendering changed from the POM's own words (raised pins), reported once like the rows. */
    private static final Map<Path, List<String>> PENDING_NOTES = new ConcurrentHashMap<>();

    /** Leaf module → the reactor root whose rendering wrote its shadow. */
    private static final Map<Path, Path> ROOT_OF = new ConcurrentHashMap<>();

    /** Per-module render gate: two readers racing on the same POM render it once. */
    private static final Map<Path, Object> GATES = new ConcurrentHashMap<>();

    private static final ShadowRenderFailures FAILURES = ShadowRenderFailures.forStallWindow();

    /** How a rendering raises its pins: the lock scope at a directory in, one report line per raise out. */
    @FunctionalInterface
    public interface PinRaises {
        List<String> apply(Path lockDir) throws Exception;
    }

    private static volatile PinRaises pinRaises = dir -> List.of();

    /** Make this class the process's shadow source, raising each rendering's pins with {@code raises}. */
    public static void install(PinRaises raises) {
        pinRaises = raises;
        ManifestPaths.installShadowSource(ShadowManifests::materialize);
    }

    /**
     * The shadow manifest of {@code dir}, rendered now when absent or behind the POM files it read.
     * A leaf of a reactor is rendered by its root; a directory the root lists that Maven would not
     * build here (an aggregator, a module of an inactive profile) is a {@link NotBuiltHere} naming
     * the profile and the remedies. An unreadable POM, or a parent read that failed within the last
     * stall window, is an {@link UncheckedIOException}.
     */
    public static Path materialize(Path dir) {
        Path module = dir.toAbsolutePath().normalize();
        Path shadow = ManifestPaths.shadowManifestPath(module);
        if (PomShadow.isCurrent(shadow, module)) return shadow;
        Optional<Path> root = PomReactorScan.reactorRootOf(module);
        if (root.isEmpty()) {
            synchronized (GATES.computeIfAbsent(module, k -> new Object())) {
                if (!PomShadow.isCurrent(shadow, module)) render(module);
            }
            return shadow;
        }
        // A leaf is rendered by its root, and only the root's gate is ever held: the root's render
        // resolves the workspace, which reads every leaf, so a leaf gate held while waiting for the
        // root would deadlock against it. A stale or missing leaf shadow under a current root means
        // the root has to render again.
        materialize(root.get());
        if (PomShadow.isCurrent(shadow, module)) return shadow;
        synchronized (GATES.computeIfAbsent(root.get(), k -> new Object())) {
            if (!PomShadow.isCurrent(shadow, module)) render(root.get());
        }
        if (PomShadow.isCurrent(shadow, module)) return shadow;
        throw notBuiltHere(module, root.get());
    }

    /**
     * A directory its reactor root lists that Maven would not build on this machine: a module only
     * an inactive profile lists, or an aggregator. One line, naming the profile and both remedies.
     */
    public static final class NotBuiltHere extends IllegalStateException {
        NotBuiltHere(String message) {
            super(message);
        }
    }

    private static NotBuiltHere notBuiltHere(Path module, Path root) {
        Path pom = root.resolve(ManifestPaths.POM);
        Set<String> profiles = PomReactorScan.profilesListing(root, module);
        if (profiles.isEmpty()) {
            return new NotBuiltHere(module + " is listed by " + pom
                    + " as an aggregator, not as a module Maven builds; build from " + root
                    + ", or run `jk import " + ManifestPaths.POM + "` there to own a " + ManifestPaths.MANIFEST);
        }
        String named = profiles.stream().map(id -> "`" + id + "`").collect(Collectors.joining(", "));
        return new NotBuiltHere(module + " is listed by " + pom + " only in "
                + (profiles.size() == 1 ? "profile " : "profiles ") + named
                + ", which Maven does not activate on this machine, so the in-place build skips it;"
                + " activate the profile in Maven terms (<activeByDefault>true</activeByDefault>, or an <activation>"
                + " that holds here), or run `jk import " + ManifestPaths.POM + "` at " + root
                + " and list the module under [workspace] modules");
    }

    /**
     * Render {@code module}'s shadow, and with it every leaf's when {@code module} is a reactor root.
     * A failure is remembered for the stall window and returned at once to the next caller.
     */
    private static void render(Path module) {
        Optional<IOException> remembered = FAILURES.recall(module);
        if (remembered.isPresent()) throw new UncheckedIOException(remembered.get());
        Path pom = module.resolve(ManifestPaths.POM);
        try {
            Cas cas = JkStores.storeCas();
            PomImporter importer =
                    new PomImporter(RepoGroupBuilder.buildForImport(cas), cas).frontends(FrontendCollector.inPlace());
            List<PomShadow.Shadow> shadows = PomReactorScan.declaresModules(pom)
                    ? PomShadow.renderReactor(importer, pom)
                    : List.of(PomShadow.render(importer, pom));
            for (PomShadow.Shadow rendered : shadows) {
                Path target = ManifestPaths.shadowManifestPath(rendered.moduleDir());
                Files.createDirectories(Objects.requireNonNull(target.getParent(), "shadow dir"));
                AtomicWrites.replace(target, rendered.toml());
                PENDING_TIER3.put(rendered.moduleDir(), rendered.tier3());
                if (!rendered.moduleDir().equals(module)) ROOT_OF.put(rendered.moduleDir(), module);
                Log.debug("shadow manifest rendered", target);
            }
            PENDING_NOTES.put(module, raisePins(module));
        } catch (IOException e) {
            FAILURES.remember(module, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Raise the shadow's pins as {@code jk import} raises a manifest's: a pin below what its
     * dependencies need is read as a floor and written as the version highest-wins resolves, so
     * the in-place build locks what Maven's POM asks for under jk's rule. A probe that cannot
     * resolve (offline, a repository down) leaves the pins as written and says so; the lock then
     * reports any pin it cannot hold.
     */
    private static List<String> raisePins(Path module) {
        try {
            return pinRaises.apply(module);
        } catch (Exception e) {
            Log.debug("shadow pin raises skipped", e);
            return List.of("the POM's pins were not checked against their dependencies' floors ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
        }
    }

    /**
     * The Tier-3 rows of the most recent rendering of {@code dir}'s shadow that no build has
     * reported yet — and, for a leaf, its reactor's own rows — each with the {@code jk import}
     * remedy; empty after the first call.
     */
    public static List<String> drainTier3(Path dir) {
        Path module = dir.toAbsolutePath().normalize();
        List<String> rows = new ArrayList<>();
        Path root = ROOT_OF.get(module);
        if (root != null) rows.addAll(Objects.requireNonNullElse(PENDING_TIER3.remove(root), List.of()));
        rows.addAll(Objects.requireNonNullElse(PENDING_TIER3.remove(module), List.of()));
        List<String> out = new ArrayList<>();
        for (String row : rows) {
            out.add(row + " — not carried by the in-place build of " + ManifestPaths.POM + "; `jk import "
                    + ManifestPaths.POM + "` writes a jk.toml to edit");
        }
        if (root != null) out.addAll(Objects.requireNonNullElse(PENDING_NOTES.remove(root), List.of()));
        out.addAll(Objects.requireNonNullElse(PENDING_NOTES.remove(module), List.of()));
        return List.copyOf(out);
    }
}
