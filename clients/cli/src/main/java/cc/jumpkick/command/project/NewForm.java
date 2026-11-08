// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.tui.form.Asker;
import cc.jumpkick.cli.tui.form.FormRenderer;
import cc.jumpkick.cli.tui.form.FormSources;
import cc.jumpkick.cli.tui.form.PathPrompt;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldLine;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.form.FormCommand;
import cc.jumpkick.form.Option;
import cc.jumpkick.form.Values;
import cc.jumpkick.scaffold.NewProjectStep;
import cc.jumpkick.wire.protocol.NewProjectFormAck;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk new}'s one path: the new-project form the engine serves. jk's step comes first
 * ({@link NewProjectStep}: language, template, name, group, directory); once a template is chosen its
 * own form follows (the layout when it renders more than one, then its {@code [[ask]]}). On a
 * terminal the form is walked with the form widgets and ends on the command that gives the same
 * project without asking; with no terminal the defaults answer what the flags left open, and a
 * required field with neither is an error naming its flag. Flags answer their fields up front, and
 * {@code -p key=value} answers a template's.
 */
final class NewForm {

    /** The step-1 keys; every other key of the form is a template property, answered by {@code -p}. */
    static final Set<String> PROJECT_KEYS = NewProjectStep.KEYS;

    private NewForm() {}

    /** Fetches the form: jk's step alone for no template, else that step and the template's. */
    @FunctionalInterface
    interface Forms {
        NewProjectFormAck fetch(@Nullable String template, @Nullable String lang) throws IOException;
    }

    /** What the flags answered; {@code params} are the {@code -p key=value} pairs, in order. */
    record Given(
            @Nullable String lang,
            @Nullable String template,
            @Nullable String name,
            @Nullable String group,
            @Nullable String dir,
            @Nullable String layout,
            Map<String, String> params) {
        Given {
            params = Map.copyOf(params);
        }
    }

    /**
     * The walk's outcome: the settled step-1 values, the template properties (the form's, then any
     * other {@code -p} pair as given) and the equivalent command.
     */
    record Answered(Map<String, Object> values, Map<String, String> params, String command) {
        String template() {
            return String.valueOf(values.get("template"));
        }

        String name() {
            return String.valueOf(values.get("name"));
        }

        @Nullable
        String text(String key) {
            return values.get(key) instanceof String s ? s : null;
        }
    }

    /**
     * Walks the form; empty when the user backed out. Once input ends every question left takes its
     * default, and one without a default ends the walk as {@link #settle} would, naming its flag.
     *
     * @param command the command the summary spells, positional arguments included
     * @param flags how each step-1 field is spelled on that command
     * @throws IOException when the engine cannot serve the form (an unknown template, a template
     *     that fails lint), naming why
     */
    static Optional<Answered> walk(
            Asker asker,
            Forms forms,
            Given given,
            DerivedDefaults derived,
            Path base,
            String command,
            Map<String, FormCommand.Flag> flags)
            throws IOException {
        NewProjectFormAck first = served(forms.fetch(given.template(), given.lang()));
        FormSources sources = new FormSources(derived, search(first.templates()), PathPrompt.directories(base));
        FormRenderer renderer = new FormRenderer(asker, sources);
        Map<String, Object> settled = new LinkedHashMap<>();
        Form form = Form.fromJson(first.form());
        Map<String, Object> presets = presets(form, given);
        if (given.template() == null) {
            Optional<Map<String, Object>> project = renderer.run(form, presets);
            if (project.isEmpty()) return unanswered(asker, forms, given, derived, command, flags);
            settled.putAll(project.get());
            NewProjectFormAck second =
                    served(forms.fetch((String) settled.get("template"), (String) settled.get("lang")));
            form = Form.fromJson(second.form());
            settled.putAll(resolved(second));
            presets = presets(form, given);
        } else {
            Map<String, Object> resolved = resolved(first);
            if (given.lang() != null) resolved.remove("lang");
            presets.putAll(resolved);
        }
        Optional<Map<String, Object>> values = renderer.run(form, settled, presets);
        if (values.isEmpty()) return unanswered(asker, forms, given, derived, command, flags);
        String line = FormCommand.render(command, form, values.get(), flags, derived);
        renderer.summary(line);
        return Optional.of(new Answered(values.get(), params(form, values.get(), given), line));
    }

