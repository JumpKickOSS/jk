package fx.keyparity.sites;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.TestStamp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** A javac key and a test stamp composed outside any owner: families beyond forArtifact. */
class BadKeyFamilies {

    static String javacKey(CompileRequest request) throws IOException {
        return ActionKey.forJavac("compile-main", request, "1");
    }

    static String testStamp(List<Path> sources, Path classes, Path lock) {
        return TestStamp.computeKey(sources, classes, List.of(), lock, List.of(), List.of());
    }
}
