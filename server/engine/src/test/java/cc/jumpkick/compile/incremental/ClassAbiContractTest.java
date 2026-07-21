// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ticket-1029 — ABI contract: method body edits do not change {@link ClassAbi#hash}; adding a
 * public method does.
 */
class ClassAbiContractTest {

    @Test
    void body_edit_keeps_abi_hash_public_method_changes_it(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);

        byte[] v1 = compile(dir, classes, "demo.A", """
                package demo;
                public class A {
                  public int n() { return 1; }
                }
                """);
        String h1 = ClassAbi.hash(v1);

        byte[] vBody = compile(dir, classes, "demo.A", """
                package demo;
                public class A {
                  public int n() { return 2; }
                }
                """);
        assertEquals(h1, ClassAbi.hash(vBody), "body-only edit must preserve ABI hash");

        byte[] vApi = compile(dir, classes, "demo.A", """
                package demo;
                public class A {
                  public int n() { return 2; }
                  public int m() { return 3; }
                }
                """);
        assertNotEquals(h1, ClassAbi.hash(vApi), "new public method must change ABI hash");
    }

    private static byte[] compile(Path dir, Path classes, String fqcn, String source) throws IOException {
        String rel = fqcn.replace('.', '/') + ".java";
        Path src = dir.resolve("src").resolve(rel);
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", classes.toString(), src.toString());
        if (rc != 0) throw new IllegalStateException("javac failed");
        Path classFile = classes.resolve(fqcn.replace('.', '/') + ".class");
        return Files.readAllBytes(classFile);
    }
}
