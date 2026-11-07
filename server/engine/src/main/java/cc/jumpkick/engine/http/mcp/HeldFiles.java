// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bytes of a few files as they stood before an edit, so an edit a person has not yet approved
 * can be put back: a file that existed gets its bytes again, one that did not is deleted.
 */
public final class HeldFiles {

    private final Map<Path, Optional<byte[]>> before;

    private HeldFiles(Map<Path, Optional<byte[]>> before) {
        this.before = before;
    }

    /** Reads {@code files} now. */
    public static HeldFiles of(List<Path> files) throws IOException {
        Map<Path, Optional<byte[]>> m = new LinkedHashMap<>();
        for (Path f : files) {
            m.put(f, Files.isRegularFile(f) ? Optional.of(Files.readAllBytes(f)) : Optional.empty());
        }
        return new HeldFiles(m);
    }

    /** Puts every file back as {@link #of} read it. */
    public void restore() throws IOException {
        for (var e : before.entrySet()) {
            if (e.getValue().isPresent()) Files.write(e.getKey(), e.getValue().get());
            else Files.deleteIfExists(e.getKey());
        }
    }
}