    /**
     * A walk that ended unanswered: empty when the user backed out; once input ended, the form
     * {@link #settle}d from the flags and defaults, which names the flag a required field lacks.
     */
    private static Optional<Answered> unanswered(
            Asker asker,
            Forms forms,
            Given given,
            DerivedDefaults derived,
            String command,
            Map<String, FormCommand.Flag> flags)
            throws IOException {
        if (!asker.ended()) return Optional.empty();
        return Optional.of(settle(forms, given, derived, command, flags));
    }

    /**
     * The form settled without asking: the flags answer their fields and defaults the rest, so no
     * {@code -t} is the default template.
     *
     * @throws Unsettled naming each field the flags and defaults leave unanswered or answer wrongly,
     *     with the flag that answers it
     * @throws IOException when the engine cannot serve the form
     */
    static Answered settle(
            Forms forms, Given given, DerivedDefaults derived, String command, Map<String, FormCommand.Flag> flags)
            throws IOException {
        NewProjectFormAck first = served(forms.fetch(given.template(), given.lang()));
        Form form = Form.fromJson(first.form());
        Map<String, Object> presets = presets(form, given);
        NewProjectFormAck ack = first;
        if (given.template() == null) {
            // jk's step alone names the template (its default when no flag does) and the language.
            Map<String, Object> project = form.check(presets, derived).values();
            ack = served(forms.fetch((String) project.get("template"), (String) project.get("lang")));
            form = Form.fromJson(ack.form());
            presets = presets(form, given);
        }
        Map<String, Object> resolved = resolved(ack);
        if (given.lang() != null) resolved.remove("lang");
        presets.putAll(resolved);
        FormCheck check = form.check(presets, derived);
        if (!check.ok()) throw new Unsettled(form, check, flags, command);
        String line = FormCommand.render(command, form, check.values(), flags, derived);
        return new Answered(check.values(), params(form, check.values(), given), line);
    }

    /** What the flags and defaults leave unsettled: one line per field, naming the flag that answers it. */
    static final class Unsettled extends RuntimeException {

        private final List<String> problems;
        private final String prefix;

        /** {@code command} is the summary's command: {@code jk new}, {@code jk init}, or with the positional. */
        Unsettled(Form form, FormCheck check, Map<String, FormCommand.Flag> flags, String command) {
            super(check.problems().getFirst().message());
            String[] words = command.split(" ");
            this.prefix = words.length >= 2 ? words[0] + " " + words[1] : command;
            List<String> out = new ArrayList<>();
            for (FormCheck.Problem p : check.problems()) {
                Field f = form.field(p.key());
                String flag = f == null ? null : flag(f, flags, command);
                if (flag == null) out.add(p.message());
                else if (p.message().endsWith(" needs a value")) out.add(p.message() + ": " + flag);
                else out.add(p.message() + " (" + flag + ")");
            }
            this.problems = List.copyOf(out);
        }

        /** The problems, a line each: {@code jk new: database needs a value: -p database=none|postgres}. */
        String lines() {
            StringBuilder out = new StringBuilder();
            for (String p : problems) {
                if (!out.isEmpty()) out.append('\n');
                out.append(prefix).append(": ").append(p);
            }
            return out.toString();
        }

        /** The flag that answers {@code f}: its own spelling, else {@code -p key=…}. */
        private static String flag(Field f, Map<String, FormCommand.Flag> flags, String command) {
            String param = FieldLine.param(f);
            String takes = param.substring(param.indexOf('=') + 1);
            if (takes.equals("…")) takes = "<" + f.key() + ">";
            return switch (flags.get(f.key())) {
                case null -> param;
                case FormCommand.Flag.Positional p -> command + " " + takes;
                case FormCommand.Flag.Named n -> n.name() + " " + takes;
            };
        }
    }

