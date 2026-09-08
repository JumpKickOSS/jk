// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.androidsdk.AndroidSdkInstaller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code sdk-component} step-dependencies under the managed SDK root (Android SDK today;
 * vocabulary is provider-neutral). Pseudo-component {@code root} is the SDK root with no download.
 */
public final class SdkComponents {

    private SdkComponents() {}

    /** The installed revision of {@code component}, or null (root pseudo-component, not installed). */
    public static @Nullable String installedRevision(String component) {
        if ("root".equals(component)) return null;
        try {
            return AndroidSdk.resolve().installedRevision(component);
        } catch (IOException e) {
            return null;
        }
    }

    public static Path resolve(@Nullable String component, @Nullable String pathInside)
            throws IOException, InterruptedException {
        return resolve(component, pathInside, null);
    }

    public static Path resolve(@Nullable String component, @Nullable String pathInside, @Nullable String pinnedRevision)
            throws IOException, InterruptedException {
        AndroidSdk sdk = AndroidSdk.resolve();
        Path base;
        if ("root".equals(component)) {
            base = sdk.root();
        } else {
            base = new AndroidSdkInstaller(sdk).ensure(component, pinnedRevision);
        }
        if (pathInside == null || pathInside.isBlank()) return base;
        Path inside = base.resolve(pathInside).normalize();
        if (!inside.startsWith(base)) {
            throw new IOException("sdk-path escapes the component: " + pathInside);
        }
        if (!Files.exists(inside)) {
            throw new IOException(
                    "sdk component " + component + " has no " + pathInside + " (looked at " + inside + ")");
        }
        return inside;
    }
}
