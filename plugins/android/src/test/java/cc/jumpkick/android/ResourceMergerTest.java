// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

/**
 * The AAR resource merge — the half of {@link ResourceMerger} that runs on a healthy build.
 * {@link ResourceMergerXxeTest} covers the arm that refuses a poisoned dependency; everything below
 * covers what the merge is <em>for</em>.
 *
 * <p>It exists because aapt2 hard-errors when two link inputs define the same value resource, which
 * two androidx AARs routinely do. So the dependency closure is resolved to one tree first, at
 * resource granularity, with the earlier classpath entry winning — AGP's precedence. Get the
 * precedence backwards and the build still succeeds: it just links a different string, colour or
 * theme than AGP would, and nothing says so.
 */
class ResourceMergerTest {

    /** File resources are copied whole, so the winner is decided by who writes the file. */
    @Test
    void the_earlier_dependency_wins_a_file_resource(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        Path second = aar(tmp, "second");
        FakeBuildIo.write(first.resolve("res/drawable/ic_logo.xml"), "<vector>first</vector>");
        FakeBuildIo.write(second.resolve("res/drawable/ic_logo.xml"), "<vector>second</vector>");

        Path out = merge(tmp, first, second);

        assertThat(out.resolve("drawable/ic_logo.xml"))
                .as("classpath order decides; the later dependency does not overwrite")
                .hasContent("<vector>first</vector>");
    }

    /** The same precedence, at resource granularity rather than file granularity. */
    @Test
    void the_earlier_dependency_wins_a_value_and_the_later_one_still_contributes(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        Path second = aar(tmp, "second");
        FakeBuildIo.write(first.resolve("res/values/strings.xml"), resources("""
                <string name="shared">from-first</string>
                """));
        FakeBuildIo.write(second.resolve("res/values/strings.xml"), resources("""
                <string name="shared">from-second</string>
                <string name="only_second">kept</string>
                """));

        Path out = merge(tmp, first, second);

        String merged = Files.readString(out.resolve("values/values.xml"));
        assertThat(merged).contains("from-first").doesNotContain("from-second");
        assertThat(merged)
                .as("a resource only the later AAR defines is not lost")
                .contains("only_second");
        assertThat(valuesFiles(out))
                .as("one values.xml per config — aapt2 links the merged tree, not N inputs")
                .containsExactly("values/values.xml");
    }

    /** Two AARs can spell the same file name; the merge is by resource identity, not by file. */
    @Test
    void every_dependencys_values_file_is_read_whatever_it_is_called(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        FakeBuildIo.write(first.resolve("res/values/strings.xml"), resources("""
                <string name="a">A</string>
                """));
        FakeBuildIo.write(first.resolve("res/values/colors.xml"), resources("""
                <color name="brand">#FF0000</color>
                """));
        FakeBuildIo.write(first.resolve("res/values/notxml.txt"), "ignored");

        Path out = merge(tmp, first);

        assertThat(Files.readString(out.resolve("values/values.xml")))
                .contains("name=\"a\"")
                .contains("name=\"brand\"")
                .doesNotContain("ignored");
    }

    /** A qualifier is a different configuration, so it gets its own merged file, never a mixed one. */
    @Test
    void each_resource_qualifier_merges_into_its_own_file(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        FakeBuildIo.write(first.resolve("res/values/strings.xml"), resources("""
                <string name="hello">Hello</string>
                """));
        FakeBuildIo.write(first.resolve("res/values-fr/strings.xml"), resources("""
                <string name="hello">Bonjour</string>
                """));

        Path out = merge(tmp, first);

        assertThat(valuesFiles(out)).containsExactly("values-fr/values.xml", "values/values.xml");
        assertThat(out.resolve("values/values.xml")).content().contains("Hello").doesNotContain("Bonjour");
        assertThat(out.resolve("values-fr/values.xml"))
                .content()
                .contains("Bonjour")
                .doesNotContain("Hello");
    }

    /**
     * The merge key is {@code (tag, type-attribute, name)}. {@code <item type="id">} is how a bare
     * id is declared, and it shares the {@code name} space with nothing — keying on {@code name}
     * alone would silently drop one of these two.
     */
    @Test
    void a_typed_item_does_not_collide_with_a_string_of_the_same_name(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        FakeBuildIo.write(first.resolve("res/values/values.xml"), resources("""
                <string name="submit">Submit</string>
                <item type="id" name="submit"/>
                <item type="dimen" name="submit">4dp</item>
                """));

        Path out = merge(tmp, first);

        String merged = Files.readString(out.resolve("values/values.xml"));
        assertThat(merged).contains("<string name=\"submit\">");
        assertThat(merged).contains("type=\"id\"");
        assertThat(merged).contains("type=\"dimen\"");
    }

