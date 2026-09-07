package fx.handbuilt;

import java.nio.file.Path;

class Ok {
    Path lib(Path javaHome) {
        return javaHome.resolve("lib").resolve("modules");
    }
}
