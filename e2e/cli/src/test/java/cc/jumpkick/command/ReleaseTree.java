// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

/** What a release host serves, laid out on disk for the wrapper tests: pointers, sums and a client. */
final class ReleaseTree {
    private ReleaseTree() {}

    /**
     * One {@code LATEST} object: {@code body} plus a {@code signature} line over those bytes from
     * {@code signer}, laid out as sign-latest-pointer.sh writes a well-formed pointer.
     */
    static void signedPointer(Path latestDir, String body, KeyPair signer) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(bytes);
        String object = body + "signature " + Base64.getEncoder().encodeToString(signature.sign()) + "\n";
        Files.writeString(latestDir.resolve("LATEST"), object);
        Files.deleteIfExists(latestDir.resolve("LATEST.sig"));
    }

    /**
     * A release host under {@code root} for version {@code version}, signed by {@code key}: the
     * {@code latest/LATEST} pointer, and a version directory holding a JVM client jar plus the
     * {@code SHA256SUMS} and {@code SHA256SUMS.sig} that cover it, and no native client. The jar's
     * {@code self write-launcher} writes a {@code bin/jk.bat} and a {@code bin/jk} that answer
     * {@code jvm jk: <args>}, and counts itself in {@code <home>/launchers-written}.
     */
    static void jvmClientRelease(Path root, String version, KeyPair key, Path tmp) throws Exception {
        signedPointer(Files.createDirectories(root.resolve("latest")), "version " + version + "\nissued 1\n", key);
        Path dir = Files.createDirectories(root.resolve(version));
        String jarName = "jk-" + version + ".jar";
        byte[] jar = fakeClientJar(tmp.resolve("fake-client"));
        Files.write(dir.resolve(jarName), jar);
        String hex =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
        byte[] sums = (hex + "  " + jarName + "\n").getBytes(StandardCharsets.US_ASCII);
        Files.write(dir.resolve("SHA256SUMS"), sums);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(sums);
        Files.writeString(dir.resolve("SHA256SUMS.sig"), Base64.getEncoder().encodeToString(signature.sign()) + "\n");
    }

    /** A runnable jar standing in for the JVM client, compiled from source here. */
    private static byte[] fakeClientJar(Path work) throws Exception {
        Path src = Files.createDirectories(work.resolve("src")).resolve("FakeClient.java");
        Files.writeString(src, """
                import java.nio.file.*;
                public class FakeClient {
                    public static void main(String[] args) throws Exception {
                        if (args.length == 2 && args[0].equals("self") && args[1].equals("write-launcher")) {
                            Path home = Path.of(System.getenv("JK_HOME"));
                            Files.createDirectories(home.resolve("bin"));
                            Files.writeString(home.resolve("bin/jk.bat"), "@echo jvm jk: %*\\r\\n");
                            Path sh = home.resolve("bin/jk");
                            Files.writeString(sh, "#!/bin/sh\\necho \\"jvm jk: $*\\"\\n");
                            sh.toFile().setExecutable(true);
                            Files.writeString(home.resolve("launchers-written"), "x",
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        }
                    }
                }
                """);
        Path classes = Files.createDirectories(work.resolve("classes"));
        var javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac).as("the test JVM is a JDK").isNotNull();
        assertThat(javac.run(null, null, null, "-d", classes.toString(), src.toString()))
                .isZero();
        var bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeClient");
        try (var out = new JarOutputStream(bytes, manifest)) {
            out.putNextEntry(new JarEntry("FakeClient.class"));
            out.write(Files.readAllBytes(classes.resolve("FakeClient.class")));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
