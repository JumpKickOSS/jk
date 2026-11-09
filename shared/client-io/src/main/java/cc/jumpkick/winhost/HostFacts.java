// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** What the Windows checks read from the host; a fake one makes every row testable anywhere. */
public interface HostFacts {

    /** The volume a path lives on: its root ({@code C:\}) and filesystem ({@code NTFS}, {@code ReFS}). */
    record Volume(String root, String type) {}

    Registry registry();

    @Nullable
    String env(String name);

    /** The volume holding {@code path}; null when it cannot be read. */
    @Nullable
    Volume volume(Path path);

    /** git's {@code core.longpaths} in any scope, false when unset; null when git is missing or slow. */
    @Nullable
    Boolean gitLongPaths();

    /** This process's {@code os.arch}. */
    String processArch();

    /** The architecture of the JDK the engine runs on ({@code os.arch} spelling); null when unknown. */
    @Nullable
    String jdkArch();

    boolean isFile(Path path);

    /** The file's text, or null when it cannot be read. */
    @Nullable
    String read(Path path);

    /** The facts of this machine. */
    static HostFacts live() {
        return LiveHostFacts.INSTANCE;
    }
}
