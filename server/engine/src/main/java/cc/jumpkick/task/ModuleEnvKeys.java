// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.Interpolation;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.EnvRead;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Every environment value a module's build reads, as key tokens: the {@code [env]} table and
 * {@code inherit}, {@code [test] env}, and a plugin table's {@code env:NAME} values, each read
 * {@link EnvRead#of} lists. The preflight memo, every step key and the forecast take their
 * environment tokens here and nowhere else, and {@link #reaches} matches every kind of read with
 * no default arm, so a new way of reading the environment is keyed by all three or does not
 * compile.
 *
 * <p>Values enter resolved the way the build resolves them (the request's shell, the module's
 * {@code .env}, then the engine's own). A value the environment answered is never hashed plainly:
 * a worker's or plugin's is sealed ({@link EnvKeyText}), an HMAC under this home's secret whose
 * shared form is under the cache key seed, and the test JVM's takes the run-tests stamp's own
 * keying ({@link #testJvm}). A key may be read on another machine, and a memo by anyone who can read
 * the project.
 */
public final class ModuleEnvKeys {

    private ModuleEnvKeys() {}

    /** The mark {@link #SEALED_LATER} puts before a value the environment answered. */
    private static final String ENVIRONMENTAL = "\u0000environmental\u0000";

    /**
     * What a worker's or plugin's resolution makes of a value the environment answered: the value
     * behind a mark, sealed once its variable's name is known ({@link #sealed}).
     */
    private static final UnaryOperator<String> SEALED_LATER = value -> ENVIRONMENTAL + value;

    /** Who reads a value: what a token reaches decides which keys carry it. */
    public enum Reader {
        /** A compiler, KSP, jlink or any other forked worker of the module. */
        WORKER,
        /** A plugin step or packager: a worker that also reads its plugin's resolved config. */
        PLUGIN,
        /** The forked test JVM. */
        TEST_JVM
    }

    /** Whether {@code reader} sees what {@code read} reads. */
    public static boolean reaches(EnvRead read, Reader reader) {
        return switch (read) {
            case EnvRead.Worker w -> true;
            case EnvRead.WholeShell s -> true;
            case EnvRead.TestJvm t -> reader == Reader.TEST_JVM;
            case EnvRead.PluginValue p -> reader == Reader.PLUGIN;
        };
    }

    /**
     * The tokens of what a forked worker of the module reads: one {@code env:<name>=<value>} per
     * {@code [env]} entry as the fork resolves it, the value sealed ({@link EnvKeyText}) when the
     * caller's environment answered it (a forwarded name, a {@code ${VAR}} in a value) and by digest
     * when the manifest writes it literally (a forwarded name the caller lacks has a marker of its own,
     * so appearing and disappearing move a key too; an unset {@code ${VAR}} fails here as it fails at
     * launch) and, under {@code [env] inherit}, {@link #inheritedToken}. A module that declares
     * nothing has none.
     */
    public static List<String> worker(JkBuild project, Path moduleDir) {
        return tokens(project, moduleDir, Reader.WORKER, SEALED_LATER);
    }

    /**
     * {@link #worker} plus one {@code plugin-env:<name>=<value>} per variable a plugin table of
     * {@code project} names as {@code env:NAME}, sealed: a plugin step or packager reads the resolved
     * value as its config (a signing keystore, a credential). Over an effective manifest the values
     * the selected variant flattened are already resolved into the config the key hashes, and only
     * the rest remain here.
     */
    public static List<String> plugin(JkBuild project, Path moduleDir) {
        return tokens(project, moduleDir, Reader.PLUGIN, SEALED_LATER);
    }

    /**
     * What the test JVM is handed, as run-tests stamp tokens: {@code test-env:<name>=<digest>} for
     * the {@code [env]} entries with {@code [test] env} laid on top, resolved in the stamp's key mode
     * (path tokens literal, anything the environment answered hashed) and then hashed whole ({@link
     * #testEnvValue}), and {@link #inheritedToken} under {@code [env] inherit}. A value the
     * environment answered (a forwarded name, a {@code ${VAR}} in a value) enters as {@code
     * environmental} makes it: a suite's key takes it by HMAC under a secret, never by plain digest
     * ({@code SuiteEnvReads.declaredValue}).
     */
    public static List<String> testJvm(JkBuild project, Path moduleDir, UnaryOperator<String> environmental) {
        return tokens(project, moduleDir, Reader.TEST_JVM, environmental);
    }

    /**
     * What the preflight memo's row keys of the environment beside the run-tests stamp's extras
     * (which carry {@link #testJvm} when tests run): every value a worker or plugin of the module
     * reads, over the manifest as written, so a plugin's {@code env:NAME} in a group no variant
     * has flattened yet still counts. A row that missed none of them is the only row that may
     * vouch for a module the forecast then never prices.
     */
    public static List<String> module(JkBuild parsed, Path moduleDir) {
        return plugin(parsed, moduleDir);
    }

    /**
     * The key token of {@code [env] inherit}: the whole inherited environment sealed ({@link
     * EnvKeyText}), less what differs between two terminals of one machine ({@link
     * BuildEnv#inheritedFingerprintEnv}); a credential when any name in it reads as one.
     */
    static String inheritedToken(EnvKeyText.Sealer sealer) {
        Map<String, String> env = BuildEnv.inheritedFingerprintEnv();
        boolean credential = env.keySet().stream().anyMatch(EnvKeyText::credential);
        return "inherited-env:" + sealer.seal(env.toString(), credential);
    }

    private static List<String> tokens(
            JkBuild project, Path moduleDir, Reader reader, UnaryOperator<String> environmental) {
        List<EnvRead> reads = EnvRead.of(project);
        if (reads.isEmpty()) return List.of();
        List<EnvDecl> worker = new ArrayList<>();
        List<EnvDecl> test = new ArrayList<>();
        List<String> plugin = new ArrayList<>();
        boolean inherit = false;
        for (EnvRead read : reads) {
            if (!reaches(read, reader)) continue;
            switch (read) {
                case EnvRead.Worker w -> worker.add(w.decl());
                case EnvRead.WholeShell s -> inherit = true;
                case EnvRead.TestJvm t -> test.add(t.decl());
                case EnvRead.PluginValue p -> plugin.add(p.name());
            }
        }
        if (worker.isEmpty() && test.isEmpty() && plugin.isEmpty() && !inherit) return List.of();
        EnvLookup lookup = BuildEnv.lookupFor(moduleDir);
        // The fork's own expansion in its key mode: ${target}/${module} stay tokens so two
        // checkouts key alike.
        var mode = new TestEnvValues.Mode.CacheKey(lookup, SecretRedactor.from(lookup), environmental);
        EnvKeyText.Sealer sealer = EnvKeyText.sealer();
        List<String> out = new ArrayList<>();
        if (reader == Reader.TEST_JVM) {
            Map<String, String> resolved =
                    new LinkedHashMap<>(TestEnvValues.resolve("[env]", worker, null, null, mode));
            resolved.putAll(TestEnvValues.resolve("[test].env", test, null, null, mode));
            resolved.forEach((name, value) -> out.add("test-env:" + name + "=" + testEnvValue(value)));
        } else if (!worker.isEmpty()) {
            Set<String> credentials = credentials(worker);
            TestEnvValues.resolve("[env]", worker, null, null, mode)
                    .forEach((name, value) ->
                            out.add("env:" + name + "=" + sealed(sealer, value, credentials.contains(name))));
        }
        if (inherit) out.add(inheritedToken(sealer));
        for (String name : plugin) {
            String value = lookup.get(name);
            out.add("plugin-env:" + name + "="
                    + (value == null ? "unset" : sealer.seal(value, EnvKeyText.credential(name))));
        }
        return List.copyOf(out);
    }

    /**
     * What one variable the test JVM is handed contributes to the stamp: the digest of its value as
     * the fork resolves it, literal or forwarded, or {@code unset} for a forwarded name the caller
     * lacks. Never the value itself, and never a control character: the token is a record's {@code
     * INPUT} line, and a record may leave the machine.
     */
    static String testEnvValue(String resolved) {
        return TestEnvValues.isAbsent(resolved) ? "unset" : Hashing.sha256Hex(resolved);
    }

    /**
     * A worker's resolved {@code value} as its token holds it: sealed when the environment answered
     * it ({@link #SEALED_LATER}), else the digest of a constant the manifest writes literally.
     */
    private static String sealed(EnvKeyText.Sealer sealer, String value, boolean credential) {
        if (value.startsWith(ENVIRONMENTAL)) return sealer.seal(value.substring(ENVIRONMENTAL.length()), credential);
        return EnvKeyText.literal(value);
    }

    /**
     * The names among {@code decls} whose value is read through a name that reads as a credential
     * ({@link EnvKeyText#credential}): the forwarded name itself, or any {@code ${VAR}} a value names.
     */
    private static Set<String> credentials(List<EnvDecl> decls) {
        Set<String> out = new HashSet<>();
        for (EnvDecl decl : decls) {
            boolean credential = EnvKeyText.credential(decl.name());
            if (decl instanceof EnvDecl.Set set) {
                for (String ref : Interpolation.references(set.value())) credential |= EnvKeyText.credential(ref);
            }
            if (credential) out.add(decl.name());
        }
        return out;
    }
}
