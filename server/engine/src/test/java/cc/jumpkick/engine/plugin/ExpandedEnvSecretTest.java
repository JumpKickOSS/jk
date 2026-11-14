// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A credential-named entry whose value is expanded from the shell is filed with the workspace's
 * redactor, as a declared name is; a literal value is manifest text and is not.
 */
class ExpandedEnvSecretTest {

    @AfterEach
    void forget() {
        ResolvedSecrets.clear();
    }

    @Test
    void a_credential_named_var_expanded_from_the_shell_is_masked_like_a_declared_one(@TempDir Path module)
            throws Exception {
        Files.writeString(module.resolve("jk.toml"), "[project]\nname = \"demo\"\n");
        String secret = "shell-secret-abcdef123";
        EnvConfig viaVar = new EnvConfig(false, List.of(new EnvDecl.Set("API_TOKEN", "${UPSTREAM_SRC}")));
        EnvConfig declared = new EnvConfig(false, List.of("API_TOKEN"), List.of(), List.of(), Map.of());

        Map<String, String> fromVar =
                RequestShell.where(Map.of("UPSTREAM_SRC", secret), () -> WorkerEnv.declared(viaVar, module, null));
        assertThat(fromVar).as("the var expanded").containsEntry("API_TOKEN", secret);
        String afterVar = ResolvedSecrets.plus(module, SecretRedactor.none()).redact("t=" + secret);

        ResolvedSecrets.clear();
        RequestShell.where(Map.of("API_TOKEN", secret), () -> WorkerEnv.declared(declared, module, null));
        String afterDeclared =
                ResolvedSecrets.plus(module, SecretRedactor.none()).redact("t=" + secret);

        assertThat(afterDeclared).as("a declared API_TOKEN is filed").isEqualTo("t=" + SecretRedactor.MASK);
        assertThat(afterVar).as("vars API_TOKEN = \"${...}\" is filed too").isEqualTo("t=" + SecretRedactor.MASK);
    }

    @Test
    void a_literal_credential_named_value_is_not_filed(@TempDir Path module) throws Exception {
        Files.writeString(module.resolve("jk.toml"), "[project]\nname = \"demo\"\n");
        EnvConfig literal = new EnvConfig(false, List.of(new EnvDecl.Set("API_TOKEN", "fixture-token")));

        WorkerEnv.declared(literal, module, null);

        assertThat(ResolvedSecrets.plus(module, SecretRedactor.none()).redact("t=fixture-token"))
                .isEqualTo("t=fixture-token");
    }
}
