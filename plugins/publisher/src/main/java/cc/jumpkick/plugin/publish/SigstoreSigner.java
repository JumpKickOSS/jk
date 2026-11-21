// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Produces a Sigstore Bundle (JSON) over an artifact file ({@code .sigstore}). No {@code
 * dev.sigstore.*} types on the interface; production impl is {@link KeylessSigstoreSigner}.
 */
@FunctionalInterface
public interface SigstoreSigner {

    /** Sign the file's contents; return the Sigstore Bundle as JSON bytes. */
    byte[] signBundle(Path artifact) throws IOException;
}
