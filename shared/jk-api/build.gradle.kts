// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk-api: the stable front-end contract (IO/thread machinery lives in :core) — BuildPlan/Step scheduler SPI, " +
        "Dependency/Coordinate model, BuildPlanListener. The command SPI (CliCommand/Invocation/Exit) " +
        "moved down to :host so plugin workers reach it. Depends on :host and nothing else — JDK + " +
        "JSpecify otherwise. PluginConfig lives here so the native CLI does not link :plugin-sdk."

dependencies {
    // The host leaf, and only the host leaf. :host is the JDK-only floor every jk process already
    // links (JK-2407), so this costs no consumer anything: BuildIdentity hashes the running jar and
    // Hashing owns the one MessageDigest lookup in the tree (JK-2416).
    api(project(":host"))
}

// This module IS jk's public API surface (Gradle project ":jk-api"; dir kept at
// shared/jk-api): the impl-free contract that
// plugins, third-party tools, and alternative front-ends (IDE/web/CI) compile
// against. Plugin workers compile against :plugin-sdk (JDK 17), not this module.
