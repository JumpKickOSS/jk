// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.WorkspaceModelResolver;
import org.jspecify.annotations.Nullable;

/**
 * One Maven reactor for Maven's model builder: the root pom.xml and every module pom.xml, keyed
 * by coordinates. Maven asks here before {@code <relativePath>} or any repository, for a parent
 * (the raw model) and for an {@code import}-scope BOM (the effective model), so a sibling is never
 * fetched from the network, whether it is a parent, a BOM, or both.
 *
 * <p>A lookup matches the coordinates as written or with their placeholders filled: the version's
 * CI-friendly ones ({@code ${revision}} and friends) from the POM chain's properties, and {@code
 * ${project.groupId}}, {@code ${project.version}}, {@code ${project.parent.groupId}} and {@code
 * ${project.parent.version}} from the POM's own coordinates and {@code <parent>} block, because
 * Maven asks for a parent with the raw version and for a BOM with the interpolated one.
 *
 * <p>An effective model is memoised from its build until {@link #release} drops it, which the
 * reactor walk does once a module's manifest is rendered: a parent is answered from the raw model,
 * so only the BOMs other modules import need to stay built. The memo, not the POM tree, is what
 * bounds the import's heap on a reactor of hundreds of modules.
 *
 * <p>The modules of one aggregator are built side by side ({@link #effectiveAll}), {@link
 * DeclaredPins#walksInFlight()} at a time, so the parent and BOM POMs a repository serves are read
 * several at once and Maven's model building runs on several cores. The memo is shared between
 * those builds; a model another thread is building at the moment it is asked for is built again
 * privately rather than waited on, so two modules importing each other's BOMs cannot deadlock.
 */
final class ReactorModelResolver implements WorkspaceModelResolver {

    /** One pom.xml of the reactor: its own declarations and where it lives; the bytes are read again on build. */
    private record Entry(Path pomFile, Model raw) {}

    /** Bounds the climb through parents when a coordinate or property is spelled by another placeholder. */
    private static final int MAX_DEPTH = 32;

    private final ModelResolver repositories;
    private final List<String> activeProfiles;
    private final Map<String, Entry> byRawGav = new LinkedHashMap<>();
    private final Map<Path, Entry> byFile = new HashMap<>();
    private final Map<Path, CompletableFuture<EffectiveModel>> effective = new ConcurrentHashMap<>();

    /** The POMs whose build is on this thread's stack, for the cycle check. */
    private final ThreadLocal<Set<Path>> building = ThreadLocal.withInitial(HashSet::new);

    /**
     * {@code repositories} answers what the reactor does not: published parents and BOMs; {@code
     * activeProfiles} are the ids every model of the reactor is built with, as under {@code -P}.
     */
    ReactorModelResolver(ModelResolver repositories, List<String> activeProfiles) {
        this.repositories = repositories;
        this.activeProfiles = List.copyOf(activeProfiles);
    }

    /** Register one pom.xml of the reactor; {@code raw} is read, never written, from here on. */
    void add(Path pomFile, Model raw) {
        Entry entry = new Entry(pomFile.toAbsolutePath(), raw);
        byRawGav.put(rawGav(raw), entry);
        byFile.put(entry.pomFile(), entry);
    }

    boolean contains(String gav) {
        return find(gav) != null;
    }

    /**
     * The effective model of a registered pom.xml, built once. Its parents and BOM imports resolve
     * through this reactor first, then {@code repositories}.
     */
    EffectiveModel effective(Path pomFile) {
        Entry entry =
                Objects.requireNonNull(byFile.get(pomFile.toAbsolutePath()), () -> pomFile + " is not in the reactor");
        EffectiveModel built = build(entry);
        if (built == null) {
            throw new IllegalStateException(pomFile + " is being built already: the reactor's imports form a cycle");
        }
        return built;
    }

