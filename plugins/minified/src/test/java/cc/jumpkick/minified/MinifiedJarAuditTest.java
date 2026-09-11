// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The post-shrink gate. A class reached only by name leaves no link error when it disappears —
 * the loader skips it and the application runs incomplete — so the build has to be where this
 * is caught.
 */
class MinifiedJarAuditTest {

    @Test
    void a_marker_indexed_class_that_r8_dropped_fails_the_build(@TempDir Path dir) throws Exception {
        Path input = jar(
                dir.resolve("in.jar"),
                Map.of(
                        "META-INF/micronaut/com.acme.Spi/com.acme.$Bean$Definition", "",
                        "com/acme/$Bean$Definition.class", "x"));
        Path output =
                jar(dir.resolve("out.jar"), Map.of("META-INF/micronaut/com.acme.Spi/com.acme.$Bean$Definition", ""));

        assertThatThrownBy(() -> MinifiedJarPackager.auditByNameIndexes(List.of(input), output))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.acme.$Bean$Definition")
                .hasMessageContaining("-keep class com.acme.$Bean$Definition { *; }");
    }

    @Test
    void a_service_named_class_that_r8_dropped_fails_the_build(@TempDir Path dir) throws Exception {
        Path input = jar(
                dir.resolve("in.jar"),
                Map.of(
                        "META-INF/services/org.slf4j.spi.SLF4JServiceProvider", "com.acme.Provider\n",
                        "com/acme/Provider.class", "x"));
        Path output = jar(
                dir.resolve("out.jar"),
                Map.of("META-INF/services/org.slf4j.spi.SLF4JServiceProvider", "com.acme.Provider\n"));

        assertThatThrownBy(() -> MinifiedJarPackager.auditByNameIndexes(List.of(input), output))
                .hasMessageContaining("com.acme.Provider");
    }

    @Test
    void a_kept_class_passes(@TempDir Path dir) throws Exception {
        Map<String, String> both = Map.of(
                "META-INF/services/com.acme.Spi", "com.acme.Impl\n",
                "com/acme/Impl.class", "x");

        assertThatCode(() -> MinifiedJarPackager.auditByNameIndexes(
                        List.of(jar(dir.resolve("in.jar"), both)), jar(dir.resolve("out.jar"), both)))
                .doesNotThrowAnyException();
    }

    @Test
    void a_name_the_input_never_resolved_is_not_r8s_doing(@TempDir Path dir) throws Exception {
        // An optional dependency nobody bundled: the service file names it, no jar ever carried
        // it. Absent before and after, so it is not a removal and must not fail the build.
        Path input = jar(dir.resolve("in.jar"), Map.of("META-INF/services/com.acme.Spi", "com.optional.Missing\n"));
        Path output = jar(dir.resolve("out.jar"), Map.of("META-INF/services/com.acme.Spi", "com.optional.Missing\n"));

        assertThatCode(() -> MinifiedJarPackager.auditByNameIndexes(List.of(input), output))
                .doesNotThrowAnyException();
    }

    @Test
    void the_message_caps_the_list_and_says_how_many_more(@TempDir Path dir) throws Exception {
        var inputEntries = new LinkedHashMap<String, String>();
        var serviceBody = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            serviceBody.append("com.acme.Impl").append(i).append('\n');
            inputEntries.put("com/acme/Impl" + i + ".class", "x");
        }
        inputEntries.put("META-INF/services/com.acme.Spi", serviceBody.toString());
        Path input = jar(dir.resolve("in.jar"), inputEntries);
        Path output = jar(dir.resolve("out.jar"), Map.of("META-INF/services/com.acme.Spi", serviceBody.toString()));

        assertThatThrownBy(() -> MinifiedJarPackager.auditByNameIndexes(List.of(input), output))
                .hasMessageContaining("R8 removed 25 classes")
                .hasMessageContaining("… and 5 more");
    }

    // ------------------------------------------------------------ generic signatures

    private static final String BOX_SIG = "<T:Ljava/lang/Object;>Ljava/lang/Object;";

    @Test
    void an_erased_signature_is_reported_with_before_and_after(@TempDir Path dir) throws Exception {
        Path input = classJar(dir.resolve("in.jar"), Map.of("com/acme/Box.class", classBytes("com.acme.Box", BOX_SIG)));
        Path output = classJar(dir.resolve("out.jar"), Map.of("com/acme/Box.class", classBytes("com.acme.Box", null)));

        var audit = MinifiedJarPackager.auditGenericSignatures(List.of(input), output);
        assertThat(audit.compared()).isEqualTo(1);
        assertThat(audit.degraded()).hasSize(1);
        var drift = audit.degraded().getFirst();
        assertThat(drift.className()).isEqualTo("com.acme.Box");
        assertThat(drift.before()).isEqualTo(BOX_SIG);
        assertThat(drift.after()).isNull();

        String warning = MinifiedJarPackager.signatureWarning(audit);
        assertThat(warning).contains("1 of 1 classes");
        assertThat(warning).contains("com.acme.Box");
        assertThat(warning).contains(BOX_SIG);
        assertThat(warning).contains("now none");
        assertThat(warning).contains("-keepattributes Signature is not enough");
        // 100% degraded is far past the threshold: the honest advice is a fat jar.
        assertThat(warning).contains("assembly = true");
    }

    @Test
    void a_preserved_signature_and_a_signatureless_class_report_nothing(@TempDir Path dir) throws Exception {
        Map<String, byte[]> both = Map.of(
                "com/acme/Box.class", classBytes("com.acme.Box", BOX_SIG),
                "com/acme/Plain.class", classBytes("com.acme.Plain", null));
        Path input = classJar(dir.resolve("in.jar"), both);
        Path output = classJar(dir.resolve("out.jar"), both);

        var audit = MinifiedJarPackager.auditGenericSignatures(List.of(input), output);
        assertThat(audit.compared()).isEqualTo(1);
        assertThat(audit.degraded()).isEmpty();
    }

    @Test
    void a_class_r8_removed_belongs_to_the_by_name_audit_not_this_one(@TempDir Path dir) throws Exception {
        Path input = classJar(dir.resolve("in.jar"), Map.of("com/acme/Box.class", classBytes("com.acme.Box", BOX_SIG)));
        Path output = classJar(dir.resolve("out.jar"), Map.of());

        var audit = MinifiedJarPackager.auditGenericSignatures(List.of(input), output);
        assertThat(audit.compared()).isZero();
        assertThat(audit.degraded()).isEmpty();
    }

    @Test
    void a_small_share_suggests_keeping_the_referenced_types() {
        List<MinifiedJarPackager.SignatureDrift> drifts =
                List.of(new MinifiedJarPackager.SignatureDrift("com.acme.Box", BOX_SIG, null));
        String warning = MinifiedJarPackager.signatureWarning(new MinifiedJarPackager.SignatureAudit(1000, drifts));
        assertThat(warning).contains("[minified] keep");
        assertThat(warning).doesNotContain("assembly = true");
    }

    private static byte[] classBytes(String fqcn, @Nullable String signature) {
        return ClassFile.of().build(ClassDesc.of(fqcn), cb -> {
            cb.withSuperclass(ConstantDescs.CD_Object);
            if (signature != null) {
                cb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(signature)));
            }
        });
    }

    private static Path classJar(Path path, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        return path;
    }

    private static Path jar(Path path, Map<String, String> entries) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return path;
    }
}
