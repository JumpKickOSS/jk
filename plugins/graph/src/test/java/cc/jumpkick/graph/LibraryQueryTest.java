// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryQueryTest {

    private static LibraryQuery query(Path dir, boolean sources) throws IOException {
        Path classes = LibraryApiTest.jar(dir);
        LibraryApi.Jar lib =
                new LibraryApi.Jar("org.lib:lib:1.0", classes, sources ? LibraryApiTest.sourcesJar(dir) : null);
        LibraryApi.Jar other = new LibraryApi.Jar("org.other:other-kit:2.0", classes);
        return new LibraryQuery(new LibraryApi(List.of(lib, other), dir.resolve("cache")));
    }

    @Test
    void aQuestionNamesTheMatchingPublicMembersWithTheirSignaturesAndJavadoc(@TempDir Path dir) throws IOException {
        String out = query(dir, true).answer("lib", "read value", 2000);
        assertThat(out)
                .startsWith("[i] org.lib:lib:1.0 is the locked version; names and Javadoc summaries from its"
                        + " sources jar were matched.")
                .contains("\n\norg.lib.Mapper.readValue\n"
                        + "  Reads json as a type.\n"
                        + "  public <T> T readValue(String json, Class<T> type) throws IOException\n"
                        + "  public <T> T readValue(byte[] json, Class<T> type) throws IOException")
                .doesNotContain("org.other")
                .doesNotContain("hidden")
                .doesNotContain("Secret");
        assertThat(out.lines().filter(l -> l.startsWith("org.lib.")).findFirst())
                .hasValue("org.lib.Mapper.readValue");
        assertThat(Files.list(dir.resolve("cache")).map(p -> p.getFileName().toString()))
                .anyMatch(name -> name.endsWith(".api"));
    }

    @Test
    void aWordOnlyTheJavadocSaysFindsItsTypeWhenTheSourcesJarIsHere(@TempDir Path dir) throws IOException {
        assertThat(query(dir, true).answer("org.lib:lib", "objects", 2000))
                .contains("\n\norg.lib.Mapper\n"
                        + "  Maps JSON text to objects.\n"
                        + "  public class Mapper implements Serializable");
    }

    @Test
    void withoutASourcesJarNamesAloneAreMatchedAndTheAnswerSaysSo(@TempDir Path dir) throws IOException {
        LibraryQuery q = query(dir, false);
        assertThat(q.answer("lib", "tree", 2000))
                .contains("no sources jar on this machine, so names alone were matched")
                .contains("org.lib.Mapper.tree\n  public Map<String, List<? extends Number>> tree(String... paths)");
        assertThat(q.answer("lib", "objects", 2000))
                .startsWith("No public type or member of org.lib:lib:1.0 matches 'objects'.");
    }

    @Test
    void theBudgetKeepsTheBestMatchesWhole(@TempDir Path dir) throws IOException {
        LibraryQuery q = query(dir, true);
        String all = q.answer("lib", "mapper read value tree hook feature", 4000);
        assertThat(all).contains(" matches among ").contains("org.lib.Mapper.hook");
        String cut = q.answer("lib", "mapper read value tree hook feature", 120);
        assertThat(cut).contains("The best ").contains("a larger budget shows more.");
        assertThat(cut.lines().filter(l -> l.startsWith("org.lib.")).count())
                .isLessThan(all.lines().filter(l -> l.startsWith("org.lib.")).count());
    }

    @Test
    void aLibraryIsNamedByArtifactGroupAndArtifactOrGlob(@TempDir Path dir) throws IOException {
        LibraryQuery q = query(dir, false);
        assertThat(q.select("other-kit")).extracting(LibraryApi.Jar::coord).containsExactly("org.other:other-kit:2.0");
        assertThat(q.select("org.lib:lib")).extracting(LibraryApi.Jar::coord).containsExactly("org.lib:lib:1.0");
        assertThat(q.select("org.*")).hasSize(2);
        assertThat(q.select("org.lib:lib:1.0")).hasSize(1);
        assertThat(q.answer("org.*", "tree", 2000))
                .contains("[i] org.lib:lib:1.0 is the locked version")
                .contains("[i] org.other:other-kit:2.0 is the locked version")
                .contains("org.lib.Mapper.tree  (org.lib:lib:1.0)")
                .contains("org.lib.Mapper.tree  (org.other:other-kit:2.0)");
        assertThat(q.answer("jackson", "read", 2000))
                .startsWith("No locked library matches 'jackson'.")
                .contains("  org.lib:lib:1.0")
                .contains("  org.other:other-kit:2.0");
        assertThat(new LibraryQuery(new LibraryApi(List.of(), null)).answer("lib", "read", 2000))
                .startsWith("No locked jar is on this machine");
    }
}
