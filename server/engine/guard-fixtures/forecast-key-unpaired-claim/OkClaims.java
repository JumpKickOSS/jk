package fx.keyparity.claims;

import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import java.util.List;

/**
 * Claims that match their factories: an owner that is its own step's factory and declares
 * nothing, and an owner whose step's factory declares a verdict the forecast names.
 */
class OkClaims {

    @KeyOwner(step = "package-jlink", unpaired = "explain does not forecast the runtime image at all")
    static Task jlinkStep(String image) {
        return Task.builder("package-jlink")
                .execute(ctx -> ActionKey.forArtifact("package-jlink:" + image, "1", List.of()))
                .build();
    }

    @KeyOwner(step = "write-image", unpaired = "the image tail declares itself always run", forecastsStep = true)
    static String imageKey(String image) {
        return ActionKey.forArtifact("write-image:" + image, "1", List.of());
    }
}

final class ClaimsImagePlan {
    static Task writeImageStep(String image) {
        return Task.builder("write-image")
                .forecast(StepForecast.always("pushes " + image))
                .build();
    }
}
