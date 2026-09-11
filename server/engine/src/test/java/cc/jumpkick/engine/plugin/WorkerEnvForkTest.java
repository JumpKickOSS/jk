// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.EnvConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a forked worker actually sees: {@link EchoPluginMain} reports its own {@code System.getenv}
 * back over the protocol, with a secret planted in "the engine's" environment through the {@link
 * WorkerEnv} seam rather than exported for real.
 */
@Tag("integration")
class WorkerEnvForkTest {

    private static final String SECRET = "FAKE_SECRET";

    @Test
    void a_secret_in_the_engine_s_environment_does_not_reach_a_worker() throws Exception {
        assertThat(seenBy(WorkerEnv.strict())).isEqualTo("<unset>");
    }

    @Test
    void a_module_that_opted_into_inheritance_gets_it() throws Exception {
        assertThat(seenBy(WorkerEnv.policy(new EnvConfig(true, List.of())))).isEqualTo("x");
    }

    @Test
    void a_declared_variable_arrives_without_inheriting_anything_else() throws Exception {
        assertThat(seenBy(WorkerEnv.strict().with(Map.of(SECRET, "declared")))).isEqualTo("declared");
    }

    /** The engine's environment with the secret planted, as the worker started under {@code env} reports it. */
    private static String seenBy(WorkerEnv env) throws Exception {
        Map<String, String> engine = new LinkedHashMap<>(System.getenv());
        engine.put(SECRET, "x");
        AtomicReference<@Nullable String> seen = new AtomicReference<>();
        int exit = WorkerEnv.withEngineEnvironment(
                engine,
                () -> PluginProcess.run(
                        cmd("env", SECRET), env, "##T:", json -> seen.set(Jsonl.str(json, SECRET)), null));
        assertThat(exit).isZero();
        return Objects.requireNonNull(seen.get(), "the worker reported no env event");
    }

    private static List<String> cmd(String... args) {
        List<String> c = new ArrayList<>(List.of(
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                EchoPluginMain.class.getName()));
        c.addAll(List.of(args));
        return c;
    }
}
