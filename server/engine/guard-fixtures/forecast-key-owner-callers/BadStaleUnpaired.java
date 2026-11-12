package fx.keyparity.callers;

import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import java.util.List;

/** Declared unpaired, yet the build and the forecast both call it: the declaration went stale. */
class BadStaleUnpaired {

    @KeyOwner(step = "package-war", unpaired = "explain does not forecast the war at all")
    static String warKey(String war) {
        return ActionKey.forArtifact("package-war:" + war, "1", List.of());
    }
}

final class StalePlan {
    static String warStep(String war) {
        return BadStaleUnpaired.warKey(war);
    }

    static String forecastWar(String war) {
        return BadStaleUnpaired.warKey(war);
    }
}
