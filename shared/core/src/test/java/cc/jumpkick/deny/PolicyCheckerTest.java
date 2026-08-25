// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.deny;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.DenyPolicy;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyCheckerTest {

    @TempDir
    Path dir;

    /**
     * {@code [deny]} is read by the manifest owner, not by a private parser — so these cases break
     * if {@link JkBuildParser}'s document policy changes.
     */
    private DenyPolicy deny(String toml) throws IOException {
        Path file = dir.resolve("jk.toml");
        Files.writeString(file, toml);
        return JkBuildParser.denyPolicy(file);
    }

    @Test
    void no_policy_yields_no_violations() {
        Lockfile lock = new Lockfile(
                5,
                "jk test",
                "pubgrub-v1",
                List.of(pkg("g:a", "1.0", "central+https://repo.maven.apache.org/maven2/")));
        var violations = new PolicyChecker(DenyPolicy.permissive()).check(lock);
        assertThat(violations).isEmpty();
    }

    @Test
    void denied_source_host_flags_packages_from_that_host() {
        Lockfile lock = new Lockfile(
                5,
                "jk test",
                "pubgrub-v1",
                List.of(
                        pkg("g:a", "1.0", "central+https://repo.maven.apache.org/maven2/"),
                        pkg("g:b", "1.0", "jcenter+https://jcenter.bintray.com/"),
                        pkg("g:c", "1.0", "nexus+https://nexus.example.com/repository/maven-public/")));

        DenyPolicy policy =
                new DenyPolicy(List.of("jcenter.bintray.com"), List.of(), List.of(), DenyPolicy.YankedPolicy.DENY);
        var violations = new PolicyChecker(policy).check(lock);
        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().module()).isEqualTo("g:b");
        assertThat(violations.getFirst().reason()).contains("jcenter.bintray.com");
    }

    @Test
    void suffix_match_flags_subdomain() {
        Lockfile lock = new Lockfile(
                5, "jk test", "pubgrub-v1", List.of(pkg("g:a", "1.0", "shady+https://artifacts.shady-corp.example/")));
        DenyPolicy policy =
                new DenyPolicy(List.of("shady-corp.example"), List.of(), List.of(), DenyPolicy.YankedPolicy.DENY);
        var violations = new PolicyChecker(policy).check(lock);
        assertThat(violations).hasSize(1);
    }

    @Test
    void host_match_does_not_flag_unrelated_suffix_hosts() {
        // deny evil.com must not match notevil.com
        assertThat(PolicyChecker.hostMatches("notevil.com", "evil.com")).isFalse();
        assertThat(PolicyChecker.hostMatches("evil.com", "evil.com")).isTrue();
        assertThat(PolicyChecker.hostMatches("repo.evil.com", "evil.com")).isTrue();
        assertThat(PolicyChecker.hostMatches("evil.com.evil", "evil.com")).isFalse();
    }

    @Test
    void toml_parser_extracts_sources_only() throws IOException {
        DenyPolicy policy = deny("""
                group    = "g"
                name     = "a"
                version  = "1"
                jdk      = 25

                [deny.sources]
                deny = ["jcenter.bintray.com"]
                """);
        assertThat(policy.deniedSources()).containsExactly("jcenter.bintray.com");
        assertThat(policy.deniedLicenses()).isEmpty();
        assertThat(policy.yanked()).isEqualTo(DenyPolicy.YankedPolicy.ALLOW);
    }

    @Test
    void toml_parser_rejects_unenforced_licenses() {
        assertThatThrownBy(() -> deny("""
                group = "g"
                name = "a"
                version = "1"
                jdk = 25
                [deny.licenses]
                deny = ["GPL-3.0"]
                """)).isInstanceOf(JkBuildParseException.class);
    }

    @Test
    void toml_parser_rejects_unenforced_yanked_deny() {
        assertThatThrownBy(() -> deny("""
                group = "g"
                name = "a"
                version = "1"
                jdk = 25
                [deny]
                yanked = "deny"
                """)).isInstanceOf(JkBuildParseException.class);
    }

    @Test
    void toml_parser_returns_permissive_when_block_absent() throws IOException {
        DenyPolicy policy = deny("""
                group    = "g"
                name     = "a"
                version  = "1"
                jdk      = 25
                """);
        assertThat(policy.isEmpty()).isTrue();
    }

    /**
     * The owner's {@link cc.jumpkick.config.Interpolation} whitelist now reaches {@code [deny]}.
     * The private parser had no guard, so a denied-source list could be environment-dependent —
     * a policy gate that reads differently on two machines.
     */
    @Test
    void interpolation_outside_the_whitelist_is_rejected() {
        assertThatThrownBy(() -> deny("""
                name = "a"
                [deny.sources]
                deny = ["${BLOCKED_HOST}"]
                """)).isInstanceOf(JkBuildParseException.class);
    }

    private static Lockfile.Artifact pkg(String name, String version, String source) {
        return new Lockfile.Artifact(name, version, source, "sha256:0", null, List.of(Scope.MAIN), List.of());
    }
}
