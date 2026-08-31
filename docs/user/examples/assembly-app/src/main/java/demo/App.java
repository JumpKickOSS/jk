// SPDX-License-Identifier: Apache-2.0
package demo;

import org.apache.commons.codec.digest.DigestUtils;

/** Hashes its arguments with Apache Commons Codec, which the fat jar bundles. */
public final class App {
    static final String DEFAULT_TEXT = "assembly-app";

    public static void main(String[] args) {
        System.out.println(describe(args.length == 0 ? DEFAULT_TEXT : String.join(" ", args)));
    }

    /** Renders {@code text} beside its SHA-256, computed by Commons Codec. */
    static String describe(String text) {
        return "sha256(" + text + ") = " + DigestUtils.sha256Hex(text);
    }

    private App() {}
}
