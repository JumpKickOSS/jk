package fx;

import java.util.Map;

class Closed {
    void encode(Map<String, Object> out, String name) {
        out.put("name", name);
    }

    String decode(String line) {
        return Jsonl.str(line, "name");
    }
}
