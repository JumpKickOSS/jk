// SPDX-License-Identifier: Apache-2.0
package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import demo.Sample.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SampleTest {
    @ParameterizedTest
    @CsvSource({"200, OK", "301, OK", "400, FAIL", "404, FAIL", "429, RETRY", "500, RETRY", "503, RETRY"})
    void classifiesEachStatusBand(int status, Outcome expected) {
        assertEquals(expected, Sample.classify(status));
    }

    @Test
    void treatsRateLimitingAsTheOneRetryableClientError() {
        assertEquals(Outcome.RETRY, Sample.classify(429));
        assertEquals(Outcome.FAIL, Sample.classify(428));
        assertEquals(Outcome.FAIL, Sample.classify(430));
    }
}
