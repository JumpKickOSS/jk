// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The jk version of this build, in a non-view module so any layer can read it. */
public final class JkVersion {
    private JkVersion() {}

    /**
     * The effective project version of the build that produced this jar: {@code 1.2.3}, or {@code
     * 1.2.3-SNAPSHOT} under {@code jk snapshot}. The build stamps it into {@value #RESOURCE} from
     * the root {@code jk.toml}; nothing writes it by hand.
     */
    public static final String VERSION = read();

    private static final String RESOURCE = "jk-version.properties";

    private static String read() {
        try (InputStream in = JkVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not beside JkVersion on the classpath: the build"
                        + " stamps it from the project version");
            }
            Properties stamped = new Properties();
            stamped.load(in);
            String version = stamped.getProperty("version", "").strip();
            if (version.isEmpty() || version.contains("${")) {
                throw new IllegalStateException(RESOURCE + " carries no version: " + version);
            }
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
