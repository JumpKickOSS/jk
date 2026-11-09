// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.time.Duration;

/** Runs a Windows PowerShell script elevated, behind the operating system's own consent prompt. */
public interface Elevator {

    /** How an elevated run ended. */
    sealed interface Outcome {
        /** The script ran and exited with {@code exit}. */
        record Ran(int exit) implements Outcome {}

        /** The person declined the UAC prompt. */
        record Declined() implements Outcome {}

        /** The prompt could not be shown or the script not started: Win32 error {@code error}. */
        record Failed(int error, String message) implements Outcome {}

        /** The script was still running when the wait ended. */
        record TimedOut() implements Outcome {}
    }

    Outcome run(String script, Duration timeout);

    /** This machine's elevation: one {@code ShellExecuteExW} with the {@code runas} verb. */
    static Elevator live() {
        return Shell32Elevator.INSTANCE;
    }
}
