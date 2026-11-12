package fx.keyparity.claims;

import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import java.util.List;

/** An owner whose step no factory builds: the annotation names a step that is not there. */
class BadUnbuiltStep {

    @KeyOwner(step = "package-wat", unpaired = "explain does not forecast it")
    static String key(String out) {
        return ActionKey.forArtifact("package-wat:" + out, "1", List.of());
    }
}
