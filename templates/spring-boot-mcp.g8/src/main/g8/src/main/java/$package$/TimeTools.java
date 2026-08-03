package $package$;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Example MCP tools — replace with your domain's agent-facing operations. */
@Service
public class TimeTools {

    @Tool(description = "Current date-time in an IANA time zone (e.g. America/Denver)")
    public String now(@ToolParam(description = "IANA time zone id") String zone) {
        ZoneId zoneId = ZoneId.of(zone == null || zone.isBlank() ? "UTC" : zone);
        return ZonedDateTime.now(zoneId).format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    @Tool(description = "Echo a message back (connectivity check)")
    public String echo(@ToolParam(description = "message to echo") String message) {
        return message;
    }
}
