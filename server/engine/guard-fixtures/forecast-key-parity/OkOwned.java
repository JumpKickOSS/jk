package fx.keyparity.sites;

import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import java.util.List;
import java.util.function.Supplier;

/** Keys derived in an owner, a lambda inside one, and a private helper only owners call. */
class OkOwned {

    @KeyOwner(step = "package-jar")
    static String jarKey(String jar) {
        return ActionKey.forArtifact("package-jar:" + jar, "1", List.of("jar:" + jar));
    }

    @KeyOwner(step = "package-javadoc")
    static Supplier<String> javadocKey(String jar) {
        return () -> ActionKey.forArtifact("package-javadoc:" + jar, "1", List.of());
    }

    @KeyOwner(step = "package-sources")
    static String sourcesKey(String jar) {
        return keyed("package-sources:" + jar);
    }

    private static String keyed(String taskId) {
        return ActionKey.forArtifact(taskId, "1", List.of());
    }
}
