// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.tool.NativeImageDriver;
import org.jspecify.annotations.Nullable;

/**
 * The engine code that runs native-image: {@link NativeImageDriver}, which spells the command line
 * around the args. A native image's key names this code as its producer rather than the whole
 * engine: GraalVM, the classpath, the args, the main class and the metadata are keyed by content,
 * so the image the reinstall linked under the previous engine restores under the engine it just
 * installed, and an edit to the driver links again.
 */
public final class NativeLinkCode {

    private NativeLinkCode() {}

    private static final String DRIVER = NativeImageDriver.class.getName().replace('.', '/');

    private static volatile @Nullable String cachedId;

    /** Whether the archive entry {@code entry} is the driver or one of its nested classes. */
    static boolean contains(String entry) {
        return entry.equals(DRIVER + ".class") || entry.startsWith(DRIVER + "$");
    }

    /** The code's identity in the running archive, 12 hex, or {@code ""} when there is no archive. */
    public static String id() {
        String local = cachedId;
        if (local != null) return local;
        synchronized (NativeLinkCode.class) {
            if (cachedId == null) cachedId = BuildIdentity.codeIdentity(NativeLinkCode::contains);
            return cachedId;
        }
    }
}
