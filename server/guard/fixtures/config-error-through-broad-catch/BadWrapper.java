package fx.config.badwrapper;

import java.io.IOException;
import java.nio.file.Path;

class BadWrapper {
    static String nameOrNull(Path manifest) {
        try {
            return Projects.load(manifest);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }
}

final class Projects {
    static String load(Path manifest) throws IOException {
        return Reader.read(manifest).strip();
    }
}

final class Reader {
    /**
     * The manifest's text.
     *
     * @throws UserConfigParseException when the user's config.toml does not parse
     */
    static String read(Path manifest) throws IOException {
        return manifest.toString();
    }
}
