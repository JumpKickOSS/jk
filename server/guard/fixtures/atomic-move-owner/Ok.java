package fx.move;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

class Ok {
    void rename(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
}
