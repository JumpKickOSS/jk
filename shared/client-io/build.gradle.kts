// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
}

description = "jk client I/O: plain-JDK HTTP, forge auth, repository credentials and transports, " +
        "catalog access, and the content-addressed store's local read/link surface. POM walking " +
        "and dependency fetch pipelines remain in :io."

dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":host")))
}
