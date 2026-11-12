package fx.keyparity.sites;

import cc.jumpkick.task.ActionKey;
import java.util.List;

/** A key built inside a return statement, not assigned to anything: still a site. */
class BadReturnPosition {

    record Keyed(String taskId, String key) {}

    static Keyed packageJar(String jar) {
        String taskId = "package-jar:" + jar;
        return new Keyed(taskId, ActionKey.forArtifact(taskId, "1", List.of("jar:" + jar)));
    }
}
