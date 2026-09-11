// SPDX-License-Identifier: Apache-2.0
package demo;

import io.micronaut.runtime.Micronaut;

/** Micronaut HTTP fixture: one controller over the Netty server, packaged three ways. */
public final class Application {
    private Application() {}

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