    /**
     * The parser is namespace-unaware on purpose ({@code DomXml}), so {@code xmlns:*} are ordinary
     * attributes on each source root and a prefixed attribute inside an element is just a name with
     * a colon in it. Dropping the declarations would emit a values.xml whose prefixes resolve to
     * nothing — malformed XML that aapt2 rejects, from an input that was fine.
     */
    @Test
    void namespace_declarations_from_the_dependencies_reach_the_merged_root(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        Path second = aar(tmp, "second");
        FakeBuildIo.write(first.resolve("res/values/strings.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <resources xmlns:tools="http://schemas.android.com/tools">
                    <string name="a" tools:ignore="MissingTranslation">A</string>
                </resources>
                """);
        FakeBuildIo.write(second.resolve("res/values/strings.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
                    <string name="b">B</string>
                </resources>
                """);

        Path out = merge(tmp, first, second);

        String merged = Files.readString(out.resolve("values/values.xml"));
        assertThat(merged)
                .contains("xmlns:tools=\"http://schemas.android.com/tools\"")
                .contains("xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\"");
        assertThat(merged).contains("tools:ignore=\"MissingTranslation\"");
        // The real bar: the result re-parses. A prefix with no declaration would not.
        assertThat(DomXml.parse(out.resolve("values/values.xml"))
                        .getDocumentElement()
                        .getTagName())
                .isEqualTo("resources");
    }

    /**
     * A generated prefix such as {@code ns1} means whatever each library's merger bound it to. Two
     * dependencies binding it to different URIs must each keep their own URI in the merged file,
     * and a URI two of them share needs one declaration.
     */
    @Test
    void a_prefix_two_dependencies_bind_to_different_uris_keeps_each_uri(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        Path second = aar(tmp, "second");
        FakeBuildIo.write(first.resolve("res/values/strings.xml"), """
                <resources xmlns:ns1="http://schemas.android.com/tools">
                    <string name="a" ns1:ignore="MissingTranslation">A</string>
                </resources>
                """);
        FakeBuildIo.write(second.resolve("res/values/strings.xml"), """
                <resources xmlns:ns1="urn:oasis:names:tc:xliff:document:1.2" xmlns:t="http://schemas.android.com/tools">
                    <string name="b"><ns1:g id="count" t:ignore="x">%d</ns1:g> items</string>
                </resources>
                """);

        Path out = merge(tmp, first, second);

        Element root = DomXml.parse(out.resolve("values/values.xml")).getDocumentElement();
        Element a = string(root, "a");
        Element g = DomXml.childElements(string(root, "b")).getFirst();
        assertThat(uri(root, prefixedAttribute(a))).isEqualTo(TOOLS);
        assertThat(uri(root, g.getTagName())).isEqualTo("urn:oasis:names:tc:xliff:document:1.2");
        assertThat(prefixedAttribute(g))
                .as("the tools URI the second file spelled t: is the one the first declared")
                .isEqualTo(prefixedAttribute(a));
        assertThat(root.getAttributes().getLength())
                .as("one declaration per URI")
                .isEqualTo(2);
    }

    /**
     * The whitespace inside a value is part of it: a string with markup must come out with no
     * line breaks or indent the merge added around its children.
     */
    @Test
    void a_strings_whitespace_comes_out_as_it_went_in(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        String body = "Hello <b>world</b>, <i>you</i>";
        FakeBuildIo.write(first.resolve("res/values/strings.xml"), resources("""
                <string name="greeting">%s</string>
                """.formatted(body)));

        Path out = merge(tmp, first);

        assertThat(out.resolve("values/values.xml")).content().contains(">" + body + "</string>");
    }

    /**
     * No dependency carries resources, so there is nothing to link against    /**
     * No dependency carries resources, so there is nothing to link against and the caller must be
     * told — {@code ResourceStep} passes no dep input to aapt2 at all in that case.
     */
    @Test
    void a_closure_with_no_dependency_resources_merges_to_nothing(@TempDir Path tmp) throws Exception {
        Path bare = aar(tmp, "bare");
        Files.createDirectories(bare.resolve("res")); // present but empty: hasRes() is false
        Path jarOnly = aar(tmp, "jar-only"); // no res/ at all

        Path out = tmp.resolve("merged");
        assertThat(ResourceMerger.mergeDepRes(aars(bare, jarOnly), out)).isNull();
        assertThat(out).doesNotExist();
    }

    /**
     * A dependency whose {@code res/} holds only loose files — no configuration directories — still
     * has to yield a directory, because the caller's next move is to list it.
     */
    @Test
    void a_dependency_with_no_configuration_directories_still_yields_a_tree(@TempDir Path tmp) throws Exception {
        Path odd = aar(tmp, "odd");
        FakeBuildIo.write(odd.resolve("res/stray.txt"), "not a configuration directory");

        Path out = tmp.resolve("merged");
        Path merged = ResourceMerger.mergeDepRes(aars(odd), out);

        assertThat(merged).isNotNull().isDirectory();
        assertThat(valuesFiles(out)).isEmpty();
    }

    /**
     * Merged output order is the classpath, then document order within each dependency — a fixed
     * sequence, not a hash order. The merged tree is an aapt2 input and aapt2's output keys the
     * action cache, so a reshuffle here is a spurious rebuild of everything downstream.
     */
    @Test
    void the_merged_order_is_classpath_order_then_document_order(@TempDir Path tmp) throws Exception {
        Path first = aar(tmp, "first");
        Path second = aar(tmp, "second");
        Path third = aar(tmp, "third");
        FakeBuildIo.write(first.resolve("res/values/strings.xml"), resources("""
                <string name="one">1</string>
                <string name="two">2</string>
                """));
        FakeBuildIo.write(second.resolve("res/values/strings.xml"), resources("""
                <string name="three">3</string>
                """));
        FakeBuildIo.write(third.resolve("res/values/strings.xml"), resources("""
                <string name="four">4</string>
                """));

        Path out = merge(tmp, first, second, third);

        assertThat(names(Files.readString(out.resolve("values/values.xml"))))
                .containsExactly("one", "two", "three", "four");
    }

    // ---- fixtures -----------------------------------------------------------------------

    private static Path aar(Path tmp, String name) throws Exception {
        return Files.createDirectories(tmp.resolve("deps").resolve(name));
    }

    private static List<AndroidDeps.Aar> aars(Path... containers) {
        List<AndroidDeps.Aar> out = new ArrayList<>();
        for (Path container : containers) {
            out.add(new AndroidDeps.Aar(container.getFileName() + ".aar", container));
        }
        return out;
    }

    private static Path merge(Path tmp, Path... containers) throws Exception {
        Path out = tmp.resolve("merged");
        Path merged = ResourceMerger.mergeDepRes(aars(containers), out);
        assertThat(merged).as("these fixtures all carry resources").isEqualTo(out);
        return out;
    }

    private static String resources(String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" + body + "</resources>\n";
    }

    /** Every {@code values*.xml} under {@code out}, as {@code /}-separated relative paths, sorted. */
    private static List<String> valuesFiles(Path out) throws Exception {
        try (var walk = Files.walk(out)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> out.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static final String TOOLS = "http://schemas.android.com/tools";

    /** The child of {@code root} named {@code name}. */
    private static Element string(Element root, String name) {
        return DomXml.childElements(root).stream()
                .filter(el -> name.equals(el.getAttribute("name")))
                .findFirst()
                .orElseThrow();
    }

    /** The URI {@code root} binds {@code qname}'s prefix to. */
    private static String uri(Element root, String qname) {
        return root.getAttribute("xmlns:" + qname.substring(0, qname.indexOf(':')));
    }

    /** The name of {@code el}'s one prefixed attribute. */
    private static String prefixedAttribute(Element el) {
        var attrs = el.getAttributes();
        for (int a = 0; a < attrs.getLength(); a++) {
            if (attrs.item(a).getNodeName().contains(":")) return attrs.item(a).getNodeName();
        }
        throw new AssertionError("no prefixed attribute on " + el.getTagName());
    }

    /** The {@code name="…"} attributes of a merged values document, in document order. */
    private static List<String> names(String xml) throws Exception {
        var root = DomXml.parse(xml).getDocumentElement();
        List<String> out = new ArrayList<>();
        for (var el : DomXml.childElements(root)) {
            out.add(el.getAttribute("name"));
        }
        return out;
    }
}
