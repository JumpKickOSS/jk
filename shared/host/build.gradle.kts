// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk host leaf — the JDK-only floor every jk process links: the JSONL / MiniJson " +
        "codec shared by the CLI wire (S1) and the plugin-worker protocol (S7), the host " +
        "primitives (Hashing, PathUtil, Errors, Os) and the Exit / command vocabulary. Zero " +
        "deps; JDK 17 floor so workers on a project JDK can load the same classes."

// Zero dependencies is the whole point. :core cannot serve this role — it api-exposes tomlj, and a
// thin worker rebuilds its classpath from a POM, so putting :core on a plugin classpath would drag
// a TOML parser into every forked worker. Anything added here lands on all 16 plugins (via
// :plugin-sdk) and inside the native image (via :cli): JDK-only, IO-light, no third-party types.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}
