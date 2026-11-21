// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.guard.extract.FactsIndexing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A helper or fixture compiled beside the tests is indexed for its tags but is never ranked by
 * {@code --affected}: only a class JUnit would find tests in is a candidate. The class files are
 * written here, so none of them is a test of this module.
 */
class TestClassIndexRunsTestsTest {

    private static final String TEST = "Lorg/junit/jupiter/api/Test;";
    private static final String PRODUCTION = "com.acme.Orders";

    @Test
    void only_classes_junit_finds_tests_in_run_tests(@TempDir Path tmp) throws Exception {
        Path testClasses = writeTree(tmp);

        Map<String, Boolean> fromClasses = runsTests(testClasses);
        assertThat(fromClasses)
                .containsEntry("com.acme.OrdersTest", true)
                .containsEntry("com.acme.OrdersInheritsTest", true)
                .containsEntry("com.acme.OrdersNestedTest", true)
                .containsEntry("com.acme.OrdersComposedTest", true)
                .containsEntry("com.acme.OrdersSpec", true)
                .containsEntry("com.acme.OrdersVendorTest", true)
                .containsEntry("com.acme.OrdersFixtures", false)
                .containsEntry("com.acme.AbstractOrdersTest", false)
                .containsEntry("com.acme.OrdersHelpersKt", false);

        FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(tmp.resolve("target"), "test"));
        assertThat(TestClassIndex.currentFacts(testClasses)).isPresent();
        assertThat(runsTests(testClasses))
                .as("the guard lane's facts index judges the same classes")
                .isEqualTo(fromClasses);
    }

    @Test
    void an_edited_helper_importing_a_changed_type_is_not_ranked(@TempDir Path tmp) throws Exception {
        Path testClasses = writeTree(tmp);
        List<TestClassIndex.Entry> scanned = TestClassIndex.scan(testClasses, Set.of(PRODUCTION), List.of());

        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                tmp.resolve("module"),
                "com.acme:module",
                tmp,
                TestSelection.DEFAULT,
                List.of(
                        "module/src/main/java/com/acme/Orders.java",
                        "module/src/test/java/com/acme/OrdersFixtures.java",
                        "module/src/test/java/com/acme/OrdersTest.java"),
                Map.of(),
                Map.of(),
                List.of(),
                scanned,
                Set.of(PRODUCTION),
                List.of()));

        assertThat(r.refused()).isFalse();
        assertThat(r.classNames())
                .as("the edited test, the importer and the name match rank; the helper does not")
                .contains("com.acme.OrdersTest", "com.acme.OrdersComposedTest")
                .doesNotContain("com.acme.OrdersFixtures", "com.acme.AbstractOrdersTest", "com.acme.OrdersHelpersKt");
    }

    private static Map<String, Boolean> runsTests(Path testClasses) throws Exception {
        Map<String, Boolean> out = new HashMap<>();
        for (TestClassIndex.Entry e : TestClassIndex.scan(testClasses, Set.of(PRODUCTION), List.of()))
            out.put(e.className(), e.runsTests());
        return out;
    }

    /** A test tree holding tests of every shape JUnit discovers, and helpers it does not. */
    private static Path writeTree(Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("target/classes/test"));
        String orders = "Lcom/acme/Orders;";
        write(root, type("com/acme/OrdersTest", Opcodes.ACC_PUBLIC, "java/lang/Object", null, TEST, orders));
        write(
                root,
                type(
                        "com/acme/AbstractOrdersTest",
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "java/lang/Object",
                        null,
                        TEST,
                        null));
        write(
                root,
                type(
                        "com/acme/OrdersInheritsTest",
                        Opcodes.ACC_PUBLIC,
                        "com/acme/AbstractOrdersTest",
                        null,
                        null,
                        null));
        write(root, type("com/acme/OrdersNestedTest", Opcodes.ACC_PUBLIC, "java/lang/Object", null, null, null));
        write(
                root,
                type("com/acme/OrdersNestedTest$Refunds", Opcodes.ACC_PUBLIC, "java/lang/Object", null, TEST, null));
        write(
                root,
                type(
                        "com/acme/OrdersCase",
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                        "java/lang/Object",
                        TEST,
                        null,
                        null));
        write(
                root,
                type(
                        "com/acme/OrdersComposedTest",
                        Opcodes.ACC_PUBLIC,
                        "java/lang/Object",
                        null,
                        "Lcom/acme/OrdersCase;",
                        orders));
        write(root, type("com/acme/OrdersSpec", Opcodes.ACC_PUBLIC, "spock/lang/Specification", null, null, null));
        write(root, type("com/acme/OrdersVendorTest", Opcodes.ACC_PUBLIC, "com/vendor/TestBase", null, null, null));
        write(
                root,
                type(
                        "com/acme/OrdersFixtures",
                        Opcodes.ACC_PUBLIC,
                        "java/lang/Object",
                        null,
                        "Lorg/jetbrains/annotations/NotNull;",
                        orders));
        write(
                root,
                type(
                        "com/acme/OrdersHelpersKt",
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                        "java/lang/Object",
                        "Lkotlin/Metadata;",
                        null,
                        orders));
        return root;
    }

    /**
     * One class file: {@code classAnnotation} on the class, {@code methodAnnotation} on its one
     * method, and a field of type {@code fieldType}; each may be absent.
     */
    private static byte[] type(
            String name,
            int access,
            String superName,
            @Nullable String classAnnotation,
            @Nullable String methodAnnotation,
            @Nullable String fieldType) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, access, name, null, superName, null);
        if (classAnnotation != null) cw.visitAnnotation(classAnnotation, true).visitEnd();
        if (fieldType != null)
            cw.visitField(Opcodes.ACC_PRIVATE, "orders", fieldType, null, null).visitEnd();
        if ((access & Opcodes.ACC_INTERFACE) == 0) {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "check", "()V", null, null);
            if (methodAnnotation != null)
                m.visitAnnotation(methodAnnotation, true).visitEnd();
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 1);
            m.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void write(Path root, byte[] bytes) throws Exception {
        String name = new ClassReader(bytes).getClassName();
        Path to = root.resolve(name + ".class");
        Files.createDirectories(to.getParent());
        Files.write(to, bytes);
    }
}
