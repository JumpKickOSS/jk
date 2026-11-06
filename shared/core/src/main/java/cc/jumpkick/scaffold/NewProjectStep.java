// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * jk's step of the new-project form, the one every project shares: language, template, name, group,
 * the parent directory, and the layout when the chosen template renders more than one. The engine
 * serves it ahead of a template's own step; {@code jk new}, MCP {@code new} and the web wizard all
 * render it from here, and read every other key of the form as a template property.
 */
public final class NewProjectStep {

    public static final String ID = "project";

    /** What a project is made from when nothing names a template: the blank application. */
    public static final String DEFAULT_TEMPLATE = "app";

    /**
     * The languages a catalogue template can be filed under, in the order the form offers them; the
     * template index and the {@code .jk-template.toml} loader accept exactly these.
     */
    public static final List<String> LANGS = List.of("java", "kotlin", "groovy", "scala");

    /** Where the template search lists the catalogue, filtered by the language answered before it. */
    public static final String TEMPLATES_SOURCE = "/api/templates?lang={lang}";

    /** The derived default of {@code dir}: each surface knows where a new project goes by default. */
    public static final String PARENT_DIR = "parent-dir";

    /** The derived default of {@code group}. */
    public static final String GIT_EMAIL = "git-email";

    /** A project name: the directory leaf, the artifact id and an image name, so lowercase. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

    /** A group: a Java package, since templates use it as one. */
    public static final Pattern GROUP = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    /** The step's keys; every other key of a new-project form is a template property. */
    public static final Set<String> KEYS = Set.of("lang", "template", "name", "group", "dir", "layout");

    private NewProjectStep() {}

    /**
     * The step for a template in {@code language} that renders {@code layouts}; before a template is
     * chosen, {@code language} is the default one and {@code layouts} is empty.
     */
    public static Step of(String language, List<String> layouts, String defaultLayout) {
        List<Field> fields = new ArrayList<>();
        Field.Builder lang = Field.of("lang", "Language", FieldKind.CHOICE);
        for (String l : LANGS) lang.option(l, Character.toUpperCase(l.charAt(0)) + l.substring(1), null);
        fields.add(lang.defaultValue(LANGS.contains(language) ? language : LANGS.getFirst())
                .build());
        fields.add(Field.of("template", "Template", FieldKind.SEARCH)
                .help("Every project comes from one; app is a blank application")
                .source(TEMPLATES_SOURCE)
                .defaultValue(DEFAULT_TEMPLATE)
                .build());
        fields.add(Field.of("name", "Name", FieldKind.TEXT)
                .help("The project's name and directory")
                .pattern(NAME.pattern())
                .build());
        fields.add(Field.of("group", "Group", FieldKind.TEXT)
                .help("Maven groupId and package")
                .pattern(GROUP.pattern())
                .defaultFrom(GIT_EMAIL)
                .build());
        fields.add(Field.of("dir", "Directory", FieldKind.PATH)
                .help("Where the project's directory is created")
                .defaultFrom(PARENT_DIR)
                .build());
        if (layouts.size() > 1) {
            Field.Builder layout = Field.of("layout", "Layout", FieldKind.CHOICE);
            for (String l : layouts) layout.option(l);
            fields.add(layout.defaultValue(defaultLayout).build());
        }
        return new Step(ID, "Project", fields);
    }
}
