// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A JDK home that jk's own discovery accepts, for tests that need an installed JDK without one.
 *
 * <p>Discovery requires {@code bin/java}, {@code bin/javac} and a {@code release} file — and on
 * Windows those binaries are {@code java.exe} / {@code javac.exe}. Hand-rolled fixtures wrote the
 * POSIX names, so every one of them was invisible to discovery on Windows: {@code jk jdk list}
 * showed the seeded JDK as {@code available / download} rather than {@code installed}, and a dozen
 * tests failed for a reason that had nothing to do with what they were testing.
 */
public final class FakeJdk {

    private FakeJdk() {}

    /** Adoptium-flavoured fake at {@code home}; {@code version} lands in the release file. */
    public static Path create(Path home, String version) throws IOException {
        return create(home, version, "Eclipse Adoptium");
    }

    /** As {@link #create(Path, String)} with an explicit {@code IMPLEMENTOR}. */
    public static Path create(Path home, String version, String implementor) throws IOException {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.writeString(bin.resolve(exe("java")), "#!/fake");
        Files.writeString(bin.resolve(exe("javac")), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n");
        return home;
    }

    /** The platform's spelling of a JDK launcher — the whole point of this class. */
    public static String exe(String tool) {
        return Os.isWindows() ? tool + ".exe" : tool;
    }
}
