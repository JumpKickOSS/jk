// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.androidsdk.AndroidSdkInstaller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves {@code sdk-component} step-dependencies under the managed SDK root (Android SDK today;
 * vocabulary is provider-neutral). Pseudo-component {@code root} is the SDK root with no download.
 */
final class SdkComponents {

    private SdkComponents() {}

    /** The installed revision of {@code component}, or null (root pseudo-component, not installed). */
    static String installedRevision(String component) {
        if ("root".equals(component)) return null;
        try {
            return AndroidSdk.resolve().installedRevision(component);
        } catch (IOException e) {
            return null;
        }
    }

    static Path resolve(String component, String pathInside) throws IOException, InterruptedException {
        return resolve(component, pathInside, null);
    }

    static Path resolve(String component, String pathInside, String pinnedRevision)
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
