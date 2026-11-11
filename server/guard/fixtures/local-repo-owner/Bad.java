package fx.m2;

import java.nio.file.Path;

class Bad {
    Path installRoot(Path m2DirOverride) {
        Path m2 = m2DirOverride != null ? m2DirOverride : Path.of(System.getProperty("user.home"), ".m2");
        return m2.resolve("repository");
    }
}
