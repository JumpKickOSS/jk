package fx.keyparity.claims;

import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import java.util.List;

/**
 * The war is claimed unforecast, but its factory declares it always run: the builder and the
 * forecast declaration are statements apart, where a chain-adjacent scan never saw them meet.
 */
class BadSplitChain {

    @KeyOwner(step = "package-war", unpaired = "explain does not forecast the war at all")
    static String warKey(String war) {
        return ActionKey.forArtifact("package-war:" + war, "1", List.of());
    }
}

final class SplitWarPlan {
    static Task warStep(String war) {
        Task.Builder step = Task.builder("package-war");
        String key = BadSplitChain.warKey(war);
        StepForecast how = StepForecast.always("restages " + key);
        return step.forecast(how).build();
    }
}
