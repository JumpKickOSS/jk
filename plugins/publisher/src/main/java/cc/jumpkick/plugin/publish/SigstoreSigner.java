// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import java.io.IOException;

/**
 * Produces a Sigstore Bundle (JSON) over artifact bytes ({@code .sigstore}). No {@code
 * dev.sigstore.*} types on the interface; production impl is {@link KeylessSigstoreSigner}.
 */
@FunctionalInterface
public interface SigstoreSigner {

    /** Sign the given bytes; return the Sigstore Bundle as JSON bytes. */
    byte[] signBundle(byte[] artifact) throws IOException;
}
