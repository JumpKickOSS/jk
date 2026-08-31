// SPDX-License-Identifier: Apache-2.0
package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {
    /** Published SHA-256 of zero bytes — a vector Commons Codec must reproduce. */
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** The digest the README and the fat jar both print. */
    private static final String DEFAULT_SHA256 = "7b3f920f1dd85d7741b976d4c7e8a075fc6447c3fee820cec387b93ea7499e03";

    @Test
    void hashesTheCanonicalEmptyStringVector() {
        assertEquals("sha256() = " + EMPTY_SHA256, App.describe(""));
    }

    @Test
    void describesTheDefaultTextAsTheReadmeClaims() {
        assertEquals("sha256(assembly-app) = " + DEFAULT_SHA256, App.describe(App.DEFAULT_TEXT));
    }
}
