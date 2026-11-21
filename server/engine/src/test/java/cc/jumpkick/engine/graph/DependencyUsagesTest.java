// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code usages dep:<module>@<version>}: the API a locked dependency would lose at another version,
 * up or down, and where this project uses it, read off the two jars and the project's classes.
 */
@Tag("integration")
class DependencyUsagesTest {

    private static final MethodTypeDesc TRIM = MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String);
    private static final MethodTypeDesc STRIP =
            MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String);

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path isolated;

    @BeforeEach
    void isolate() {
        System.setProperty("jk.env.JK_STORE_DIR", isolated.resolve("store").toString());
        System.setProperty("jk.env.JK_CACHE_DIR", isolated.resolve("cache").toString());
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        for (String v : List.of("1.0.0", "1.1.0")) {
            upstream.pomOnly("com.acme", "text", v, MavenStub.emptyPom("com.acme", "text", v));
            upstream.bytes(MavenStub.path("com.acme", "text", v, ".jar"), textJar(v));
        }
        upstream.metadata("com.acme", "text", "1.0.0", "1.1.0");
    }

    @AfterEach
    void release() {
        LockfileReader.clearCache();
    }

    @Test
    void an_upgrade_lists_the_api_it_takes_away_and_the_calls_of_it(@TempDir Path dir) throws Exception {
        project(dir, "1.0.0", "trim", TRIM);

        GraphAck ack = DependencyUsages.answer(dir, usages("dep:com.acme:text@1.1.0"));

        assertThat(ack.error()).isNull();
        assertThat(ack.text())
                .startsWith("com.acme:text 1.0.0 → 1.1.0: 1 API change, 1 used here at 1 place\n")
                .contains("  removed  acme.Text.trim(String): String\n    src/main/java/app/App.java:7  App.run");
    }

    @Test
    void a_downgrade_lists_the_api_the_older_version_lacks(@TempDir Path dir) throws Exception {
        project(dir, "1.1.0", "strip", STRIP);

        GraphAck ack = DependencyUsages.answer(dir, usages("dep:text@1.0.0"));

        assertThat(ack.error())
                .as("the artifact name alone names the one locked module")
                .isNull();
        assertThat(ack.text())
                .startsWith("com.acme:text 1.1.0 → 1.0.0: 1 API change, 1 used here at 1 place\n")
                .contains("  removed  acme.Text.strip(String, String): String\n"
                        + "    src/main/java/app/App.java:7  App.run");
    }

    @Test
    void an_address_naming_no_locked_module_or_no_version_is_refused(@TempDir Path dir) throws Exception {
        project(dir, "1.0.0", "trim", TRIM);

        assertThat(DependencyUsages.answer(dir, usages("dep:guava@33.4.8-jre")).error())
                .isEqualTo("guava: not a locked dependency");
        assertThat(DependencyUsages.answer(dir, usages("dep:com.acme:text")).error())
                .isEqualTo("dep:com.acme:text: write dep:<group:artifact>@<version>");
    }

    // ---- fixture ---------------------------------------------------------------

    private static GraphRequest usages(String address) {
        return new GraphRequest(
                null,
                "usages",
                List.of(address),
                null,
                0,
                2000,
                List.of(),
                false,
                0,
                null,
                false,
                false,
                null,
                null,
                null,
                List.of());
    }

    /** A project locked on {@code com.acme:text:<locked>} whose {@code App.run} calls {@code acme.Text.<call>} on line 7. */
    private void project(Path dir, String locked, String call, MethodTypeDesc type) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "%s"

                [dependencies]
                text = "com.acme:text:%s"
                """.formatted(http.baseUrl(), locked));
        LockFlow.Result lock = LockFlow.run(dir, isolated.resolve("cache"), List.of(), false, null);
        assertThat(lock.status()).as("the lock: %s", lock.error()).isZero();
        Files.createDirectories(dir.resolve("src/main/java/app"));
        Files.writeString(dir.resolve("src/main/java/app/App.java"), "package app;\n");
        Path classes = Files.createDirectories(dir.resolve("target/classes/app"));
        Files.write(classes.resolve("App.class"), ClassFile.of().build(ClassDesc.of("app.App"), c -> {
            c.withFlags(ClassFile.ACC_PUBLIC);
            c.with(SourceFileAttribute.of("App.java"));
            c.withMethodBody("run", ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> {
                b.lineNumber(7);
                for (int i = 0; i < type.parameterCount(); i++) b.ldc("x");
                b.invokestatic(ClassDesc.of("acme.Text"), call, type);
                b.pop();
                b.return_();
            });
        }));
    }

    /** {@code acme.Text}: {@code trim(String)} in 1.0.0, {@code strip(String, String)} in its place in 1.1.0. */
    private static byte[] textJar(String version) {
        boolean first = version.equals("1.0.0");
        byte[] text = ClassFile.of().build(ClassDesc.of("acme.Text"), c -> {
            c.withFlags(ClassFile.ACC_PUBLIC);
            c.withMethodBody(
                    first ? "trim" : "strip",
                    first ? TRIM : STRIP,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    b -> b.aload(0).areturn());
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("acme/Text.class"));
            zip.write(text);
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }
}
