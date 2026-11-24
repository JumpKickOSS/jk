// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reference index and the generated-file ledger live beside the classes, not among them: a
 * compile served from the action cache has to lay them back too, or they describe whatever javac
 * last compiled, or nothing.
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

    @Test
    void a_hit_of_the_compile_that_wrote_the_generated_file_ledger_lays_it_back(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectories(dir.resolve("module/target/classes"));
        Path state = Files.createDirectories(dir.resolve("state"));
        Path ledger = JavaCompile.generatedLedger(out);
        assertThat(ledger).isEqualTo(dir.resolve("module/target/incremental/classes-generated.tsv"));
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "/w/gen/a/AGen.java\t/w/src/a/A.java\n");
        JavaCompile.keepGeneratedLedger(state, "key-1", out);

        Files.delete(ledger);
        JavaCompile.restoreGeneratedLedger(state, "key-2", out);
        assertThat(ledger).as("a hit of another compile's classes").doesNotExist();

        JavaCompile.restoreGeneratedLedger(state, "key-1", out);
        assertThat(ledger).hasContent("/w/gen/a/AGen.java\t/w/src/a/A.java\n");

        Files.writeString(ledger, "/w/gen/b/BGen.java\t/w/src/b/B.java\n");
        JavaCompile.restoreGeneratedLedger(state, "key-1", out);
        assertThat(ledger)
                .as("another compile's ledger on disk is replaced")
                .hasContent("/w/gen/a/AGen.java\t/w/src/a/A.java\n");
    }

    @Test
    void a_hit_of_a_compile_the_state_kept_no_ledger_for_takes_the_ledger_on_disk(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectories(dir.resolve("module/target/classes"));
        Path state = Files.createDirectories(dir.resolve("state"));
        Path ledger = JavaCompile.generatedLedger(out);
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "/w/gen/a/AGen.java\t/w/src/a/A.java\n");
        JavaCompile.keepGeneratedLedger(state, "key-1", out);

        // The classes of key-2 are laid over key-1's: key-1's ledger would vouch for generated
        // classes that are not there and leave out those that are.
        JavaCompile.restoreGeneratedLedger(state, "key-2", out);
        assertThat(ledger).as("a hit of another compile's classes").doesNotExist();

        Files.writeString(ledger, "/w/gen/a/AGen.java\t/w/src/a/A.java\n");
        Files.delete(state.resolve(JavaCompile.GENERATED_LEDGER));
        JavaCompile.restoreGeneratedLedger(state, "key-1", out);
        assertThat(ledger).as("a state that kept no ledger vouches for none").doesNotExist();
    }

    @Test
    void a_compile_that_wrote_no_generated_file_ledger_keeps_none(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectories(dir.resolve("module/target/classes"));
        Path state = Files.createDirectories(dir.resolve("state"));
        Files.writeString(state.resolve(JavaCompile.GENERATED_LEDGER), "key-0\nstale\n");
        JavaCompile.keepGeneratedLedger(state, "key-1", out);
        assertThat(state.resolve(JavaCompile.GENERATED_LEDGER)).doesNotExist();
    }

    @Test
    void a_hit_lays_back_the_state_another_checkout_cached_naming_this_checkouts_files(@TempDir Path dir)
            throws Exception {
        ActionCache cache = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        Path one = checkout(dir.resolve("one"));
        Path two = checkout(dir.resolve("two"));
        Path outOne = Files.createDirectories(one.resolve("target/classes"));
        Path outTwo = Files.createDirectories(two.resolve("target/classes"));
        Path stateOne = Files.createDirectories(dir.resolve("state-one"));
        Path stateTwo = Files.createDirectories(dir.resolve("state-two"));
        Path ledgerOne = JavaCompile.generatedLedger(outOne);
        Files.createDirectories(ledgerOne.getParent());
        Files.writeString(ledgerOne, one.resolve("gen/a/AGen.java") + "\t" + one.resolve("src/a/A.java") + "\n");
        Files.writeString(stateOne.resolve("zinc"), "analysis of key-1");
        Files.writeString(stateOne.resolve(CompileWarnings.LEDGER), "");
        JavaCompile.nameRoot(stateOne, outOne);
        JavaCompile.keepGeneratedLedger(stateOne, "key-1", outOne);
        JavaCompile.storeState(cache, cache.cas(), "compile-main@m", "key-1", stateOne);

        Files.writeString(stateTwo.resolve("zinc"), "this checkout's last analysis");
        Files.writeString(stateTwo.resolve("analysis-off"), "digest\nsome.Type\n");
        JavaCompile.restoreState(cache, "key-1", stateTwo, outTwo);
        JavaCompile.restoreGeneratedLedger(stateTwo, "key-1", outTwo);

        assertThat(stateTwo.resolve("zinc")).hasContent("analysis of key-1");
        assertThat(stateTwo.resolve(CompileWarnings.LEDGER)).exists();
        assertThat(stateTwo.resolve("analysis-off"))
                .as("the marker of this checkout's last compile")
                .doesNotExist();
        assertThat(stateTwo.resolve(JavaCompile.ROOT_FILE)).hasContent(two + "\n");
        assertThat(JavaCompile.generatedLedger(outTwo))
                .hasContent(two.resolve("gen/a/AGen.java") + "\t" + two.resolve("src/a/A.java") + "\n");
    }

    @Test
    void a_hit_whose_compile_cached_no_state_leaves_the_state_alone(@TempDir Path dir) throws Exception {
        ActionCache cache = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        Path out = Files.createDirectories(checkout(dir.resolve("app")).resolve("target/classes"));
        Path state = Files.createDirectories(dir.resolve("state"));
        Files.writeString(state.resolve("zinc"), "analysis with absolute paths");
        JavaCompile.storeState(cache, cache.cas(), "compile-main@m", "key-1", state);

        Files.writeString(state.resolve("zinc"), "this checkout's last analysis");
        JavaCompile.restoreState(cache, "key-1", state, out);

        assertThat(state.resolve("zinc")).hasContent("this checkout's last analysis");
    }

    /** A project checked out at {@code root}. */
    private static Path checkout(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("jk.toml"), "name = \"app\"\n");
        return root.toAbsolutePath().normalize();
    }
}
