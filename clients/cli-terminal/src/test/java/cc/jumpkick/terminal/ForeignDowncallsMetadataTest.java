// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Native-image {@code MissingForeignRegistrationError} on {@code tcgetattr}/{@code poll} made
 * {@code PosixTty.openControlling()} return null on a live TTY, so {@code jk new} skipped the
 * wizard. This file is the registration, not a comment.
 */
class ForeignDowncallsMetadataTest {

    private static final Pattern DOWNCALL = Pattern.compile(
            "\"returnType\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)]"
                    + "(?:\\s*,\\s*\"options\"\\s*:\\s*\\{([^}]*)\\})?",
            Pattern.DOTALL);

    @Test
    void posixTtyDowncallsAreRegistered() throws IOException {
        String json = readMetadata();
        List<String> keys = keys(json);
        assertThat(keys)
                .contains(
                        "jint|jint, void*|capture",
                        "jint|jint, jint, void*|capture",
                        "jint|void*, jlong, jint|capture",
                        "jint|void*, jint, jint|capture",
                        "jlong|jint, void*, jlong|capture",
                        "jint|jint, jint, jint|variadic2",
                        "jint|jint, jlong, void*|variadic2",
                        "jint|void*, jint|",
                        "jint|jint|");
    }

    private static String readMetadata() throws IOException {
        String path = "/META-INF/native-image/cc.jumpkick/cli-terminal/reachability-metadata.json";
        try (InputStream in = ForeignDowncallsMetadataTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> keys(String json) {
        int foreign = json.indexOf("\"foreign\"");
        assertThat(foreign).isGreaterThanOrEqualTo(0);
        Matcher m = DOWNCALL.matcher(json.substring(foreign));
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            String params = m.group(2).replace("\"", "").replaceAll("\\s+", " ").trim();
            String options = m.group(3) == null ? "" : m.group(3);
            String extra = options.contains("captureCallState")
                    ? "capture"
                    : options.contains("firstVariadicArg") ? "variadic2" : "";
            keys.add(m.group(1) + "|" + params + "|" + extra);
        }
        return keys;
    }
}
