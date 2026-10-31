// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reference index lives beside the classes, not among them: a compile served from the action
 * cache has to lay it back too, or it keeps describing whatever javac last compiled.
 */
class JavaCompileReferencesTest {

    @Test
    void a_compile_served_from_the_cache_lays_back_the_reference_index_it_wrote(@TempDir Path dir) throws Exception {
        ActionCache cache = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        Path out = Files.createDirectories(dir.resolve("module/target/classes"));
        Path refs = JavaCompile.referencesDir(out);
        assertThat(refs).isEqualTo(dir.resolve("module/target/incremental/classes-refs"));
        Path a = refs.resolve("src/main/java/a/A.java.refs");
        Files.createDirectories(a.getParent());
        Files.writeString(a, "# jk-refs 1 aaa\nr\tT a.A\t0\t1\t1\n");

        JavaCompile.storeReferences(cache, cache.cas(), "compile-java:m", "key-1", out);
        // A later compile of other sources rewrites the index; then the first sources come back.
        Files.writeString(a, "# jk-refs 1 bbb\n");
        JavaCompile.restoreReferences(cache, "key-1", out);

        assertThat(a).hasContent("# jk-refs 1 aaa\nr\tT a.A\t0\t1\t1\n");
    }

    @Test
    void a_compile_with_no_cached_index_leaves_the_directory_alone(@TempDir Path dir) throws Exception {
        ActionCache cache = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        Path out = Files.createDirectories(dir.resolve("module/target/classes"));
        JavaCompile.restoreReferences(cache, "never-stored", out);
        assertThat(JavaCompile.referencesDir(out)).doesNotExist();
    }
}
