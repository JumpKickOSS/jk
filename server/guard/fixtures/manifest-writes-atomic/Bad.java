package fx.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class Bad {
    void bump(Path manifest, String edited) throws IOException {
        Files.writeString(manifest, edited);
    }
}
