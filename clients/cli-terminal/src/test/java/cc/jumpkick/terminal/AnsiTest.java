// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPool;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnsiTest {
    @Test
    void oscConstructorsAlwaysEmit() {
        assertThat(Ansi.taskbarProgress(40)).contains("9;4;1;40");
        assertThat(Ansi.TASKBAR_INDETERMINATE).isEqualTo(Ansi.taskbarIndeterminate());
        assertThat(Ansi.windowTitle("hi")).contains("hi").doesNotContain("\n");
        assertThat(Ansi.desktopNotify("JumpKick Build", "hello"))
                .contains("i=jk")
                .contains("hello");
    }

    /**
     * {@code Ansi} is the escape-sequence alphabet: it must stay linkable on its own, with no
     * policy type behind it. Asserted against the <em>compiled</em> class, not the source text —
     * the constant pool names every type the class actually references, so a new
     * {@code SessionContext.current()} (or any other jk collaborator) fails here even if the
     * import is written as a fully-qualified name.
     */
    @Test
    void compiles_against_the_jdk_alone() throws Exception {
        Set<String> foreign = new LinkedHashSet<>();
        for (String referenced : referencedTypes(Ansi.class)) {
            if (referenced.startsWith("java/") || referenced.startsWith("cc/jumpkick/terminal/Ansi")) continue;
            foreign.add(referenced);
        }
        assertThat(foreign)
                .as("Ansi must reference only JDK types; policy belongs in :cli")
                .isEmpty();
    }

    /** Internal names of every class the constant pool of {@code type} names. */
    private static Set<String> referencedTypes(Class<?> type) throws Exception {
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertThat(in)
                    .as("compiled %s on the test classpath", type.getSimpleName())
                    .isNotNull();
            bytes = in.readAllBytes();
        }
        ClassModel model = ClassFile.of().parse(bytes);
        ConstantPool pool = model.constantPool();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 1; i < pool.size(); i++) {
            PoolEntry entry;
            try {
                entry = pool.entryByIndex(i);
            } catch (IllegalArgumentException wideSlot) {
                continue; // long/double occupy two slots; the second is not addressable
            }
            if (entry instanceof ClassEntry ce) names.add(ce.asInternalName());
        }
        return names;
    }
}
