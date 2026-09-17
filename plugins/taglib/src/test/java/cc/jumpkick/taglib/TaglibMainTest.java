// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.taglib;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated interface as the Jenkins Maven build writes it: the library's directory names
 * the package and the interface, {@code hudson} is read as {@code jenkins}, a hyphenated view
 * becomes an underscored method carrying {@code @TagFile}, a keyword gets its underscore and the
 * same annotation, the
 * view's documentation is the Javadoc, and a directory without the marker gets nothing.
 */
class TaglibMainTest {

    @Test
    void a_taglib_directory_becomes_a_typed_interface_in_its_parent_package(@TempDir Path tmp) throws Exception {
        Path resources = tmp.resolve("src/main/resources");
        write(resources.resolve("lib/layout/taglib"), "Tag library that defines the basic layouts.");
        write(resources.resolve("lib/layout/layout.jelly"), """
                <?jelly escape-by-default='true'?>
                <j:jelly xmlns:j="jelly:core" xmlns:st="jelly:stapler">
                  <st:documentation>
                    Outer-most tag for a normal &lt;html&gt; page.
                    <st:attribute name="title" use="required">Page title.</st:attribute>
                  </st:documentation>
                  <html/>
                </j:jelly>
                """);
        write(resources.resolve("lib/layout/app-bar.jelly"), """
                <j:jelly xmlns:j="jelly:core"><div/></j:jelly>
                """);
        write(resources.resolve("lib/layout/switch.jelly"), """
                <j:jelly xmlns:j="jelly:core"><div/></j:jelly>
                """);
        write(resources.resolve("lib/layout/notes.txt"), "not a view");
        write(resources.resolve("hudson/tools/taglib"), "");
        write(resources.resolve("hudson/tools/label.jelly"), "<j:jelly xmlns:j=\"jelly:core\"/>");
        write(resources.resolve("hudson/model/View/index.jelly"), "<j:jelly xmlns:j=\"jelly:core\"/>");
        Path out = tmp.resolve("out");

        int written = TaglibMain.generate(out, StandardCharsets.UTF_8, List.of(resources));

        assertThat(written).isEqualTo(2);
        assertThat(out.resolve("lib/LayoutTagLib.java")).content().isEqualTo("""
                package lib;

                import groovy.lang.Closure;
                import java.util.Map;
                import org.kohsuke.stapler.jelly.groovy.TagFile;
                import org.kohsuke.stapler.jelly.groovy.TagLibraryUri;
                import org.kohsuke.stapler.jelly.groovy.TypedTagLibrary;

                @TagLibraryUri("/lib/layout")
                public interface LayoutTagLib extends TypedTagLibrary {

                    @TagFile("app-bar")
                    void app_bar(Map args, Closure body);

                    @TagFile("app-bar")
                    void app_bar(Closure body);

                    @TagFile("app-bar")
                    void app_bar(Map args);

                    @TagFile("app-bar")
                    void app_bar();

                    /**
                     * Outer-most tag for a normal &lt;html&gt; page.
                     */
                    void layout(Map args, Closure body);

                    /**
                     * Outer-most tag for a normal &lt;html&gt; page.
                     */
                    void layout(Closure body);

                    /**
                     * Outer-most tag for a normal &lt;html&gt; page.
                     */
                    void layout(Map args);

                    /**
                     * Outer-most tag for a normal &lt;html&gt; page.
                     */
                    void layout();

                    @TagFile("switch")
                    void switch_(Map args, Closure body);

                    @TagFile("switch")
                    void switch_(Closure body);

                    @TagFile("switch")
                    void switch_(Map args);

                    @TagFile("switch")
                    void switch_();
                }
                """);
        assertThat(out.resolve("jenkins/ToolsTagLib.java"))
                .content()
                .contains("package jenkins;")
                .contains("@TagLibraryUri(\"/hudson/tools\")")
                .contains("public interface ToolsTagLib extends TypedTagLibrary")
                .contains("void label(Map args, Closure body);");
        assertThat(out.resolve("jenkins/model")).doesNotExist();
    }

    @Test
    void the_documentation_is_the_elements_own_text_without_its_attribute_entries_or_inline_tags() {
        String view = """
                <?jelly escape-by-default='true'?>
                <j:jelly xmlns:j="jelly:core" xmlns:st="jelly:stapler">
                  <st:documentation>
                    <!-- what the tag is for -->
                    Button that copies text, either <code>text</code> or <code>ref</code> is required &amp; read.
                    <st:attribute name="text" use="required">
                      Text to be copied.
                    </st:attribute>
                    <st:attribute name="ref"/>
                    Second paragraph.
                  </st:documentation>
                  <span/>
                </j:jelly>
                """;

        assertThat(TaglibMain.documentation(view))
                .isEqualTo(
                        "Button that copies text, either  or  is required &amp; read.\n     * \n     * Second paragraph.");
        assertThat(TaglibMain.documentation("<j:jelly xmlns:j=\"jelly:core\"><div/></j:jelly>"))
                .isNull();
        assertThat(TaglibMain.documentation("<j:jelly xmlns:st=\"jelly:stapler\"><st:documentation/></j:jelly>"))
                .isNull();
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
