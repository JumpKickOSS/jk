// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-micronaut: Micronaut build plugin worker (AOT step). Declarative BOM/scaffold in jk-plugin.toml."

dependencies {
    implementation(project(":plugin-sdk"))
}
