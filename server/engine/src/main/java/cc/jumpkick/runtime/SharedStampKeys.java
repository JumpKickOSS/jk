// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.ModuleEnvKeys;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.KeyAudit;
import cc.jumpkick.task.KeySeed;
import cc.jumpkick.task.RemoteCaches;
import cc.jumpkick.task.RemoteTier;
import cc.jumpkick.test.SuiteEnvReads;
import cc.jumpkick.test.SuiteReads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The shared form of a run-tests stamp whose suite read a value of the caller's environment.
 * The stamp this host takes keys each such value under this jk home's secret, read
 * undeclared ({@link SuiteEnvReads#tokens}) or declared and forwarded ({@link #declaredTokens}), so
 * it matches here alone and carries {@link SuiteEnvReads#HOST_TOKEN}, which keeps its result here.
 * With a remote tier whose seed is in force ({@link RemoteTier#keySeed}) the same stamp is taken
 * again with those values under the seed and no host mark, and the tier asks and stores the result,
 * and its class ledger, by that form ({@link RemoteTier#shareAs}). The local stamp is the one every
 * reader here names (the local cache, history, {@code jk explain}), so a seed that arrives, changes
 * or goes reruns nothing on this host.
 */
final class SharedStampKeys {

    private SharedStampKeys() {}

    /** The stamp over other extras: the very derivation the local stamp took. */
    @FunctionalInterface
    interface Rekey {
        @Nullable
        String key(List<String> extras) throws Exception;
    }

    /** The prefix of the tokens {@link ModuleEnvKeys#testJvm} makes for a declared variable. */
    private static final String DECLARED = "test-env:";

    /**
     * The {@code test-env:} tokens of {@code project}'s declared variables, each value the caller's
     * environment answered keyed by {@link SuiteEnvReads#declaredValue} under {@code seed} (the key's
     * shared form) or, with none, this home's secret, and then {@link SuiteEnvReads#HOST_TOKEN}.
     */
    static List<String> declaredTokens(JkBuild project, Path moduleDir, @Nullable KeySeed seed) {
        boolean[] answered = {false};
        List<String> out = new ArrayList<>(ModuleEnvKeys.testJvm(project, moduleDir, value -> {
            answered[0] = true;
            return SuiteEnvReads.declaredValue(value, seed);
        }));
        if (answered[0] && seed == null) out.add(SuiteEnvReads.HOST_TOKEN);
        return out;
    }

    /**
     * Note the shared form of {@code local}, the stamp taken over {@code extras} for the suites of
     * {@code moduleDir} under {@code selection}, with the build's remote tier, which holds a key noted
     * with no shared form on this host, ledger and all: nothing when no tier is bound, the stamp keys
     * on no secret (no {@link SuiteEnvReads#HOST_TOKEN}: both forms are one),
     * no seed is in force, or the result stays on this host even so (a run with no caller, or a
     * credential's value while the user keeps those here). Heard by no tap: the shared form names
     * nothing a forecast or a miss compares.
     */
    static void note(
            String local,
            List<String> extras,
            JkBuild project,
            Path moduleDir,
            TestSelection selection,
            SuiteEnvReads.Policy policy,
            Rekey rekey) {
        RemoteTier tier = RemoteCaches.current();
        if (tier == null || tier.noted(local) || !extras.contains(SuiteEnvReads.HOST_TOKEN)) return;
        // Noted as itself until a shared form is known: the tier uploads neither the result nor its
        // ledger under a key no other host takes, and asked again, it costs a map read.
        tier.shareAs(local, local);
        tier.shareAs(TestClassLedger.key(local), TestClassLedger.key(local));
        KeySeed seed = tier.keySeed();
        if (seed == null) return;
        try {
            String key = KeyAudit.untapped(() -> {
                List<String> env = SuiteEnvReads.sharedTokens(
                        SuiteReads.load(moduleDir, selection).env(),
                        policy,
                        CallerEnv.current(),
                        seed,
                        tier.credentialsLocal());
                return env == null ? null : rekey.key(shared(extras, env, declaredTokens(project, moduleDir, seed)));
            });
            if (key == null || key.equals(local)) return;
            tier.shareAs(local, key);
            tier.shareAs(TestClassLedger.key(local), TestClassLedger.key(key));
        } catch (Exception unkeyed) {
            // No shared form: the result stays on this host, as it would with no seed.
            Log.debug("suite key: no shared form", unkeyed);
        }
    }

    /**
     * {@code extras} with its environment tokens in their shared form: the read tokens ({@link
     * SuiteEnvReads#isToken}, the host mark among them) replaced by {@code env} where the first stood,
     * and the declared tokens by {@code declared} where the first of those stood.
     */
    static List<String> shared(List<String> extras, List<String> env, List<String> declared) {
        List<String> out = new ArrayList<>();
        boolean readsPlaced = false;
        boolean declaredPlaced = false;
        for (String token : extras) {
            if (SuiteEnvReads.isToken(token)) {
                if (!readsPlaced) out.addAll(env);
                readsPlaced = true;
            } else if (token.startsWith(DECLARED)) {
                if (!declaredPlaced) out.addAll(declared);
                declaredPlaced = true;
            } else out.add(token);
        }
        if (!readsPlaced) out.addAll(env);
        if (!declaredPlaced) out.addAll(declared);
        return out;
    }
}
