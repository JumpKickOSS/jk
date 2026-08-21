package $package$;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationTest {

    @Autowired
    private ToolCallbackProvider tools;

    @Test
    void publishesTools() {
        assertThat(tools.getToolCallbacks()).hasSizeGreaterThanOrEqualTo(2);
    }
}
