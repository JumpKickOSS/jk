// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk dynamic surface: the parts of a program static analysis cannot see " +
        "(reflection, proxies, resources, serialization, service loading), modelled once and " +
        "emitted as both R8 keep rules and GraalVM reachability metadata. Links nothing but " +
        ":host so the minified worker and the native-image driver can both take it."

// :host only. This module used to declare no dependency at all and paid for it with a private
// JSON parser and two private escapers — the "dependency-free" it was protecting was never
// available, since :host is the JDK-only floor :plugin-sdk already puts on the minified worker
// and :cli puts inside the native image (JK-2422).
dependencies {
    api(project(":host"))
}
