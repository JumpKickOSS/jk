package fx.keyparity.callers;

import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import java.util.List;

/**
 * The owner's two callers are both the forecast's, one call spelled as a prefix of the other: a
 * text reach point would have counted the first as the build's.
 */
class BadForecastOnly {

    @KeyOwner(step = "package-jar")
    static String jarKey(String jar, String dir) {
        return ActionKey.forArtifact("package-jar:" + jar, "1", List.of("dir:" + dir));
    }
}

final class PrefixPlanner {
    static String forecastJar(String jar) {
        return BadForecastOnly.jarKey(jar, "d");
    }

    static String forecastJarAt(String jar, String dir) {
        return BadForecastOnly.jarKey(jar, dir);
    }
}
