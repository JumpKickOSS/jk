// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.net.PasswordAuthentication;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * A proxy URL's {@code user:password@} as the credential it names. The spelled form is split at its
 * first {@code :} and each half is percent-decoded after, so an escaped {@code %3A} in the user name
 * stays part of the name rather than becoming the separator. Every place jk reads a proxy's user
 * info goes through here.
 */
public final class ProxyUserInfo {

    private ProxyUserInfo() {}

    /** The credential {@code rawUserInfo} (as {@link java.net.URI#getRawUserInfo} gives it) names. */
    public static PasswordAuthentication credential(String rawUserInfo) {
        int colon = rawUserInfo.indexOf(':');
        String user = decode(colon < 0 ? rawUserInfo : rawUserInfo.substring(0, colon));
        String password = decode(colon < 0 ? "" : rawUserInfo.substring(colon + 1));
        return new PasswordAuthentication(user, password.toCharArray());
    }

    /** Percent-decoding as a URI spells it: a {@code +} is a plus, not a space. */
    private static String decode(String spelled) {
        return URLDecoder.decode(spelled.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
