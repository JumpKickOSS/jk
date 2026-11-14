package fx.manifest;

import cc.jumpkick.host.AtomicWrites;
import java.io.IOException;
import java.nio.file.Path;

class Ok {
    void bump(Path manifest, String edited) throws IOException {
        AtomicWrites.replace(manifest, edited);
    }
}