    /**
     * The effective models of several registered pom.xml files, keyed in the order given, built
     * side by side on the io pool under the caller's session, {@link DeclaredPins#walksInFlight()}
     * at a time. A build that fails is rethrown here as it would be from {@link #effective}; an
     * interrupt of the caller cancels the builds still running and surfaces as {@link
     * RepoModelResolver.ReadInterrupted}.
     */
    Map<Path, EffectiveModel> effectiveAll(List<Path> pomFiles) {
        Map<Path, EffectiveModel> out = new LinkedHashMap<>();
        if (pomFiles.size() < 2) {
            for (Path pomFile : pomFiles) out.put(pomFile, effective(pomFile));
            return out;
        }
        var session = SessionContext.current();
        Semaphore permits = new Semaphore(DeclaredPins.walksInFlight());
        List<Future<EffectiveModel>> pending = new ArrayList<>(pomFiles.size());
        for (Path pomFile : pomFiles) {
            pending.add(JkThreads.io()
                    .submit(() -> SessionContext.where(session, () -> {
                        permits.acquire();
                        try {
                            return effective(pomFile);
                        } finally {
                            permits.release();
                        }
                    })));
        }
        for (int i = 0; i < pomFiles.size(); i++) {
            try {
                out.put(pomFiles.get(i), pending.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                for (Future<EffectiveModel> f : pending) f.cancel(true);
                throw new RepoModelResolver.ReadInterrupted("interrupted while building " + pomFiles.get(i));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(pomFiles.get(i) + ": " + cause.getMessage(), cause);
            }
        }
        return out;
    }

    /**
     * Drop a memoised effective model; a later lookup builds it again. Called once the importer is
     * done with a module, so the memo holds only what other modules still ask for.
     */
    void release(Path pomFile) {
        effective.remove(pomFile.toAbsolutePath());
    }

    /** The pom.xml files whose effective models the memo holds right now. */
    Set<Path> retained() {
        Set<Path> held = new HashSet<>();
        effective.forEach((pomFile, built) -> {
            if (built.isDone() && !built.isCompletedExceptionally()) held.add(pomFile);
        });
        return Set.copyOf(held);
    }

    /**
     * {@code null} while the entry's own build is in progress higher up this thread's stack (a
     * cycle). A model another thread is building right now is built here again and not memoised.
     */
    private @Nullable EffectiveModel build(Entry entry) {
        Path pomFile = entry.pomFile();
        Set<Path> onStack = building.get();
        if (onStack.contains(pomFile)) return null;
        CompletableFuture<EffectiveModel> mine = new CompletableFuture<>();
        CompletableFuture<EffectiveModel> memo = effective.putIfAbsent(pomFile, mine);
        if (memo != null && memo.isDone() && !memo.isCompletedExceptionally()) return memo.join();
        onStack.add(pomFile);
        try {
            EffectiveModel built =
                    EffectiveModel.build(read(pomFile), pomFile, repositories.newCopy(), this, activeProfiles);
            if (memo == null) mine.complete(built);
            return built;
        } catch (RuntimeException | Error e) {
            if (memo == null) {
                effective.remove(pomFile, mine);
                mine.completeExceptionally(e);
            }
            throw e;
        } finally {
            onStack.remove(pomFile);
        }
    }

    private static byte[] read(Path pomFile) {
        try {
            return Files.readAllBytes(pomFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A parent's raw model. Maven checks the candidate's {@code groupId} and {@code version} against
     * the child's {@code <parent>} block as written, so a coordinate this POM spells differently
     * from the child — {@code ${project.parent.groupId}} against a literal — is handed back as the
     * child wrote it; a spelling both share ({@code ${revision}}) stays raw for Maven to fill.
     */
    @Override
    public @Nullable Model resolveRawModel(String groupId, String artifactId, String versionConstraint) {
        Entry entry = find(groupId + ":" + artifactId + ":" + versionConstraint);
        if (entry == null) return null;
        Model model = entry.raw().clone();
        model.setPomFile(entry.pomFile().toFile());
        if (differsByPlaceholder(model.getGroupId(), groupId)) model.setGroupId(groupId);
        if (differsByPlaceholder(model.getVersion(), versionConstraint)) model.setVersion(versionConstraint);
        return model;
    }

    private static boolean differsByPlaceholder(@Nullable String own, String asked) {
        return own != null && CiFriendlyVersions.hasPlaceholder(own) && !own.equals(asked);
    }

    /** A sibling BOM: Maven takes its {@code dependencyManagement} from here instead of a repository. */
    @Override
    public @Nullable Model resolveEffectiveModel(String groupId, String artifactId, String versionConstraint) {
        Entry entry = find(groupId + ":" + artifactId + ":" + versionConstraint);
        if (entry == null) return null;
        EffectiveModel built = build(entry);
        return built == null || built.failure() != null ? null : built.model().clone();
    }

    /** The entry whose coordinates match {@code gav} as written, else with its placeholders filled. */
    private @Nullable Entry find(String gav) {
        return find(gav, 0);
    }

    /** {@code depth} bounds the climb through parents whose own coordinates need filling. */
    private @Nullable Entry find(String gav, int depth) {
        Entry hit = byRawGav.get(gav);
        if (hit != null) return hit;
        if (depth > MAX_DEPTH) return null;
        String[] parts = gav.split(":", 3);
        if (parts.length < 3) return null;
        for (Entry entry : byRawGav.values()) {
            Model raw = entry.raw();
            if (!parts[1].equals(raw.getArtifactId())) continue;
            if (!parts[0].equals(interpolated(groupOf(raw), raw, depth))) continue;
            if (parts[2].equals(interpolated(versionOf(raw), raw, depth))) return entry;
        }
        return null;
    }

    /** The version as Maven would spell it: placeholders filled from this POM and its reactor parents. */
    String interpolatedVersion(Model raw) {
        return interpolated(versionOf(raw), raw, 0);
    }

    private String interpolated(@Nullable String text, Model raw, int depth) {
        return text == null ? "" : CiFriendlyVersions.interpolate(text, name -> expression(raw, name, depth + 1));
    }

    /**
     * The value of one {@code ${...}} name in {@code raw}'s scope: the POM's own coordinates and
     * {@code <parent>} block for the {@code project.*} names, else a property of the POM or of a
     * reactor parent. {@code null} when nothing in the reactor defines it.
     */
    private @Nullable String expression(Model raw, String name, int depth) {
        if (depth > MAX_DEPTH) return null;
        Parent parent = raw.getParent();
        String parentGroup = parent == null ? null : parent.getGroupId();
        String parentVersion = parent == null ? null : parent.getVersion();
        return switch (name) {
            case "project.groupId", "pom.groupId" -> ownOrParent(raw.getGroupId(), parentGroup, name, raw, depth);
            case "project.version", "pom.version" -> ownOrParent(raw.getVersion(), parentVersion, name, raw, depth);
            case "project.parent.groupId" -> parentGroup == null ? null : interpolated(parentGroup, raw, depth);
            case "project.parent.version" -> parentVersion == null ? null : interpolated(parentVersion, raw, depth);
            default -> property(raw, name, depth);
        };
    }

    /**
     * A coordinate the POM writes itself, unless that spelling refers back to {@code name} (Maven
     * then reads the parent's value), else the one inherited from {@code <parent>}.
     */
    private @Nullable String ownOrParent(
            @Nullable String own, @Nullable String inherited, String name, Model raw, int depth) {
        String text = own != null && !own.contains("${" + name + "}") ? own : inherited;
        return text == null ? null : interpolated(text, raw, depth);
    }

    private @Nullable String property(Model raw, String name, int depth) {
        String own = raw.getProperties().getProperty(name);
        if (own != null) return own;
        Parent parent = raw.getParent();
        if (parent == null) return null;
        Entry above = find(parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion(), depth + 1);
        return above == null || above.raw() == raw ? null : property(above.raw(), name, depth + 1);
    }

    /** {@code g:a:v} with {@code <parent>} filling a missing groupId or version, as Maven does. */
    static String rawGav(Model raw) {
        return groupOf(raw) + ":" + raw.getArtifactId() + ":" + versionOf(raw);
    }

    private static @Nullable String groupOf(Model raw) {
        Parent parent = raw.getParent();
        return raw.getGroupId() != null ? raw.getGroupId() : parent != null ? parent.getGroupId() : null;
    }

    private static String versionOf(Model raw) {
        Parent parent = raw.getParent();
        String version = raw.getVersion() != null ? raw.getVersion() : parent != null ? parent.getVersion() : null;
        return version == null ? "" : version;
    }
}
