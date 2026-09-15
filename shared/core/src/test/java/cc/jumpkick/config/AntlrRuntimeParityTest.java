// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.RuntimeMetaData;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

/**
 * The ANTLR runtime jk ships is the one tomlj's generated TOML parser was built with.
 *
 * <p>The generated lexer checks the runtime's version against the version that generated it the
 * first time it loads and prints two warnings to stderr when they differ — on every jk command
 * that reads a manifest, including the installer's engine warm-up on the user's terminal. {@code
 * shared/core/jk.toml} pins the runtime to the generating version; this holds the lock to that
 * pin and reads the generating version out of the lexer itself, so a re-lock that floats the
 * runtime fails here instead of on the terminal.
 */
class AntlrRuntimeParityTest {

    private static final Path REPO = RepoRoot.find(AntlrRuntimeParityTest.class);

    /** The runtime's row in the lock and the version it resolved to. */
    private static final Pattern LOCKED =
            Pattern.compile("name\\s*=\\s*\"org\\.antlr:antlr4-runtime:jar:\"\\s*\\n\\s*version\\s*=\\s*\"([^\"]+)\"");

    /** The one version-shaped literal a generated lexer carries: the tool version handed to the check. */
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    /** The generated lexer: its static initializer is where the version check runs. */
    private static final String GENERATED_LEXER = "org/tomlj/internal/TomlLexer.class";

    @Test
    void the_locked_antlr_runtime_is_the_one_the_toml_parser_was_generated_with() throws Exception {
        Matcher locked = LOCKED.matcher(Files.readString(REPO.resolve("jk-lock.toml")));
        assertThat(locked.find()).as("the lock carries the ANTLR runtime").isTrue();
        assertThat(locked.group(1))
                .as("the lock and the runtime on this classpath agree")
                .isEqualTo(RuntimeMetaData.VERSION);

        assertThat(versionLiterals(GENERATED_LEXER))
                .as("the version the lexer was generated with is the runtime's")
                .containsExactly(RuntimeMetaData.VERSION);
    }

    /** Every version-shaped string constant in the class at {@code resource} on tomlj's classpath. */
    private static Set<String> versionLiterals(String resource) throws IOException {
        Set<String> versions = new LinkedHashSet<>();
        try (InputStream in =
                        Objects.requireNonNull(Toml.class.getClassLoader().getResourceAsStream(resource), resource);
                DataInputStream data = new DataInputStream(in)) {
            data.readInt(); // magic
            data.readUnsignedShort(); // minor
            data.readUnsignedShort(); // major
            int count = data.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                int tag = data.readUnsignedByte();
                switch (tag) {
                    case 1 -> { // Utf8
                        String text = data.readUTF();
                        if (VERSION.matcher(text).matches()) versions.add(text);
                    }
                    case 3, 4 -> data.readInt(); // Integer, Float
                    case 5, 6 -> { // Long, Double take two slots
                        data.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> data.readUnsignedShort(); // Class, String, MethodType, Module, Package
                    case 9, 10, 11, 12, 17, 18 -> data.readInt(); // refs, NameAndType, Dynamic, InvokeDynamic
                    case 15 -> { // MethodHandle
                        data.readUnsignedByte();
                        data.readUnsignedShort();
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag + " in " + resource);
                }
            }
        }
        return versions;
    }
}
