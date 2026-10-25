// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.InstallTarget;
import cc.jumpkick.wire.runtime.TailArgs;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A goal's tail arguments survive the build request's round trip, every variant and credential. */
class TailArgsJsonTest {

    @Test
    void every_tail_round_trips_through_a_build_request() {
        List<TailArgs> tails = List.of(
                new TailArgs.Native("ex.Main", List.of("--no-fallback", "-O2")),
                new TailArgs.Native(null, List.of()),
                new TailArgs.Install(Path.of("/home/u/.m2"), null, false),
                new TailArgs.Install(null, InstallTarget.JK, false),
                new TailArgs.Install(null, null, false),
                new TailArgs.Install(null, null, true),
                new TailArgs.Train("smoke", true),
                new TailArgs.Train(null, false),
                publish(new RepoCredential.Basic("u", "p")),
                publish(new RepoCredential.Bearer("t")),
                publish(RepoCredential.ANONYMOUS));
        for (TailArgs tail : tails) {
            assertThat(BuildRequest.decode(request(tail).encode()).tail())
                    .as(tail.toString())
                    .isEqualTo(tail);
        }
    }

    /** {@code --tarball} is tri-state: absent, the default layout path, or an explicit path. */
    @Test
    void the_image_tarball_keeps_its_three_states() {
        for (String tarball : new String[] {null, "", "/out/app.tar"}) {
            TailArgs image = new TailArgs.Image("ex.Main", "reg.example", "1.0", tarball, "podman");
            assertThat(BuildRequest.decode(request(image).encode()).tail()).isEqualTo(image);
        }
    }

    @Test
    void a_request_without_a_tail_carries_none() {
        assertThat(BuildRequest.decode(request(null).encode()).tail()).isNull();
        assertThat(request(null).encode()).doesNotContain("\"tail\"");
    }

    private static TailArgs publish(RepoCredential credential) {
        return new TailArgs.Publish(
                URI.create("https://repo.example/releases/"),
                "eu-west-1",
                "https://s3.example",
                Path.of("/w/target/app.jar"),
                true,
                false,
                Path.of("/keys/release.asc"),
                "secret",
                true,
                true,
                true,
                credential,
                true,
                "automatic");
    }

    private static BuildRequest request(@Nullable TailArgs tail) {
        return new BuildRequest(
                "/w",
                "/c",
                null,
                0,
                null,
                false,
                false,
                0,
                true,
                false,
                false,
                false,
                false,
                false,
                null,
                TestSelection.DEFAULT,
                null,
                List.of(),
                false,
                "publish",
                Map.of(),
                tail,
                null,
                null);
    }
}
