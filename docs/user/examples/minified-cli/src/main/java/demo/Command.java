// SPDX-License-Identifier: Apache-2.0
package demo;

import java.util.List;

/**
 * One verb of the CLI. Every implementation is reached by name — from a service file or from the
 * registry resource — and none is referenced from {@link Cli}'s bytecode.
 */
public interface Command {

    /** The verb that selects this command on the command line. */
    String verb();

    /** Runs the command over {@code args}, returning the single line to print. */
    String run(List<String> args);
}
