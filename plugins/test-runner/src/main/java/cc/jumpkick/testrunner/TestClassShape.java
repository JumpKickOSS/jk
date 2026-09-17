// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether a class file is one the JUnit Platform would have admitted as a test class, read from
 * its bytes without loading it: a concrete class that declares a test method or a test annotation
 * (Jupiter's, JUnit 4's, TestNG's, or a project annotation composed of one), extends a class that
 * does, or extends a framework's specification base. The probe over the classes discovery dropped
 * keeps to these, so a helper under the test root that cannot load — a fixture compiled against a
 * dependency absent at test time — is not a failed run.
 */
final class TestClassShape {

    /** Annotation descriptors that make a class a test class when it, or one of its methods, wears one. */
    static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/params/ParameterizedTest;",
            "Lorg/junit/jupiter/params/ParameterizedClass;",
            "Lorg/junit/platform/commons/annotation/Testable;",
            "Lorg/junit/platform/suite/api/Suite;",
            "Lorg/junit/Test;",
            "Lorg/testng/annotations/Test;");

    /** Supertypes whose concrete subclasses are specifications with no annotation of their own. */
    static final Set<String> TEST_BASES = Set.of("spock/lang/Specification");

    /** Kotest's spec styles all live under this package. */
    static final String KOTEST_SPECS = "io/kotest/core/spec/";

    /** Supertypes followed before a class is judged not a test class. */
    private static final int SUPER_DEPTH = 8;

    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_ANNOTATION = 0x2000;

    /** What a class file says about itself: its name, supertype, access flags and every constant-pool string. */
    record Facts(String name, @Nullable String superName, int access, Set<String> strings) {

        boolean concrete() {
            return (access & (ACC_INTERFACE | ACC_ABSTRACT)) == 0;
        }

        boolean annotation() {
            return (access & ACC_ANNOTATION) != 0;
        }

        /** True when the pool names a test annotation: the class or a member wears it. */
        boolean declaresTest() {
            for (String a : TEST_ANNOTATIONS) {
                if (strings.contains(a)) return true;
            }
            return false;
        }
    }

    private final Path root;
    private final ClassLoader loader;

    /** {@code root} holds the class files judged; {@code loader} supplies supertypes and annotations beyond it. */
    TestClassShape(Path root, ClassLoader loader) {
        this.root = root;
        this.loader = loader;
    }

    /**
     * True when the class file for {@code binaryName} under the root is test-shaped. A file that
     * cannot be read or parsed is: nothing proves it a helper, and the failure it caused is kept.
     */
    boolean isTestClass(String binaryName) {
        Facts facts = facts(binaryName.replace('.', '/'));
        if (facts == null) return true;
        if (!facts.concrete()) return false;
        return testShaped(facts, 0);
    }

    private boolean testShaped(Facts facts, int depth) {
        if (facts.declaresTest() || composed(facts)) return true;
        String sup = facts.superName();
        if (sup == null || "java/lang/Object".equals(sup) || depth >= SUPER_DEPTH) return false;
        if (TEST_BASES.contains(sup) || sup.startsWith(KOTEST_SPECS)) return true;
        Facts parent = facts(sup);
        return parent != null && testShaped(parent, depth + 1);
    }

    /**
     * True when a type the class names is an annotation composed of a test annotation — a project's
     * own {@code @IntegrationTest} that is itself {@code @Test}. Platform types are never that.
     */
    private boolean composed(Facts facts) {
        for (String s : facts.strings()) {
            if (s.length() < 3 || s.charAt(0) != 'L' || s.indexOf(';') != s.length() - 1) continue;
            String internal = s.substring(1, s.length() - 1);
            if (platformType(internal)) continue;
            Facts a = facts(internal);
            if (a != null && a.annotation() && a.declaresTest()) return true;
        }
        return false;
    }

    private static boolean platformType(String internal) {
        return internal.startsWith("java/")
                || internal.startsWith("javax/")
                || internal.startsWith("jdk/")
                || internal.startsWith("kotlin/")
                || internal.startsWith("scala/")
                || internal.startsWith("groovy/");
    }

    /** The class file for an internal name — under the root, else on the loader — or {@code null}. */
    private @Nullable Facts facts(String internalName) {
        byte[] bytes = bytes(internalName);
        if (bytes == null) return null;
        try {
            return read(bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private byte @Nullable [] bytes(String internalName) {
        String resource = internalName + ".class";
        try {
            Path file = root.resolve(resource);
            if (Files.isRegularFile(file)) return Files.readAllBytes(file);
            try (InputStream in = loader.getResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@code bytes} as {@link Facts}: the header, the constant pool, then the class's own three words. */
    static Facts read(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNames = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classNames[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    i++;
                }
                default -> throw new IOException("constant pool tag " + tag);
            }
        }
        int access = in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        int superClass = in.readUnsignedShort();
        Set<String> strings = new HashSet<>();
        for (String s : utf8) {
            if (s != null) strings.add(s);
        }
        String name = className(utf8, classNames, thisClass);
        String sup = superClass == 0 ? null : className(utf8, classNames, superClass);
        return new Facts(name, sup, access, strings);
    }

    private static String className(String[] utf8, int[] classNames, int index) throws IOException {
        if (index <= 0 || index >= classNames.length) throw new IOException("class index " + index);
        int nameIndex = classNames[index];
        String name = nameIndex > 0 && nameIndex < utf8.length ? utf8[nameIndex] : null;
        if (name == null) throw new IOException("class name index " + nameIndex);
        return name;
    }
}
