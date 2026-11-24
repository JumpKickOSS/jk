// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

/**
 * A marked class still verifies and computes what it did, through every construct whose offsets
 * the mark moves (a loop back to the first instruction, a handler, both switches, an object built
 * under a branch, a lambda, a constructor before its super call), and names itself when its code
 * runs in a window, not before.
 */
class EntryMarksTest {

    /** Every construct the mark has to move an offset for. */
    public static final class Busy {
        static final List<String> WORDS = List.of("a", "bb", "ccc");
        private final long base;

        public Busy(long base) {
            this(base > 0 ? base : -base, 0.5d);
        }

        private Busy(long base, double half) {
            super();
            this.base = base + (long) half;
        }

        public static int loop(int n) {
            int i = 0;
            while (true) {
                if (++i >= n) return i;
            }
        }

        public static int caught(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        public static int switches(int k, String s) {
            int dense =
                    switch (k) {
                        case 0 -> 10;
                        case 1 -> 11;
                        case 2 -> 12;
                        default -> 19;
                    };
            int sparse =
                    switch (k * 1000) {
                        case 0 -> 1;
                        case 7000 -> 2;
                        default -> 3;
                    };
            return dense + sparse + s.length();
        }

        public static StringBuilder built(boolean flag) {
            return new StringBuilder(flag ? "yes" : "no");
        }

        public long lambda() {
            IntSupplier s = () -> WORDS.size() + (int) base;
            return s.getAsInt() + 1_000_000_000_000L;
        }
    }

    @Test
    void a_marked_class_verifies_runs_as_compiled_and_names_itself_when_its_code_runs() throws Exception {
        int id = ClassUses.id(Busy.class.getName());
        byte[] marked = Objects.requireNonNull(EntryMarks.mark(bytesOf(Busy.class), id));
        Class<?> busy = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(Busy.class.getName(), marked, 0, marked.length);
            }
        }.define();

        ClassUses.opened();
        assertThat(ClassUses.namedInWindow(id)).as("nothing of it ran yet").isFalse();
        assertThat(call(busy, "loop", 5)).isEqualTo(5);
        assertThat(ClassUses.namedInWindow(id))
                .as("its code ran in this window")
                .isTrue();
        assertThat(call(busy, "caught", "12")).isEqualTo(12);
        assertThat(call(busy, "caught", "x")).isEqualTo(-1);
        assertThat(call(busy, "switches", 1, "ab")).isEqualTo(11 + 3 + 2);
        assertThat(call(busy, "switches", 7, "")).isEqualTo(19 + 2);
        assertThat(call(busy, "built", true).toString()).isEqualTo("yes");
        Object instance = busy.getConstructor(long.class).newInstance(-4L);
        Method lambda = busy.getMethod("lambda");
        assertThat(lambda.invoke(instance)).isEqualTo(1_000_000_000_007L);

        ClassUses.opened();
        assertThat(ClassUses.namedInWindow(id))
                .as("a new window names it again only when it runs")
                .isFalse();
        lambda.invoke(instance);
        assertThat(ClassUses.namedInWindow(id)).isTrue();
    }

    @Test
    void a_class_that_cannot_hold_the_mark_is_named_in_every_window() {
        assertThat(EntryMarks.mark(new byte[] {(byte) 0xca, (byte) 0xfe}, 0)).isNull();
        String name = "demo.Unmarkable";
        ClassUses.unmarked(name);
        int id = ClassUses.id(name);
        ClassUses.opened();
        assertThat(ClassUses.namedInWindow(id)).isTrue();
    }

    private static Object call(Class<?> c, String name, Object... args) throws Exception {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)) return m.invoke(null, args);
        }
        throw new NoSuchMethodException(name);
    }

    private static byte[] bytesOf(Class<?> c) throws Exception {
        String path = c.getName().replace('.', '/') + ".class";
        try (InputStream in =
                Objects.requireNonNull(EntryMarksTest.class.getClassLoader().getResourceAsStream(path))) {
            return in.readAllBytes();
        }
    }
}
