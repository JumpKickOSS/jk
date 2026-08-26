// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@link ImageExtension} surface: assembles an image from the finished module (an OCI image via
 * Jib, a native image). Consumes the main artifact + runtime closure ({@link TerminalContext}); the
 * image settings (base, registry, tag, platform, ports, env, labels, mode) ride the generic {@link
 * #config()} table.
 */
public interface ImageContext extends TerminalContext {

    /** The module's compiled classes dir, when the image layers classes directly (no jar), else empty. */
    Optional<Path> classesDir();

    /**
     * A resolved registry credential the engine shipped on the spec's {@code secret} lines; never
     * echoed back. An image goal pulls a base image and may push the result, and both legs can face
     * a registry that refuses anonymous requests — same channel {@link PublishContext#secret} uses
     * for signing credentials, for the same reason: argv is world-readable.
     */
    Optional<String> secret(String key);
}
