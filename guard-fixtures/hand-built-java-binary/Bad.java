package fx.handbuilt;

import java.nio.file.Path;

class Bad {
    Path java(Path javaHome) {
        return javaHome.resolve("bin/java");
    }
}
