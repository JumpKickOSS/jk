// SPDX-License-Identifier: Apache-2.0
package demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Prints the Java line count that {@code .jk/after-resources.groovy} generated at build time. */
public final class App {
    private static final String RESOURCE = "/line-count.txt";

    public static void main(String[] args) throws IOException {
        System.out.println("Line Count: " + lineCount());
    }

    /** Reads the generated resource off the classpath. */
    static int lineCount() throws IOException {
        try (InputStream in = App.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        RESOURCE + " is not on the classpath: build logic under .jk/ did not run");
            }
            return Integer.parseInt(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
    }

    private App() {}
}
