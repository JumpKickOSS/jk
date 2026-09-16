// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The owner's own posture test. Every jk reader now parses through {@link DomXml}, so a door that
 * forgot the hardening would weaken all of them at once — each of the four is exercised against the
 * same entity-carrying document, and the secret is asserted absent from the failure as well, since
 * a resolved entity that then fails validation has still leaked.
 */
class DomXmlTest {

    private static final String SECRET = "TOP_SECRET_VALUE";

    private static String poisoned(Path secret) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE project [ <!ENTITY leak SYSTEM "file://%s"> ]>
                <project><artifactId>&leak;</artifactId></project>
                """.formatted(secret.toAbsolutePath());
    }

    private static Path secretFile(Path tmp) throws IOException {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, SECRET);
        return secret;
    }

    @Test
    void bytes_reject_an_external_entity(@TempDir Path tmp) throws Exception {
        String xml = poisoned(secretFile(tmp));
        assertThatThrownBy(() -> DomXml.parse(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void text_rejects_an_external_entity(@TempDir Path tmp) throws Exception {
        String xml = poisoned(secretFile(tmp));
        assertThatThrownBy(() -> DomXml.parse(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void a_stream_rejects_an_external_entity(@TempDir Path tmp) throws Exception {
        String xml = poisoned(secretFile(tmp));
        assertThatThrownBy(() -> DomXml.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void a_file_rejects_an_external_entity(@TempDir Path tmp) throws Exception {
        Path xml = tmp.resolve("poisoned.xml");
        Files.writeString(xml, poisoned(secretFile(tmp)));
        assertThatThrownBy(() -> DomXml.parse(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining(SECRET);
    }

    /** An entity-free DOCTYPE is refused too: jk rejects the declaration, not just what it declares. */
    @Test
    void an_entity_free_doctype_is_still_refused() {
        assertThatThrownBy(() -> DomXml.parse("<!DOCTYPE project><project/>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    void a_plain_document_parses_through_every_door(@TempDir Path tmp) throws Exception {
        String xml = "<project><artifactId>widget</artifactId></project>";
        Path file = tmp.resolve("plain.xml");
        Files.writeString(file, xml);

        for (var doc : List.of(
                DomXml.parse(xml),
                DomXml.parse(xml.getBytes(StandardCharsets.UTF_8)),
                DomXml.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                DomXml.parse(file))) {
            assertThat(DomXml.childText(doc.getDocumentElement(), "artifactId")).isEqualTo("widget");
        }
    }

    /** Text is decoded already, so a lying {@code encoding=} must not be applied a second time. */
    @Test
    void text_ignores_the_encoding_declaration() throws Exception {
        var doc = DomXml.parse("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><p><n>café</n></p>");
        assertThat(DomXml.childText(doc.getDocumentElement(), "n")).isEqualTo("café");
    }

    /** Bytes are not, so the declaration is what tells the parser how to decode them. */
    @Test
    void bytes_honour_the_encoding_declaration() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><p><n>café</n></p>";
        var doc = DomXml.parse(xml.getBytes(StandardCharsets.ISO_8859_1));
        assertThat(DomXml.childText(doc.getDocumentElement(), "n")).isEqualTo("café");
    }

    @Test
    void new_document_builds_a_dom_to_write() {
        var doc = DomXml.newDocument();
        var root = doc.createElement("resources");
        doc.appendChild(root);
        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("resources");
    }

    @Test
    void child_navigation_reads_direct_children_only() throws Exception {
        var doc = DomXml.parse("""
                <project>
                  <artifactId>outer</artifactId>
                  <parent><artifactId>inner</artifactId></parent>
                  <modules><module>a</module><module>b</module></modules>
                </project>
                """);
        var project = doc.getDocumentElement();
        assertThat(DomXml.childText(project, "artifactId")).isEqualTo("outer");
        assertThat(DomXml.childText(DomXml.childElement(project, "parent"), "artifactId"))
                .isEqualTo("inner");
        assertThat(DomXml.childElements(DomXml.childElement(project, "modules"), "module"))
                .extracting(e -> e.getTextContent().trim())
                .containsExactly("a", "b");
        assertThat(DomXml.childElements(project)).hasSize(3);
        assertThat(DomXml.childElement(null, "anything")).isNull();
        assertThat(DomXml.childText(project, "nope")).isNull();
    }

    /**
     * Maven's POM reader accepts the HTML 4 character entities without a declaration, and Central
     * holds POMs that lean on it: the plexus parent names a developer {@code Laugst&oslash;l}.
     */
    @Test
    void an_html_entity_reads_as_maven_reads_it() throws Exception {
        String xml = "<project><name>Trygve Laugst&oslash;l</name></project>";
        assertThat(DomXml.childText(DomXml.parse(xml).getDocumentElement(), "name"))
                .isEqualTo("Trygve Laugst\u00f8l");
        assertThat(DomXml.childText(
                        DomXml.parse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement(), "name"))
                .isEqualTo("Trygve Laugst\u00f8l");
    }

    @Test
    void an_html_entity_in_latin1_bytes_keeps_the_rest_of_the_document() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><p><n>caf\u00e9 &amp; Laugst&oslash;l</n></p>";
        var doc = DomXml.parse(xml.getBytes(StandardCharsets.ISO_8859_1));
        assertThat(DomXml.childText(doc.getDocumentElement(), "n")).isEqualTo("caf\u00e9 & Laugst\u00f8l");
    }

    @Test
    void an_entity_outside_the_table_is_still_refused() {
        assertThatThrownBy(() -> DomXml.parse("<project><name>&bogus;</name></project>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void an_html_entity_inside_cdata_stays_literal() throws Exception {
        var doc = DomXml.parse("<p><n><![CDATA[&oslash;]]></n></p>");
        assertThat(DomXml.childText(doc.getDocumentElement(), "n")).isEqualTo("&oslash;");
    }
}
