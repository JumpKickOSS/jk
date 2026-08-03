# $name$

Spring Boot MCP server — exposes `@Tool` methods to MCP clients (Claude, IDEs, agents)
over the WebMVC SSE transport. Scaffolded from the jk `spring-boot-mcp` template —
Spring Boot $spring_boot$, Spring AI $spring_ai$.

```bash
jk build
jk test
jk run       # MCP endpoint on :8080 (SSE at /sse, messages at /mcp/message)
```

Point an MCP client at it, e.g. in a client's config:

```json
{
  "mcpServers": {
    "$name$": { "url": "http://localhost:8080/sse" }
  }
}
```

Add tools by annotating methods with `@Tool` and registering the bean in `McpConfig`
(or exposing additional `ToolCallbackProvider`s). Resources/prompts follow the same
pattern via the Spring AI MCP server APIs.
