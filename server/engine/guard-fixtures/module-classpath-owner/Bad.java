package fx.moduleclasspath;

import cc.jumpkick.config.WorkspaceClasspath;
import java.util.List;

/** A forecast resolving a module's siblings itself, beside ModuleClasspaths' own. */
class Bad {
    List<Object> forecastSiblings(Object dir, Object project) {
        return WorkspaceClasspath.resolve(dir, project);
    }
}
