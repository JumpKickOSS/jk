package fx.props;

import java.util.Properties;

class Ok {
    String one(Properties p) {
        return p.getProperty("name", "");
    }
}
