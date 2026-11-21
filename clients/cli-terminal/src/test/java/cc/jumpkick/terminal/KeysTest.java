// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KeysTest {
    @Test
    void arrowCsi() {
        byte[] csi = {0x1B, '[', 'A'};
        try (MemoryTerminal tty = Terminals.memory(new ByteArrayInputStream(csi), new ByteArrayOutputStream())) {
            assertThat(tty.readKey(Duration.ofSeconds(1))).contains(Key.Up.INSTANCE);
        }
    }

    @Test
    void bareEscWhenNoFollow() {
        try (MemoryTerminal tty =
                Terminals.memory(new ByteArrayInputStream(new byte[] {0x1B}), new ByteArrayOutputStream())) {
            assertThat(tty.readKey(Duration.ofMillis(80))).contains(Key.Escape.INSTANCE);
        }
    }

    @Test
    void unreadPreservesNextKeyAfterBareEsc() {
        byte[] in = {0x1B, 'x'};
        try (MemoryTerminal tty = Terminals.memory(new ByteArrayInputStream(in), new ByteArrayOutputStream())) {
            assertThat(tty.readKey(Duration.ofSeconds(1))).contains(Key.Escape.INSTANCE);
            assertThat(tty.readKey(Duration.ofSeconds(1))).contains(new Key.Char('x'));
        }
    }

    @Test
    void namedBytes() {
        assertThat(read(new byte[] {0x03})).contains(Key.CtrlC.INSTANCE);
        assertThat(read(new byte[] {0x0F})).contains(Key.CtrlO.INSTANCE);
        assertThat(read(new byte[] {0x18})).contains(Key.CtrlX.INSTANCE);
        assertThat(read(new byte[] {0x0A})).contains(Key.Enter.INSTANCE);
        assertThat(read(new byte[] {0x0D})).contains(Key.Enter.INSTANCE);
        assertThat(read(new byte[] {0x20})).contains(Key.Space.INSTANCE);
        assertThat(read(new byte[] {0x09})).contains(Key.Tab.INSTANCE);
        assertThat(read(new byte[] {0x7F})).contains(Key.Backspace.INSTANCE);
        assertThat(read(new byte[] {0x08})).contains(Key.Backspace.INSTANCE);
    }

    @Test
    void csiArrows() {
        assertThat(read(new byte[] {0x1B, '[', 'B'})).contains(Key.Down.INSTANCE);
        assertThat(read(new byte[] {0x1B, '[', 'C'})).contains(Key.Right.INSTANCE);
        assertThat(read(new byte[] {0x1B, '[', 'D'})).contains(Key.Left.INSTANCE);
    }

    /** Application cursor mode (DECCKM) sends arrows as SS3: {@code ESC O A}. */
    @Test
    void ss3Arrows() {
        assertThat(read(new byte[] {0x1B, 'O', 'A'})).contains(Key.Up.INSTANCE);
        assertThat(read(new byte[] {0x1B, 'O', 'B'})).contains(Key.Down.INSTANCE);
        assertThat(read(new byte[] {0x1B, 'O', 'C'})).contains(Key.Right.INSTANCE);
        assertThat(read(new byte[] {0x1B, 'O', 'D'})).contains(Key.Left.INSTANCE);
    }

    @Test
    void anSs3SequenceIsOneKeyAndLeavesTheNextIntact() {
        assertThat(readAll(new byte[] {0x1B, 'O', 'P', 'x'})).containsExactly(new Key.Unknown('P'), new Key.Char('x'));
    }

    /** Shift, Alt and Ctrl with an arrow: {@code ESC [ 1 ; <mod> A}, one key, nothing left behind. */
    @Test
    void modifiedCsiArrowsAreOneKey() {
        assertThat(readAll(new byte[] {0x1B, '[', '1', ';', '2', 'A', 'x'}))
                .containsExactly(Key.Up.INSTANCE, new Key.Char('x'));
        assertThat(readAll(new byte[] {0x1B, '[', '1', ';', '5', 'D'})).containsExactly(Key.Left.INSTANCE);
    }

    @Test
    void aTildeSequenceIsOneKeyAndLeavesTheNextIntact() {
        assertThat(readAll(new byte[] {0x1B, '[', '3', '~', 'x'}))
                .containsExactly(new Key.Unknown('~'), new Key.Char('x'));
        assertThat(readAll(new byte[] {0x1B, '[', '1', '5', ';', '2', '~', 'q'}))
                .containsExactly(new Key.Unknown('~'), new Key.Char('q'));
    }

    @Test
    void escThenOIsEscapeThenTheLetterWhenNothingFollows() {
        assertThat(readAll(new byte[] {0x1B, 'O'})).containsExactly(Key.Escape.INSTANCE, new Key.Char('O'));
    }

    @Test
    void keysIsPackagePrivate() {
        assertThat(Keys.class.getModifiers() & Modifier.PUBLIC).isZero();
    }

    /** Every key {@code bytes} decode to, in order. */
    private static List<Key> readAll(byte[] bytes) {
        List<Key> keys = new ArrayList<>();
        try (MemoryTerminal tty = Terminals.memory(new ByteArrayInputStream(bytes), new ByteArrayOutputStream())) {
            for (Optional<Key> k = tty.readKey(Duration.ofMillis(200));
                    k.isPresent();
                    k = tty.readKey(Duration.ofMillis(200))) {
                keys.add(k.get());
            }
        }
        return keys;
    }

    private static Optional<Key> read(byte[] bytes) {
        try (MemoryTerminal tty = Terminals.memory(new ByteArrayInputStream(bytes), new ByteArrayOutputStream())) {
            return tty.readKey(Duration.ofSeconds(1));
        }
    }
}
