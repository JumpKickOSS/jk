package fx.curated;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class LockPipelineTest {

    @Test
    void round_trips() {}

    @Test
    void refuses_offline_without_a_lock_entry() {}
}
