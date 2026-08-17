// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk-api: the stable front-end contract (IO/thread machinery lives in :core) — BuildPlan/Step scheduler SPI, " +
        "Dependency/Coordinate model, BuildPlanListener + command (CliCommand/Invocation) SPI. " +
        "Zero project dependencies — JDK + JSpecify only. PluginConfig lives here so the " +
        "native CLI does not link :plugin-sdk."

// This module IS jk's public API surface (Gradle project ":jk-api"; dir kept at
// shared/jk-api): the impl-free contract that
// plugins, third-party tools, and alternative front-ends (IDE/web/CI) compile
// against. Plugin workers compile against :plugin-sdk (JDK 17), not this module.
