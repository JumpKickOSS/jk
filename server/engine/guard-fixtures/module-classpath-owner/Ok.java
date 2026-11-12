package fx.moduleclasspath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A step reading the classpath the classpath step published, handed to it. */
class Ok {
    List<Path> withClasses(List<Path> published, Path classes) {
        List<Path> cp = new ArrayList<>(published);
        cp.add(classes);
        return cp;
    }
}
