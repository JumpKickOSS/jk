package fx.props;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Properties;

class Bad {
    String render(Properties p) throws IOException {
        StringWriter w = new StringWriter();
        p.store(w, null);
        return w.toString();
    }
}
