package fx;

import java.util.Map;

class NowWritten {
    void encode(Map<String, Object> out, String auth) {
        out.put("auth", auth);
    }
}
