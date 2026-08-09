// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk dynamic surface: the parts of a program static analysis cannot see " +
        "(reflection, proxies, resources, serialization, service loading), modelled once and " +
        "emitted as both R8 keep rules and GraalVM reachability metadata. Dependency-free so " +
        "the minified worker and the native-image driver can both link it."
