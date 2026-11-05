package fx.config.bad;

import java.io.IOException;
import java.nio.file.Path;

class Bad {
    static String nameOrNull(Path manifest) {
        try {
            return JkBuildParser.parse(manifest);
        } catch (Exception e) {
            return null;
        }
    }
}

final class JkBuildParser {
    /**
     * The project's name.
     *
     * @throws UserConfigParseException when the user's config.toml does not parse
     */
    static String parse(Path manifest) throws IOException {
        return manifest.toString();
    }
}
