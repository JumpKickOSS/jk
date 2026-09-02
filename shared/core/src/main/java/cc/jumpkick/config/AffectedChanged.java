// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-invocation, cross-module carrier for {@code --affected} runs: the changed production types
 * each dirty module classified at compile time ({@code FQC → "ABI" | "BODY"}), readable by every
 * dependent module's test ranking. Shared by every copy of one {@link Session} — the same
 * lifetime discipline as {@link cc.jumpkick.task.IoLedger}.
 *
 * <p>Ordering: a dependent's {@code run-tests} runs after its dependencies' compiles (their
 * artifacts gate its compile-test), so every classification a module's tests can name is present
 * before that module ranks. Kinds are plain strings so this can live in shared config without a
 * dependency on the engine's class-file types.
 */
public final class AffectedChanged {

    public static final String KIND_ABI = "ABI";
    public static final String KIND_BODY = "BODY";

    private final ConcurrentHashMap<String, String> kinds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, List<String>> dirtyByRoot = new ConcurrentHashMap<>();

    /** Record one changed production type. ABI wins over BODY when both modules vote. */
    public void put(String fqc, String kind) {
        if (fqc == null || fqc.isBlank() || kind == null) return;
        kinds.merge(fqc, kind, (a, b) -> KIND_ABI.equals(a) || KIND_ABI.equals(b) ? KIND_ABI : KIND_BODY);
    }

    /** Immutable view of everything classified so far. */
    public Map<String, String> snapshot() {
        return Map.copyOf(kinds);
    }

    /**
     * The invocation's WIP path set for {@code root}, computed once and shared — every module of a
     * workspace run asks for the same set, and one {@code git} exec per invocation is the honest
     * cost. {@code null} (git failed) is cached as an empty marker and returned as {@code null}.
     */
    public List<String> dirtyPaths(Path root) {
        Path key = root.toAbsolutePath().normalize();
        List<String> got = dirtyByRoot.computeIfAbsent(key, r -> {
            List<String> wip = DirtyPaths.wip(r);
            return wip == null ? GIT_FAILED : wip;
        });
        return got == GIT_FAILED ? null : got;
    }

    private static final List<String> GIT_FAILED = List.of("\0git-failed");
}
