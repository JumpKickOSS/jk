// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A javac plugin that records the options javac handed it: the first is the file to write, the
 * rest are written one per line. Registered under {@code META-INF/services} in the test resources,
 * so a processor path holding this module's test output makes it discoverable as {@code JkProbe}.
 */
public final class ProbePlugin implements Plugin {

    public static final String NAME = "JkProbe";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        try {
            Files.write(Path.of(args[0]), Arrays.asList(args).subList(1, args.length), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
