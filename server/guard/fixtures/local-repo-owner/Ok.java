package fx.m2;

import java.nio.file.Path;

class Ok {
    // Installs go to ~/.m2/repository unless the user configured another one.
    Path installRoot(Path repositories) {
        return repositories.resolve("releases");
    }
}
