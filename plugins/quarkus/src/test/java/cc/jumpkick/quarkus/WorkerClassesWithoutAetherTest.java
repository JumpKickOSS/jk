// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The worker JVM has no Aether on its classpath; only the JVMs it forks do. A class the worker
 * loads that names Aether's transfer types where the verifier must check them (passing an {@link
 * AetherTransfers} as a {@code TransferListener}) fails the step with NoClassDefFoundError, so
 * the listener is attached by {@link AetherTransfers#attach} in the forked JVM alone.
 */
class WorkerClassesWithoutAetherTest {

    @ParameterizedTest
    @ValueSource(classes = {QuarkusPlugin.class, RepositoryRoutes.class, ForkNotices.class})
    void a_class_the_worker_loads_names_no_aether_transfer_type(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            bytes = in.readAllBytes();
        }
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);

        assertThat(constants).doesNotContain("org/eclipse/aether/transfer/").doesNotContain("AetherTransfers");
    }
}
