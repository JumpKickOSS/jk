// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.TestClassRule;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether a class file is one the JUnit Platform would have admitted as a test class, read from
 * its bytes without loading it, by {@link TestClassRule}: its supertypes and composed annotations
 * are read from the root, else from the loader, and its nested classes from the root. The probe
 * over the classes discovery dropped keeps to these, so a helper under the test root that cannot
 * load — a fixture compiled against a dependency absent at test time — is not a failed run.
 */
final class TestClassShape {

    /**
     * What a class file says about itself: its name, supertypes and access flags as internal names,
     * and every constant-pool string. The pool names every annotation the class and its members
     * wear, so a type descriptor in it stands for an annotation of either.
     */
    record Facts(String name, @Nullable String superName, List<String> interfaces, int access, Set<String> strings) {

        /** These facts as the rule reads them: every type descriptor in the pool a candidate annotation. */
        TestClassRule.Type type() {
            List<String> supertypes = new ArrayList<>();
            if (superName != null) supertypes.add(binary(superName));
            for (String i : interfaces) supertypes.add(binary(i));
            Set<String> annotations = new LinkedHashSet<>();
            for (String s : strings) {
                if (s.length() >= 3 && s.charAt(0) == 'L' && s.indexOf(';') == s.length() - 1) {
                    annotations.add(binary(s.substring(1, s.length() - 1)));
                }
            }
            return new TestClassRule.Type(binary(name), access, supertypes, annotations, annotations);
        }
    }

    private final Path root;
    private final ClassLoader loader;
    private final Map<String, Optional<TestClassRule.Type>> read = new HashMap<>();

    /** {@code root} holds the class files judged; {@code loader} supplies supertypes and annotations beyond it. */
    TestClassShape(Path root, ClassLoader loader) {
        this.root = root;
        this.loader = loader;
    }

    /**
     * True when the class file for {@code binaryName} under the root is test-shaped. A file that
     * cannot be read or parsed is: nothing proves it a helper, and the failure it caused is kept.
     * Any other type that cannot be read declares no test.
     */
    boolean isTestClass(String binaryName) {
        TestClassRule.Type type = type(binaryName);
        if (type == null) return true;
        return TestClassRule.runsTests(type, nested(binaryName), this::type, false);
    }

    /** The classes declared inside {@code binaryName}, at any depth, from the class files beside it. */
    private List<TestClassRule.Type> nested(String binaryName) {
        Path file = root.resolve(binaryName.replace('.', '/') + ".class");
        Path dir = file.getParent();
        String prefix = file.getFileName().toString().replace(".class", "$");
        List<TestClassRule.Type> out = new ArrayList<>();
        if (dir == null) return out;
        try {
            PathUtil.forEachChild(dir, (f, attrs) -> {
                String n = String.valueOf(f.getFileName());
                if (!attrs.isRegularFile() || !n.startsWith(prefix) || !n.endsWith(".class")) return true;
                TestClassRule.Type t =
                        type(binaryName + n.substring(prefix.length() - 1, n.length() - ".class".length()));
                if (t != null) out.add(t);
                return true;
            });
        } catch (IOException | RuntimeException unreadable) {
            return out;
        }
        return out;
    }

    /** The type named {@code binaryName}, under the root or else on the loader, read once; {@code null} when unreadable. */
    private TestClassRule.@Nullable Type type(String binaryName) {
        Optional<TestClassRule.Type> known = read.get(binaryName);
        if (known == null) {
            known = Optional.ofNullable(readType(binaryName));
            read.put(binaryName, known);
        }
        return known.orElse(null);
    }

    private TestClassRule.@Nullable Type readType(String binaryName) {
        byte[] bytes = bytes(binaryName.replace('.', '/'));
        if (bytes == null) return null;
        try {
            return read(bytes).type();
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

    private static String binary(String internalName) {
        return internalName.replace('/', '.');
    }

    /** {@code bytes} as {@link Facts}: the header, the constant pool, then the class's own words and its interfaces. */
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
        int interfaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < interfaceCount; i++) interfaces.add(className(utf8, classNames, in.readUnsignedShort()));
        Set<String> strings = new HashSet<>();
        for (String s : utf8) {
            if (s != null) strings.add(s);
        }
        String name = className(utf8, classNames, thisClass);
        String sup = superClass == 0 ? null : className(utf8, classNames, superClass);
        return new Facts(name, sup, interfaces, access, strings);
    }

    private static String className(String[] utf8, int[] classNames, int index) throws IOException {
        if (index <= 0 || index >= classNames.length) throw new IOException("class index " + index);
        int nameIndex = classNames[index];
        String name = nameIndex > 0 && nameIndex < utf8.length ? utf8[nameIndex] : null;
        if (name == null) throw new IOException("class name index " + nameIndex);
        return name;
    }
}
