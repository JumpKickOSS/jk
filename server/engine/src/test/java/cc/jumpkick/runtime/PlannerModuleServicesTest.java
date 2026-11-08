// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.runtime.base.ResourceMirror;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code module-services} step body over a real classes tree: each {@code provides} becomes a
 * services file naming its providers in declaration order, an unchanged tree is no work, a file the
 * resources own is left when it agrees and fails the step when it does not, and a {@code provides}
 * removed from the descriptor removes the file the step wrote but never a resource's.
 */
class PlannerModuleServicesTest {

    private static final String GREETER = "META-INF/services/com.example.Greeter";
    private static final String CLOCK = "META-INF/services/com.example.Clock";

    @TempDir
    Path tmp;

    private Path classes;
    private Path incremental;

    @BeforeEach
    void tree() throws IOException {
        classes = Files.createDirectories(tmp.resolve("target/classes"));
        incremental = tmp.resolve("target/incremental");
    }

    @Test
    void each_provides_is_a_services_file_in_declaration_order_and_a_second_run_is_no_work() throws IOException {
        descriptor(Map.of(
                "com.example.Greeter", new String[] {"com.example.Zed", "com.example.Abe"},
                "com.example.Clock", new String[] {"com.example.SystemClock"}));

        assertThat(run()).isEqualTo(2);
        assertThat(read(GREETER)).isEqualTo("com.example.Zed\ncom.example.Abe\n");
        assertThat(read(CLOCK)).isEqualTo("com.example.SystemClock\n");
        assertThat(PlannerModuleServices.outOfSync(classes, incremental)).isFalse();

        assertThat(run()).as("unchanged descriptor, unchanged tree").isZero();
    }

    @Test
    void a_removed_provides_removes_the_file_the_step_wrote() throws IOException {
        descriptor(Map.of(
                "com.example.Greeter", new String[] {"com.example.Hello"},
                "com.example.Clock", new String[] {"com.example.SystemClock"}));
        run();

        descriptor(Map.of("com.example.Greeter", new String[] {"com.example.Hello"}));
        assertThat(PlannerModuleServices.outOfSync(classes, incremental)).isTrue();
        assertThat(run()).isEqualTo(1);
        assertThat(classes.resolve(CLOCK)).doesNotExist();
        assertThat(classes.resolve(GREETER)).exists();

        Files.delete(classes.resolve("module-info.class"));
        assertThat(run()).as("no descriptor: nothing of the step's is left").isEqualTo(1);
        assertThat(classes.resolve("META-INF")).doesNotExist();
    }

    @Test
    void a_resource_naming_the_same_providers_is_left_and_never_removed() throws IOException {
        descriptor(Map.of("com.example.Greeter", new String[] {"com.example.Hello", "com.example.Hi"}));
        resource(GREETER, "# hand-written\ncom.example.Hi\n\ncom.example.Hello  # the default\n");

        assertThat(run()).isZero();
        assertThat(read(GREETER)).startsWith("# hand-written");

        descriptor(Map.of());
        assertThat(run()).isZero();
        assertThat(classes.resolve(GREETER))
                .as("the resource's file is not the step's")
                .exists();
    }

    @Test
    void a_resource_naming_other_providers_fails_the_step_and_writes_nothing() throws IOException {
        descriptor(Map.of(
                "com.example.Greeter", new String[] {"com.example.Hello"},
                "com.example.Clock", new String[] {"com.example.SystemClock"}));
        resource(GREETER, "com.example.Legacy\n");

        assertThatThrownBy(this::run)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(GREETER)
                .hasMessageContaining("[com.example.Legacy]")
                .hasMessageContaining("[com.example.Hello]");
        assertThat(classes.resolve(CLOCK)).as("a failed plan writes nothing").doesNotExist();
        assertThat(read(GREETER)).isEqualTo("com.example.Legacy\n");
        assertThat(PlannerModuleServices.outOfSync(classes, incremental)).isTrue();
    }

    @Test
    void a_file_the_step_wrote_that_a_resource_later_claims_is_judged_as_the_resource() throws IOException {
        descriptor(Map.of("com.example.Greeter", new String[] {"com.example.Hello"}));
        run();

        resource(GREETER, "com.example.Other\n");
        assertThatThrownBy(this::run).hasMessageContaining("[com.example.Other]");
    }

    @Test
    void a_tree_without_a_descriptor_is_no_work() throws IOException {
        assertThat(PlannerModuleServices.provides(classes)).isEmpty();
        assertThat(run()).isZero();
    }

    private int run() throws IOException {
        return PlannerModuleServices.apply(PlannerModuleServices.plan(classes, incremental), classes, incremental);
    }

    /** Write {@code module-info.class} for module {@code m} with these {@code provides}. */
    private void descriptor(Map<String, String[]> provides) throws IOException {
        byte[] bytes = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("m"), mb -> {
            mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
            provides.forEach((service, impls) -> {
                ClassDesc[] descs = new ClassDesc[impls.length];
                for (int i = 0; i < impls.length; i++) descs[i] = ClassDesc.of(impls[i]);
                mb.provides(ClassDesc.of(service), descs);
            });
        }));
        Files.write(classes.resolve("module-info.class"), bytes);
    }

    /** A file the resource copy laid into the tree and recorded in its ledger. */
    private void resource(String rel, String content) throws IOException {
        Path target = classes.resolve(rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        Files.createDirectories(incremental);
        Files.writeString(incremental.resolve(ResourceMirror.LEDGER), rel + "\n");
    }

    private String read(String rel) throws IOException {
        return Files.readString(classes.resolve(rel), StandardCharsets.UTF_8);
    }
}
