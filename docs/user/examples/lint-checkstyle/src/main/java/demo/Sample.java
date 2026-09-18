// SPDX-License-Identifier: Apache-2.0
package demo;

import java.util.List;
import java.util.stream.Stream;

/** Decides what a caller should do about an HTTP status code. */
public final class Sample {

    /** The request succeeded. */
    private static final int OK_STATUS = 200;

    /** Lowest status that blames the caller. */
    private static final int BAD_REQUEST = 400;

    /** The one client error worth retrying: the caller was rate limited. */
    private static final int TOO_MANY_REQUESTS = 429;

    /** Lowest status that blames the server, and every one of those is worth retrying. */
    private static final int INTERNAL_SERVER_ERROR = 500;

    /** Statuses {@code main} classifies when it is given no arguments. */
    private static final List<Integer> DEMO_STATUSES =
            List.of(OK_STATUS, BAD_REQUEST, TOO_MANY_REQUESTS, INTERNAL_SERVER_ERROR);

    private Sample() {}

    /** What a caller should do about a status code. */
    public enum Outcome {
        /** Nothing to retry: the server handled the request. */
        OK,
        /** Transient failure: send it again. */
        RETRY,
        /** The caller is wrong; sending it again will not help. */
        FAIL
    }

    /** The action {@code status} calls for. */
    public static Outcome classify(int status) {
        if (status < BAD_REQUEST) {
            return Outcome.OK;
        }
        if (status == TOO_MANY_REQUESTS || status >= INTERNAL_SERVER_ERROR) {
            return Outcome.RETRY;
        }
        return Outcome.FAIL;
    }

    /** Prints one {@code status -> outcome} line per argument, or for a demo set when given none. */
    public static void main(String[] args) {
        List<Integer> statuses = args.length == 0
                ? DEMO_STATUSES
                : Stream.of(args).map(Integer::parseInt).toList();
        statuses.forEach(status -> System.out.println(status + " -> " + classify(status)));
    }
}
