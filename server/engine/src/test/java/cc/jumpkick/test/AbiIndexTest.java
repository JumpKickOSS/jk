// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The abi idx advances incrementally (JK-2610): compiled sources re-hash, the rest carry over. */
class AbiIndexTest {

    @Test
    void updated_rehashes_only_compiled_sources_and_drops_missing_classes(@TempDir Path module) throws Exception {
        Files.createDirectories(module.resolve("src/main/java/com/acme"));
        Files.writeString(module.resolve("jk.toml"), "group = \"g\"\nname = \"m\"\nversion = \"1\"\n");
        Path classes = module.resolve("target/classes/main");
        writeClass(classes, "com/acme/Foo.class", classBytes("com/acme/Foo"));

        var stale = new ClassAbi.Fingerprint("old-api");
        Map<String, ClassAbi.Fingerprint> previous = Map.of(
                "com.acme.Foo", stale, // recompiled below → re-hashed
                "com.acme.Kept", stale, // untouched → carried over verbatim
                "com.acme.Gone", stale); // recompiled but class file missing → dropped

        Map<String, ClassAbi.Fingerprint> out = AbiIndex.updated(
                previous,
                module,
                List.of(
                        module.resolve("src/main/java/com/acme/Foo.java"),
                        module.resolve("src/main/java/com/acme/Gone.java")),
                classes);

        assertThat(out.get("com.acme.Kept")).isSameAs(stale);
        assertThat(out).doesNotContainKey("com.acme.Gone");
        assertThat(out.get("com.acme.Foo")).isNotEqualTo(stale);
        assertThat(out.get("com.acme.Foo")).isEqualTo(ClassAbi.of(classBytes("com/acme/Foo")));
    }

    @Test
    void load_and_write_round_trip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("target/incremental/main-abi.idx");
        Map<String, ClassAbi.Fingerprint> rows = Map.of("com.acme.Foo", new ClassAbi.Fingerprint("aaaa"));
        AbiIndex.write(file, rows);
        assertThat(AbiIndex.load(file)).isEqualTo(rows);
    }

    @Test
    void named_nested_api_change_flips_the_owner_fingerprint(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        writeClass(classes, "com/acme/Foo.class", classBytes("com/acme/Foo"));
        writeClass(classes, "com/acme/Foo$Builder.class", classBytesWithMethod("com/acme/Foo$Builder", "a"));
        var before = AbiIndex.scanClasses(classes);

        writeClass(classes, "com/acme/Foo$Builder.class", classBytesWithMethod("com/acme/Foo$Builder", "b"));
        var after = AbiIndex.scanClasses(classes);

        assertThat(before).containsOnlyKeys("com.acme.Foo");
        assertThat(ClassAbi.classify(before.get("com.acme.Foo"), after.get("com.acme.Foo")))
                .isEqualTo(ClassAbi.Kind.ABI);
    }

    @Test
    void anonymous_classes_do_not_move_the_owner_fingerprint(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        writeClass(classes, "com/acme/Foo.class", classBytes("com/acme/Foo"));
        var before = AbiIndex.scanClasses(classes);
        writeClass(classes, "com/acme/Foo$1.class", classBytes("com/acme/Foo$1"));
        var after = AbiIndex.scanClasses(classes);
        assertThat(after.get("com.acme.Foo")).isEqualTo(before.get("com.acme.Foo"));
    }

    @Test
    void named_nested_detection() {
        assertThat(AbiIndex.isNamedNested("$Builder")).isTrue();
        assertThat(AbiIndex.isNamedNested("$Builder$Inner")).isTrue();
        assertThat(AbiIndex.isNamedNested("$1")).isFalse();
        assertThat(AbiIndex.isNamedNested("$Builder$1")).isFalse();
        assertThat(AbiIndex.isNamedNested("$1$Local")).isFalse();
    }

    private static byte[] classBytesWithMethod(String internalName, String method) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeClass(Path dir, String rel, byte[] bytes) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
    }

    private static byte[] classBytes(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
