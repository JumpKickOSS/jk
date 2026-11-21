// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An install pass that replaced the engine it ran under: telling that it did, handing the
 * re-shelving pass to the new engine only once that engine is the one answering, and what is left
 * for the user when the handoff cannot finish here.
 */
public final class EngineHandoff {

    private EngineHandoff() {}

    /**
     * What the last pass leaves unsaid when it too replaced the engine: the passes are bounded at
     * two, so a shelf packaged by an engine the home no longer names is the user's to finish. Null
     * when the home names the engine that ran the final pass — the shelf is that engine's.
     */
    public static @Nullable String shelfBehindNotice(
            boolean lastPass, Optional<String> before, Optional<String> after) {
        if (!lastPass || !replaced(before, after)) return null;
        return "workers were shelved by engine " + shortSha(before) + ", not the new engine " + shortSha(after)
                + "; run `jk install` once more";
    }

    /**
     * What a pass that installed an engine of another version than {@code clientVersion} leaves
     * to the tree's own client: the home names that engine now, an engine serves only clients of
     * its own version, so the re-shelving pass runs when {@code client} (the PATH client the pass
     * installed, when it did) is the one asking. Null when the versions agree — this client is
     * served by the new engine and runs the pass itself — or when the home names no engine.
     */
    public static @Nullable String handoverNotice(
            String clientVersion, Optional<String> engineVersion, @Nullable Path client, String verb) {
        if (engineVersion.isEmpty() || engineVersion.get().equals(clientVersion)) return null;
        String tree = engineVersion.get();
        String next = client != null ? "`" + client + " " + verb + "`" : "`jk " + verb + "` as jk " + tree;
        return "installed engine is jk " + tree + " and this one is jk " + clientVersion + "; run " + next
                + " once more";
    }

    /**
     * Forget the engine this process ensured, bring up the one the next request will be served
     * by, and check it is the one {@code pointer} names. {@link Exit#SUCCESS} when the re-shelving
     * pass may run; otherwise the failure is printed and its exit code returned.
     */
    static int refusal(Optional<String> pointer) {
        EngineClient.forgetEnsuredEngine();
        String refused;
        try {
            EngineProbe.Handshake live = EngineClient.ensureRunning(EnginePaths.current(), JkVersion.VERSION);
            refused = mismatch(live.buildId(), pointer);
        } catch (IOException e) {
            refused = "re-shelving pass: " + e.getMessage();
        }
        if (refused == null) return Exit.SUCCESS;
        CommandWedge.printFail("Install", refused);
        return Exit.SOFTWARE;
    }

    /**
     * Why the re-shelving pass must not run: the engine answering the endpoint ({@code
     * liveBuildId}, a prefix of its jar's digest) is not the one the home's pointer names. Null
     * when they agree, or when either side has no identity to compare (an engine run from a
     * classes directory, a home with no pointer).
     */
    public static @Nullable String mismatch(String liveBuildId, Optional<String> pointerSha) {
        if (liveBuildId.isEmpty() || pointerSha.isEmpty()) return null;
        if (pointerSha.get().toLowerCase(Locale.ROOT).startsWith(liveBuildId.toLowerCase(Locale.ROOT))) return null;
        return "engine " + liveBuildId + " answered, not the installed engine " + shortSha(pointerSha)
                + "; run `jk engine stop`, then `jk install` again";
    }

    private static String shortSha(Optional<String> sha) {
        return sha.map(s -> s.length() > 12 ? s.substring(0, 12) : s).orElse("(none)");
    }

    /**
     * The engine jar the product library's pointer names, by digest; empty when the home has none,
     * or names one without a sha256 (a jar inferred from the engine home).
     */
    static Optional<String> liveSha() {
        EngineInstall install = EngineInstall.current();
        return install.currentInstall().flatMap(m -> install.engineSha(m.version()));
    }

    /** The product version of the engine the pointer names; empty when the home has none. */
    static Optional<String> liveVersion() {
        return EngineInstall.current().currentInstall().map(EngineInstall.Materialized::version);
    }

    /**
     * Whether an install pass left the home naming another engine than the one it started under.
     * Every artifact-shaped action key names the engine that packaged the artifact, so a shelf the
     * displaced engine filled is one the new engine packages afresh: one more pass under it brings
     * the shelf to the tree. A home that named no engine before is treated the same way — whatever
     * ran the pass is not the engine the home names now.
     */
    public static boolean replaced(Optional<String> before, Optional<String> after) {
        if (after.isEmpty()) return false;
        return before.isEmpty() || !before.get().equalsIgnoreCase(after.get());
    }
}
