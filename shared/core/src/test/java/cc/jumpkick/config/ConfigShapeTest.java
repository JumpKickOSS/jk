// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.IoLedger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The shape of jk's config records: {@code Optional} is a return type, and single-field copies are
 * generated rather than written out.
 *
 * <p>The second half is the one that used to be wrong in a way no unit test could see. {@link
 * Session} and {@link JkConfig} carried 26 hand-written copy methods between them, each restating a
 * 12- or 14-argument positional constructor whose adjacent components are the same type — three
 * adjacent {@code Path}s on {@code Session}, ten adjacent {@code Boolean}s on {@code JkConfig}. A
 * transposition in any one of them compiles, and shows up as one setting quietly taking another's
 * value. So the test is not "does {@code withQuiet} set quiet"; it is <em>"does every wither leave
 * every other component alone"</em>, checked reflectively so a wither added later is covered the day
 * it lands.
 */
class ConfigShapeTest {

    /** Every config-shape record this ticket owns, including the nested ones. */
    private static List<Class<?>> configShapes() {
        return List.of(
                JkConfig.class,
                Session.class,
                JkEngineConfig.class,
                JkM2Config.class,
                JkCacheConfig.class,
                JkCacheConfig.DiskSpace.class,
                JkHttpConfig.class,
                JkHttpConfig.Mcp.class,
                JkHistoryConfig.class,
                JkTemplatesConfig.class,
                JkTemplatesConfig.Source.class,
                MachineConfig.class,
                StampedMemo.FileStamp.class);
    }

    private static final List<Class<?>> OPTIONALS =
            List.of(Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class);

    /**
     * A record component is a field plus an accessor. The accessor may return {@code Optional}; the
     * component may not, because it is also the field and the constructor parameter.
     */
    @Test
    void no_config_shape_carries_an_Optional_component_field_or_parameter() {
        int componentsChecked = 0;
        int membersChecked = 0;
        for (Class<?> type : configShapes()) {
            RecordComponent[] components = type.getRecordComponents();
            assertThat(components).describedAs("%s is a record", type).isNotNull();
            for (RecordComponent rc : components) {
                assertThat(rc.getType())
                        .describedAs("component %s.%s", type.getSimpleName(), rc.getName())
                        .isNotIn(OPTIONALS);
                componentsChecked++;
            }
            for (Field f : type.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                assertThat(f.getType())
                        .describedAs("field %s.%s", type.getSimpleName(), f.getName())
                        .isNotIn(OPTIONALS);
                membersChecked++;
            }
            for (Method m : type.getDeclaredMethods()) {
                if (m.isSynthetic()) continue;
                for (Parameter p : m.getParameters()) {
                    assertThat(p.getType())
                            .describedAs("parameter %s.%s(%s)", type.getSimpleName(), m.getName(), p.getName())
                            .isNotIn(OPTIONALS);
                    membersChecked++;
                }
            }
        }
        // The scan must have seen something, or an empty list would satisfy every assertion above.
        assertThat(configShapes()).hasSize(13);
        assertThat(componentsChecked).isGreaterThanOrEqualTo(45);
        assertThat(membersChecked).isPositive();
    }

    /** {@code Optional}-returning methods are fine and there are some — the rule is about inputs. */
    @Test
    void an_Optional_return_type_is_still_allowed() {
        assertThat(JkConfig.ColorChoice.parse("never")).contains(JkConfig.ColorChoice.NEVER);
        assertThat(JkHttpConfig.fromToml(Path.of("/__jk_no_config__"))).contains(JkHttpConfig.DEFAULTS);
    }

    // ---- withers -----------------------------------------------------------

    /** A wither call: the method to invoke, its argument, and the components it is allowed to move. */
    private record Wither(String method, Object[] args, List<String> changes) {}

