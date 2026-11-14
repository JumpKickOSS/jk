// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.EnvRead;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every environment value a module's build reads, as key tokens: the {@code [env]} table and
 * {@code inherit}, {@code [test] env}, and a plugin table's {@code env:NAME} values, each read
 * {@link EnvRead#of} lists. The preflight memo, every step key and the forecast take their
 * environment tokens here and nowhere else, and {@link #reaches} matches every kind of read with
 * no default arm, so a new way of reading the environment is keyed by all three or does not
 * compile.
 *
 * <p>Values enter resolved the way the build resolves them (the request's shell, the module's
 * {@code .env}, then the engine's own) and hashed: a key may be read on another machine.
 */
public final class ModuleEnvKeys {

    private ModuleEnvKeys() {}

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
     * The tokens of what a forked worker of the module reads: one {@code env:<name>=<digest>} per
     * {@code [env]} entry as the fork resolves it (a forwarded name the caller lacks has its own
     * digest, so appearing and disappearing move a key too; an unset {@code ${VAR}} fails here as
     * it fails at launch) and, under {@code [env] inherit}, {@link #inheritedToken}. A module that
     * declares nothing has none.
     */
    public static List<String> worker(JkBuild project, Path moduleDir) {
        return tokens(project, moduleDir, Reader.WORKER);
    }

    /**
     * {@link #worker} plus one {@code plugin-env:<name>=<digest>} per variable a plugin table of
     * {@code project} names as {@code env:NAME}: a plugin step or packager reads the resolved value
     * as its config (a signing keystore, a credential). Over an effective manifest the values the
     * selected variant flattened are already resolved into the config the key hashes, and only the
     * rest remain here.
     */
    public static List<String> plugin(JkBuild project, Path moduleDir) {
        return tokens(project, moduleDir, Reader.PLUGIN);
    }

    /**
     * What the test JVM is handed, as run-tests stamp tokens: {@code test-env:<name>=<value>} for
     * the {@code [env]} entries with {@code [test] env} laid on top, in the stamp's key mode (path
     * tokens literal, anything the environment answered hashed), and {@link #inheritedToken} under
     * {@code [env] inherit}.
     */
    public static List<String> testJvm(JkBuild project, Path moduleDir) {
        return tokens(project, moduleDir, Reader.TEST_JVM);
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
     * The key token of {@code [env] inherit}: the whole inherited environment by digest, less what
     * differs between two terminals of one machine ({@link BuildEnv#inheritedFingerprintEnv}).
     */
    public static String inheritedToken() {
        return "inherited-env:"
                + Hashing.sha256Hex(BuildEnv.inheritedFingerprintEnv().toString());
    }

    private static List<String> tokens(JkBuild project, Path moduleDir, Reader reader) {
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
        var mode = new TestEnvValues.Mode.CacheKey(lookup, SecretRedactor.from(lookup));
        List<String> out = new ArrayList<>();
        if (reader == Reader.TEST_JVM) {
            Map<String, String> resolved =
                    new LinkedHashMap<>(TestEnvValues.resolve("[env]", worker, null, null, mode));
            resolved.putAll(TestEnvValues.resolve("[test].env", test, null, null, mode));
            resolved.forEach((name, value) -> out.add("test-env:" + name + "=" + value));
        } else if (!worker.isEmpty()) {
            TestEnvValues.resolve("[env]", worker, null, null, mode)
                    .forEach((name, value) -> out.add("env:" + name + "=" + Hashing.sha256Hex(value)));
        }
        if (inherit) out.add(inheritedToken());
        for (String name : plugin) out.add("plugin-env:" + name + "=" + digest(lookup.get(name)));
        return List.copyOf(out);
    }

    /** A value's digest, or a marker no digest can equal for a variable the caller lacks. */
    private static String digest(@Nullable String value) {
        return value == null ? "unset" : Hashing.sha256Hex(value);
    }
}
