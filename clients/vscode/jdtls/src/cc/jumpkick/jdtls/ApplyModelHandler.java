// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdtls;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.IDelegateCommandHandler;

/**
 * {@code jk.jdtls.applyModel}: makes the language server's projects match the engine's model, the
 * JSON string the extension passes as the only argument. Creates or updates one project per
 * module with its raw classpath and compliance, and deletes the JumpKick projects under the same
 * root the model no longer names. Project metadata stays in the language server's own storage;
 * nothing is written into the user's tree.
 */
public final class ApplyModelHandler implements IDelegateCommandHandler {

    static final String COMMAND = "jk.jdtls.applyModel";

    @Override
    public Object executeCommand(String commandId, List<Object> arguments, IProgressMonitor monitor)
            throws Exception {
        if (!COMMAND.equals(commandId)) throw new UnsupportedOperationException(commandId);
        if (arguments == null || arguments.size() != 1 || !(arguments.get(0) instanceof String json)) {
            throw new IllegalArgumentException(COMMAND + " takes the model as one JSON string");
        }
        ProjectModel model = ProjectModel.parse(json);
        IProgressMonitor m = monitor == null ? new NullProgressMonitor() : monitor;
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        List<String> applied = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        workspace.run(
                pm -> {
                    for (ProjectModel.Project p : model.projects()) applied.add(apply(workspace, p, pm));
                    removed.addAll(removeStale(workspace.getRoot(), model, pm));
                },
                workspace.getRoot(),
                IWorkspace.AVOID_UPDATE,
                m);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projects", applied);
        result.put("removed", removed);
        return result;
    }

    private static String apply(IWorkspace workspace, ProjectModel.Project p, IProgressMonitor pm)
            throws CoreException {
        IWorkspaceRoot root = workspace.getRoot();
        IProject project = root.getProject(p.name());
        IPath location = IPath.fromOSString(p.dir().toString());
        if (project.exists() && !location.equals(project.getLocation())) {
            if (!JkNature.has(project)) {
                throw new IllegalStateException("a project named " + p.name() + " already exists at "
                        + project.getLocation() + "; rename the JumpKick module or close that project");
            }
            project.delete(IResource.NEVER_DELETE_PROJECT_CONTENT | IResource.FORCE, pm);
        }
        if (!project.exists()) {
            IProjectDescription d = workspace.newProjectDescription(p.name());
            if (!location.equals(root.getLocation().append(p.name()))) d.setLocation(location);
            project.create(d, pm);
        }
        if (!project.isOpen()) project.open(pm);
        IProjectDescription d = project.getDescription();
        if (!d.hasNature(JavaCore.NATURE_ID) || !d.hasNature(JkNature.ID)) {
            d.setNatureIds(new String[] {JavaCore.NATURE_ID, JkNature.ID});
            ICommand builder = d.newCommand();
            builder.setBuilderName(JavaCore.BUILDER_ID);
            d.setBuildSpec(new ICommand[] {builder});
            project.setDescription(d, pm);
        }

        IJavaProject java = JavaCore.create(project);
        Map<String, String> options = new HashMap<>(java.getOptions(false));
        JavaCore.setComplianceOptions(p.compliance(), options);
        options.put(JavaCore.COMPILER_RELEASE, JavaCore.ENABLED);
        java.setOptions(options);

        IPath base = project.getFullPath();
        List<IClasspathEntry> entries = new ArrayList<>();
        for (ProjectModel.Entry e : p.entries()) {
            entries.add(switch (e) {
                case ProjectModel.Source s -> JavaCore.newSourceEntry(
                        base.append(s.path()), new IPath[0], new IPath[0], base.append(s.output()), attributes(s.test(), s.optional()));
                case ProjectModel.ProjectRef r ->
                    JavaCore.newProjectEntry(IPath.fromOSString("/" + r.name()), null, false, attributes(r.test(), false), false);
                case ProjectModel.Library l -> JavaCore.newLibraryEntry(
                        IPath.fromOSString(l.path()),
                        l.sources() == null ? null : IPath.fromOSString(l.sources()),
                        null,
                        null,
                        attributes(l.test(), false),
                        false);
                case ProjectModel.Container c -> JavaCore.newContainerEntry(IPath.fromPortableString(c.path()));
            });
        }
        java.setRawClasspath(entries.toArray(IClasspathEntry[]::new), base.append(p.output()), pm);
        return p.name();
    }

    private static IClasspathAttribute[] attributes(boolean test, boolean optional) {
        List<IClasspathAttribute> out = new ArrayList<>();
        if (test) out.add(JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true"));
        if (optional) {
            out.add(JavaCore.newClasspathAttribute(IClasspathAttribute.OPTIONAL, "true"));
            out.add(JavaCore.newClasspathAttribute(IClasspathAttribute.IGNORE_OPTIONAL_PROBLEMS, "true"));
        }
        return out.toArray(IClasspathAttribute[]::new);
    }

    /** JumpKick projects under the model's root that it no longer names. */
    private static List<String> removeStale(IWorkspaceRoot root, ProjectModel model, IProgressMonitor pm)
            throws CoreException {
        Set<String> wanted = model.projects().stream().map(ProjectModel.Project::name).collect(Collectors.toSet());
        Path modelRoot = Path.of(model.root());
        List<String> removed = new ArrayList<>();
        for (IProject project : root.getProjects()) {
            if (wanted.contains(project.getName()) || !JkNature.has(project) || project.getLocation() == null) continue;
            if (!Path.of(project.getLocation().toOSString()).normalize().startsWith(modelRoot)) continue;
            project.delete(IResource.NEVER_DELETE_PROJECT_CONTENT | IResource.FORCE, pm);
            removed.add(project.getName());
        }
        return removed;
    }
}