    /** The template properties: the form's settled ones, then any other {@code -p} pair as given. */
    private static Map<String, String> params(Form form, Map<String, Object> values, Given given) {
        Map<String, String> params = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            if (!PROJECT_KEYS.contains(e.getKey())) params.put(e.getKey(), Values.text(e.getValue()));
        }
        for (var e : given.params().entrySet()) {
            if (form.field(e.getKey()) == null) params.put(e.getKey(), e.getValue());
        }
        return params;
    }

    /**
     * The {@code --help} section for one template: each field it adds to jk's step, one line each,
     * as {@code -p} sets it.
     */
    static String help(Form form, String template) {
        StringBuilder out = new StringBuilder("Template ").append(template);
        List<Field> fields = new ArrayList<>();
        for (Field f : form.fields()) {
            if (f.key().equals("layout") || !PROJECT_KEYS.contains(f.key())) fields.add(f);
        }
        if (fields.isEmpty())
            return out.append(" asks nothing beyond jk's own flags.\n").toString();
        out.append(" (-L for layout, -p key=value for the rest):\n");
        for (Field f : fields) out.append("  ").append(FieldLine.of(f)).append('\n');
        return out.toString();
    }

    /** The flags' answers for the form's fields, typed for each field's kind. */
    static Map<String, Object> presets(Form form, Given given) {
        Map<String, String> raw = new LinkedHashMap<>(given.params());
        putIfSet(raw, "lang", given.lang());
        putIfSet(raw, "template", given.template());
        putIfSet(raw, "name", given.name());
        putIfSet(raw, "group", given.group());
        putIfSet(raw, "dir", given.dir());
        putIfSet(raw, "layout", given.layout());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : form.fields()) {
            String value = raw.get(f.key());
            if (value == null) continue;
            Object typed = Values.parse(f, value);
            if (typed != null) out.put(f.key(), typed);
        }
        return out;
    }

    /**
     * The template search: the catalogue entries in the language the endpoint names, each as the
     * shortest ref that resolves to it in that language ({@code name} under framework {@code none},
     * else {@code framework/name}), those starting with the query first.
     */
    static FormSources.Search search(Map<String, String> catalogue) {
        return (field, endpoint, query) -> {
            String lang = queryParam(endpoint, "lang");
            String q = query.strip().toLowerCase(Locale.ROOT);
            List<Option> first = new ArrayList<>();
            List<Option> rest = new ArrayList<>();
            for (var e : catalogue.entrySet()) {
                String[] id = e.getKey().split("/");
                if (id.length != 3 || (lang != null && !lang.isEmpty() && !id[0].equals(lang))) continue;
                String ref = id[1].equals("none") ? id[2] : id[1] + "/" + id[2];
                String description = e.getValue();
                Option option = new Option(ref, null, description.isBlank() ? null : description);
                if (ref.startsWith(q)) first.add(option);
                else if (ref.contains(q) || description.toLowerCase(Locale.ROOT).contains(q)) rest.add(option);
            }
            first.addAll(rest);
            return first;
        };
    }

    /**
     * What the engine resolved the template ref to: the id, so the summary names exactly the
     * template that was scaffolded, and for a catalogue id the language it is in.
     */
    private static Map<String, Object> resolved(NewProjectFormAck ack) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = ack.template();
        if (id.isEmpty()) return out;
        out.put("template", id);
        String[] parts = id.split("/");
        if (parts.length == 3 && NewProjectStep.LANGS.contains(parts[0])) out.put("lang", parts[0]);
        return out;
    }

    private static @Nullable String queryParam(String endpoint, String name) {
        int q = endpoint.indexOf('?');
        if (q < 0) return null;
        for (String pair : endpoint.substring(q + 1).split("&")) {
            if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
        }
        return null;
    }

    private static NewProjectFormAck served(NewProjectFormAck ack) throws IOException {
        String error = ack.error();
        if (error != null && !error.isBlank()) throw new IOException(error);
        return ack;
    }

    private static void putIfSet(Map<String, String> out, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }
}