    private static Session populatedSession() {
        return Session.defaults()
                .withConfig(JkConfig.empty().withQuiet(true))
                .withWorkingDir(Path.of("/tmp/jk-wd-a"))
                .withCacheDir(Path.of("/tmp/jk-cache-a"))
                .withJdksDir(Path.of("/tmp/jk-jdks-a"))
                .withJvm(PluginTuning.NONE)
                .withToolchainSpecs("21", "graal-21", Path.of("/opt/graal-21"))
                .withParallelTests(false)
                .withCancel(Session.CancelToken.live())
                .withVariant("alpha", Map.of("A", "1"))
                .withAssemblyOverride("fat")
                .withTestSelection(TestSelection.DEFAULT)
                .withAffected(true)
                .withIo(new IoLedger());
    }

    private static List<Wither> sessionWithers() {
        return List.of(
                new Wither("withConfig", new Object[] {JkConfig.empty().withVerbose(true)}, List.of("config")),
                new Wither("withWorkingDir", new Object[] {Path.of("/tmp/jk-wd-b")}, List.of("workingDir")),
                new Wither("withCacheDir", new Object[] {Path.of("/tmp/jk-cache-b")}, List.of("cacheDir")),
                new Wither("withJdksDir", new Object[] {Path.of("/tmp/jk-jdks-b")}, List.of("jdksDir")),
                new Wither(
                        "withJvm",
                        new Object[] {new PluginTuning(null, "G1", null, List.of("-Xmx1g"))},
                        List.of("jvm")),
                new Wither("withParallelTests", new Object[] {true}, List.of("parallelTests")),
                new Wither("withCancel", new Object[] {Session.CancelToken.NONE}, List.of("cancel")),
                new Wither("withAssemblyOverride", new Object[] {"minified"}, List.of("assemblyOverride")),
                new Wither(
                        "withTestSelection",
                        new Object[] {TestSelection.DEFAULT.withIncludeTags(List.of("integration"))},
                        List.of("testSelection")),
                new Wither("withAffected", new Object[] {false}, List.of("affected")),
                new Wither("withIo", new Object[] {new IoLedger()}, List.of("io")),
                // Two components, one fact — the pairs that are only meaningful together.
                new Wither("withVariant", new Object[] {"beta", Map.of("B", "2")}, List.of("variant", "clientEnv")),
                new Wither(
                        "withToolchainSpecs",
                        new Object[] {"25", "graal-25", Path.of("/opt/graal-25")},
                        List.of("jdkSpec", "graalSpec", "graalHome")));
    }

    @Test
    void every_session_wither_moves_exactly_its_own_components() throws Exception {
        assertOnlyTheseMove(Session.class, populatedSession(), sessionWithers());
    }

    private static JkConfig populatedConfig() {
        return JkConfig.empty()
                .withColor(JkConfig.ColorChoice.NEVER)
                .withOffline(false)
                .withRebuild(false)
                .withNoProgress(false)
                .withQuiet(false)
                .withVerbose(false)
                .withDirectory(Path.of("/tmp/jk-dir-a"))
                .withForce(false)
                .withNoAnsi(false)
                .withForceAnsi(false)
                .withNoOsc(false)
                .withNotifyPolicy(JkConfig.NotifyChoice.NEVER)
                .withBuildOutput(false);
    }

    private static List<Wither> configWithers() {
        return List.of(
                new Wither("withColor", new Object[] {JkConfig.ColorChoice.ALWAYS}, List.of("color")),
                new Wither("withOffline", new Object[] {true}, List.of("offline")),
                new Wither("withRebuild", new Object[] {true}, List.of("rebuild")),
                new Wither("withNoProgress", new Object[] {true}, List.of("noProgress")),
                new Wither("withQuiet", new Object[] {true}, List.of("quiet")),
                new Wither("withVerbose", new Object[] {true}, List.of("verbose")),
                new Wither("withDirectory", new Object[] {Path.of("/tmp/jk-dir-b")}, List.of("directory")),
                new Wither("withForce", new Object[] {true}, List.of("force")),
                new Wither("withNoAnsi", new Object[] {true}, List.of("noAnsi")),
                new Wither("withForceAnsi", new Object[] {true}, List.of("forceAnsi")),
                new Wither("withNoOsc", new Object[] {true}, List.of("noOsc")),
                new Wither("withNotifyPolicy", new Object[] {JkConfig.NotifyChoice.ALWAYS}, List.of("notifyPolicy")),
                new Wither("withBuildOutput", new Object[] {true}, List.of("buildOutput")));
    }

