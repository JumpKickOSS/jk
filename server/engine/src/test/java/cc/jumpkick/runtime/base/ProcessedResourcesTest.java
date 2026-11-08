// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.MirroredOutputs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * After compile-main, a resource copy whose bytes are no longer its resource's is held as the
 * processor's; without processors, or after a restore whose record does not hold it, it goes back
 * to the resource.
 */
class ProcessedResourcesTest {

    @TempDir
    Path tmp;

    @Test
    void a_copy_a_processor_rewrote_is_held_with_its_resource_sha() throws Exception {
        Fixture f = new Fixture(tmp);
        Files.writeString(f.copy("META-INF/manifest"), "static\nmerged\n");

        ProcessedResources.settle(f.classes, f.incremental, true, null);

        assertThat(ProcessedResources.read(f.incremental))
                .containsExactly(Map.entry("META-INF/manifest", FileHashMemo.contentHash(f.resource("manifest"))));
        assertThat(ProcessedResources.unchanged(f.classes)).containsExactly("META-INF/manifest");

        Files.writeString(f.resource("manifest"), "edited\n");
        assertThat(ProcessedResources.unchanged(f.classes))
                .as("an edited resource is copied again, so its copy is drift")
                .isEmpty();
    }

    @Test
    void without_processors_a_held_copy_goes_back_to_its_resource() throws Exception {
        Fixture f = new Fixture(tmp);
        Files.writeString(f.copy("META-INF/manifest"), "static\nmerged\n");
        ProcessedResources.settle(f.classes, f.incremental, true, null);

        ProcessedResources.settle(f.classes, f.incremental, false, null);

        assertThat(f.copy("META-INF/manifest")).hasContent("static");
        assertThat(ProcessedResources.read(f.incremental)).isEmpty();
    }

    @Test
    void a_restore_whose_record_lacks_a_held_copy_puts_the_resource_back() throws Exception {
        Fixture f = new Fixture(tmp);
        Files.writeString(f.copy("META-INF/manifest"), "static\nmerged\n");
        ProcessedResources.settle(f.classes, f.incremental, true, null);

        ProcessedResources.settle(f.classes, f.incremental, true, Set.of("App.class"));

        assertThat(f.copy("META-INF/manifest")).hasContent("static");
        assertThat(ProcessedResources.read(f.incremental)).isEmpty();
    }

    /** One resource, {@code META-INF/manifest}, copied as the resource copy leaves it. */
    private static final class Fixture {
        final Path classes;
        final Path incremental;
        final Path resources;

        Fixture(Path tmp) throws Exception {
            classes = Files.createDirectories(tmp.resolve("target/classes"));
            incremental = Files.createDirectories(tmp.resolve("target/incremental"));
            resources = Files.createDirectories(tmp.resolve("resources"));
            Files.createDirectories(resources.resolve("META-INF"));
            Files.writeString(resource("manifest"), "static\n");
            Path ledger = incremental.resolve(MirroredOutputs.MAIN_LEDGER);
            ResourceMirror.Main m = ResourceMirror.syncMain(List.of(resources), Map.of(), classes, ledger, Map.of());
            MirroredOutputs.writeSources(incremental, m.sources());
        }

        Path resource(String name) {
            return resources.resolve("META-INF").resolve(name);
        }

        Path copy(String rel) {
            return classes.resolve(rel);
        }
    }
}
