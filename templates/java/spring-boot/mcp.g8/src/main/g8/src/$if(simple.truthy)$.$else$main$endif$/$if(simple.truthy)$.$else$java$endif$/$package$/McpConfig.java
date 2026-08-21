package $package$;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpConfig {

    /** Publishes every @Tool method on TimeTools to connected MCP clients. */
    @Bean
    public ToolCallbackProvider tools(TimeTools timeTools) {
        return MethodToolCallbackProvider.builder().toolObjects(timeTools).build();
    }
}