    @Test
    void every_config_wither_moves_exactly_its_own_component() throws Exception {
        assertOnlyTheseMove(JkConfig.class, populatedConfig(), configWithers());
    }

    /**
     * Every wither the record declares must be in the table — otherwise a hand-written one added
     * later, with a transposition in it, is simply not tested.
     */
    @Test
    void the_tables_name_every_wither_the_records_declare() {
        assertThat(declaredWithers(Session.class))
                .containsExactlyInAnyOrderElementsOf(
                        sessionWithers().stream().map(Wither::method).toList());
        assertThat(declaredWithers(JkConfig.class))
                .containsExactlyInAnyOrderElementsOf(
                        configWithers().stream().map(Wither::method).toList());
        // Every component of both records is reachable through exactly one wither.
        assertThat(sessionWithers().stream().flatMap(w -> w.changes().stream()))
                .containsExactlyInAnyOrderElementsOf(componentNames(Session.class));
        assertThat(configWithers().stream().flatMap(w -> w.changes().stream()))
                .containsExactlyInAnyOrderElementsOf(componentNames(JkConfig.class));
    }

    private static List<String> declaredWithers(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (!m.isSynthetic()
                    && Modifier.isPublic(m.getModifiers())
                    && m.getName().startsWith("with")
                    && m.getReturnType() == type) {
                names.add(m.getName());
            }
        }
        return names;
    }

    private static List<String> componentNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (RecordComponent rc : type.getRecordComponents()) {
            names.add(rc.getName());
        }
        return names;
    }

    /**
     * Apply each wither to {@code base} and check, component by component, that exactly the declared
     * ones moved. Reflective on purpose: this is the assertion 26 hand-written copies could not make
     * about themselves.
     */
    private static <T> void assertOnlyTheseMove(Class<T> type, T base, List<Wither> withers) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        for (Wither w : withers) {
            Method target = null;
            for (Method m : type.getDeclaredMethods()) {
                if (m.getName().equals(w.method()) && m.getParameterCount() == w.args().length) {
                    target = m;
                    break;
                }
            }
            assertThat(target)
                    .describedAs("%s.%s exists", type.getSimpleName(), w.method())
                    .isNotNull();
            Object copy = target.invoke(base, w.args());

            for (RecordComponent rc : components) {
                Object before = rc.getAccessor().invoke(base);
                Object after = rc.getAccessor().invoke(copy);
                if (w.changes().contains(rc.getName())) {
                    assertThat(after)
                            .describedAs("%s must move %s", w.method(), rc.getName())
                            .isNotEqualTo(before);
                } else {
                    assertThat(after)
                            .describedAs("%s must not touch %s", w.method(), rc.getName())
                            .isEqualTo(before);
                }
            }
        }
    }

    /** A generated wither runs the canonical constructor, so its normalisation still applies. */
    @Test
    void a_generated_wither_still_normalises_through_the_canonical_constructor() {
        Session s = Session.defaults();
        assertThat(s.withAssemblyOverride("  fat  ").assemblyOverride()).isEqualTo("fat");
        assertThat(s.withAssemblyOverride("   ").assemblyOverride()).isEmpty();
        assertThat(s.withTestSelection(null).testSelection()).isEqualTo(TestSelection.DEFAULT);
        assertThat(s.withCancel(null).cancel()).isSameAs(Session.CancelToken.NONE);
        assertThat(s.withIo(null).io()).isNotNull();
    }

    /** Sanity: the env/file loader still round-trips through the nullable components. */
    @Test
    void loader_layers_still_merge_through_nullable_components() {
        Function<String, String> env = Map.of("JK_QUIET", "true")::get;
        JkConfig fromEnv = JkConfigLoader.loadFromEnv(env);
        assertThat(fromEnv.quiet()).isTrue();
        assertThat(fromEnv.verbose()).isNull();
        assertThat(JkConfig.empty().mergedWith(fromEnv).quietOr(false)).isTrue();
    }
}
