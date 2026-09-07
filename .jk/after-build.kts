// SPDX-License-Identifier: Apache-2.0
//
// The house-rule gate: every rule in docs/contributors/code-as-art.md that a text scan can
// check, checked.
//
// The workspace root's `after-build` anchor, so it runs once, after every member module, with
// the whole tree on disk. That scope is also what makes its caching sound: a root script's
// action key covers every file in the checkout bar build output, so an unchanged tree skips
// the gate and any edit anywhere re-runs it. Keyed to a module — where this lived before the
// root had an anchor — a green verdict would have survived changes to the very files it reads.
//
// Bindings are `projectDir` (the workspace root) and `outDir`. Nothing is written to `outDir`:
// a check has no artifact, and its result is the verdict the cache records.
//
// Guards are collected, not short-circuited: one run reports every rule that is broken.

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Tree
// ---------------------------------------------------------------------------

/** Workspace root: the one directory carrying the catalog. */
val root: Path = generateSequence(projectDir.toAbsolutePath().normalize()) { it.parent }
    .firstOrNull { Files.isRegularFile(it.resolve("jk-libs.toml")) }
    ?: error("gate: no jk-libs.toml above $projectDir — the workspace root marker moved")

// Every derivation below is memoised per file. Twenty guards over 2,300 sources is twenty passes
// of the same lexer otherwise, and the gate runs on every build — the first version of this file
// spent 23 seconds re-reading and re-blanking the tree.
val rawCache = HashMap<Path, String>()
val guardCache = HashMap<Path, String>()
val codeCache = HashMap<Path, String>()
val blankedCache = HashMap<Path, String>()

fun text(p: Path): String = rawCache.getOrPut(p) { Files.readString(p) }

/** Comments and imports gone, whitespace squashed. Reuses [codeOf]'s pass rather than re-lexing. */
fun guardTextOf(p: Path): String = guardCache.getOrPut(p) { squashImportsOut(codeOf(p)) }

/** Comments blanked, string literals and line structure kept. */
fun codeOf(p: Path): String = codeCache.getOrPut(p) { blankNonCode(text(p), blankStrings = false) }

/** Comments and string literals both blanked, line structure kept. */
fun blankedOf(p: Path): String = blankedCache.getOrPut(p) { blankNonCode(text(p)) }

val literalCache = HashMap<Path, Set<String>>()

/**
 * The distinct string literals a file spells, out of [guardTextOf]. The vocabulary guards ban
 * 160-odd literals between them; asking each file "which of these do you contain" is one pass and
 * a set lookup, where asking each literal "which files contain you" is 160 passes over the tree.
 */
fun quotedLiteralsOf(p: Path): Set<String> = literalCache.getOrPut(p) {
    // Lexed by hand, not by regex. The obvious pattern for a Java string literal is
    // `"((?:\\.|[^"\\])*)"`, and it worked until it didn't: an alternation inside a star makes
    // java.util.regex recurse once per repetition, so a long literal — a text block of embedded
    // TOML, a wide expected-output fixture — overflows the stack instead of matching. That surfaced
    // as `G12 checkNoBareTaskName: java.lang.StackOverflowError` on an incremental build, a gate
    // that crashed rather than judged, and it was intermittent because how much stack is left
    // depends on which thread the script ran on. One forward pass has no such cliff.
    val src = guardTextOf(p)
    val out = HashSet<String>()
    var i = 0
    while (i < src.length) {
        if (src[i] != '"') {
            i++
            continue
        }
        val body = StringBuilder()
        val opened = i++
        var closed = false
        while (i < src.length) {
            val c = src[i]
            when {
                // Escapes are kept verbatim: callers match against the source spelling.
                c == '\\' && i + 1 < src.length -> {
                    body.append(c).append(src[i + 1])
                    i += 2
                }
                c == '\\' -> i++
                c == '"' -> {
                    closed = true
                    i++
                }
                else -> {
                    body.append(c)
                    i++
                }
            }
            if (closed) break
        }
        // An unterminated quote is a `'"'` char literal, not a string; resume just past it rather
        // than swallowing the rest of the file as one enormous literal.
        if (closed) out.add(body.toString()) else i = opened + 1
    }
    out
}

fun rel(p: Path): String = root.relativize(p).toString().replace('\\', '/')

fun at(r: String): Path = root.resolve(r)

/** Regular files under [dir] with [suffix], sorted. Missing directories contribute none. */
fun filesUnder(dir: Path, suffix: String): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()
    val out = mutableListOf<Path>()
    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun visitFile(f: Path, a: BasicFileAttributes): FileVisitResult {
            if (a.isRegularFile && f.fileName.toString().endsWith(suffix)) out.add(f)
            return FileVisitResult.CONTINUE
        }
    })
    return out.sortedBy { rel(it) }
}

fun children(dir: Path): List<Path> =
    if (!Files.isDirectory(dir)) emptyList()
    else Files.list(dir).use { it.toList() }.filter { Files.isDirectory(it) }.sortedBy { rel(it) }

/**
 * Trees that are source but are not a module of either build, with the reason. Excluded BY NAME
 * and not by accident: the Gradle guards missed `clients/intellij` because a per-module `fileTree`
 * simply never reached a directory `settings.gradle.kts` does not include, which reads the same as
 * a rule that does not apply there. Stating it is what lets a reader tell the two apart.
 */
val nonModuleTrees = mapOf(
    "clients/intellij" to "a standalone IntelliJ plugin build that must never see a jk jar, so jk's"
        + " own vocabulary rules cannot reach it")

// Module directories, two segments deep — the same `<group>/<module>` glob the Gradle guards
// used. Two deep on purpose: a module's own target/ holds generated sample projects with their
// own src/main/java, and a scan that wandered into one would report someone else's code.
val moduleDirs: List<Path> = children(root)
    .filter { it.fileName.toString().let { n -> !n.startsWith(".") && n != "target" && n != "build" } }
    .flatMap { children(it) }
    .filterNot { rel(it) in nonModuleTrees }

/**
 * Every regular file in the checkout that a reader could see, minus build output, VCS metadata and
 * binaries. The two tree-wide guards (G49, G50) need this: their whole point is that a rule scoped
 * by extension is a rule that gets missed next time, so the walk is broad and the *guard* decides
 * what it cares about.
 *
 * Excluded by name rather than by accident, same reasoning as [nonModuleTrees].
 */
val treeFiles: List<Path> by lazy {
    val skipDirs = setOf("build", "target", ".git", ".gradle", ".firebase", "node_modules", ".board", ".kotlin")
    // CI installs jk into a home inside the checkout, and a home is a store: fetched artifacts,
    // metadata indexes and other people's prose, none of it this tree's. Pruned by what it is.
    val jkHome = System.getenv("JK_HOME")?.let { Path.of(it).toAbsolutePath().normalize() }
    val skipExt = listOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".jar", ".zip", ".xz", ".gz",
        ".class", ".aot", ".woff", ".woff2", ".ttf", ".pdf", ".so", ".dylib", ".exe")
    val out = mutableListOf<Path>()
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        // `build` and `target` name build output at a module root and a java package underneath a
        // source root, and pruning on the name alone loses the second: `cc.jumpkick.plugin.build`
        // is the plugin SPI, so every guard reading this walker was silently scanning one package
        // less of the tree than it claimed; a corpus floor is what catches that, which is what
        // corpus floors are for. Anything under a `src/` directory is source, whatever it is called.
        // A nested checkout — a git worktree, which CONTRIBUTING recommends for parallel work —
        // holds another branch's source. Recognised by what it is (a directory carrying its own
        // `.git`) rather than by name, because a name list is exactly what let one through.
        override fun preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult =
            if (d != root && Files.exists(d.resolve(".git"))) {
                FileVisitResult.SKIP_SUBTREE
            } else if (d != root && (d.toAbsolutePath().normalize() == jkHome || d.fileName.toString() == ".ci-jk-home")) {
                FileVisitResult.SKIP_SUBTREE
            } else if (d != root && d.fileName.toString() in skipDirs && !rel(d).contains("/src/")) {
                FileVisitResult.SKIP_SUBTREE
            } else {
                FileVisitResult.CONTINUE
            }

        override fun visitFile(f: Path, a: BasicFileAttributes): FileVisitResult {
            val name = f.fileName.toString()
            if (a.isRegularFile && skipExt.none { name.endsWith(it) }) out.add(f)
            return FileVisitResult.CONTINUE
        }
    })
    out.sortedBy { rel(it) }
}

fun javaIn(sourceSet: String): List<Path> =
    moduleDirs.flatMap { filesUnder(it.resolve("src/$sourceSet/java"), ".java") }

val mainJava: List<Path> = javaIn("main")
val testJava: List<Path> = javaIn("test")
val fixtureJava: List<Path> = javaIn("fixtures")

// The fixture set is a third corpus for G48 and the size caps, scanned by the Gradle gate under
// the same path. When it renamed once (testFixtures → fixtures) this scan kept its old name and
// passed every rule over nothing while G51 letter parity saw two identical guard sets.
if (fixtureJava.isEmpty()) error("gate scanned zero fixture sources under src/fixtures/java — the scope has moved.")

val pluginModules: List<Path> = children(at("plugins"))

// ---------------------------------------------------------------------------
// Guard plumbing
// ---------------------------------------------------------------------------

val problems = LinkedHashMap<String, String>()
val notes = mutableListOf<String>()
var guardsRun = 0

/**
 * Run one guard. A thrown message is that guard's failure; every other guard still runs, so
 * one build reports the whole set rather than the first one to fire.
 */
fun guard(id: String, name: String, body: () -> Unit) {
    guardsRun++
    try {
        body()
    } catch (e: Throwable) {
        problems["$id $name"] = e.message ?: e.toString()
    }
}

fun bullets(lines: List<String>): String = lines.joinToString("\n") { "  $it" }

// ---------------------------------------------------------------------------
// Text primitives
//
// Two habits every guard below inherits: match against CODE only — a banned literal named in
// javadoc is documentation, not a defect — and squash whitespace first, so wrapping a call
// across two lines cannot evade a pattern written on one.
// ---------------------------------------------------------------------------

/**
 * Blank comments — and, when [blankStrings], string / char / text-block literals too —
 * preserving length and line structure, so a name inside a `{@link}` or a fixture string is
 * not counted. [templateLiterals] treats backticks as literals for JS.
 */
fun blankNonCode(src: String, blankStrings: Boolean = true, templateLiterals: Boolean = false): String {
    val n = src.length
    val out = StringBuilder(n)
    var i = 0
    var line = false
    var block = false
    var textBlock = false
    var str = false
    var chr = false
    var tick = false
    // Character at a time, never a substring: the gate lexes ~16 MB three times per run, and a
    // two- and three-character `src.substring` per position was allocating its way through most
    // of the wall clock.
    fun ch(k: Int): Char = if (k < n) src[k] else ' '
    fun litChar(c: Char) = out.append(if (blankStrings) ' ' else c)
    fun litRun(from: Int, len: Int) {
        if (blankStrings) repeat(len) { out.append(' ') } else out.append(src, from, from + len)
    }
    fun tripleQuoteAt(k: Int): Boolean = ch(k) == '"' && ch(k + 1) == '"' && ch(k + 2) == '"'
    while (i < n) {
        val c = src[i]
        when {
            line -> if (c == '\n') { line = false; out.append(c) } else out.append(' ')
            block -> {
                if (c == '*' && ch(i + 1) == '/') { block = false; out.append("  "); i += 2; continue }
                out.append(if (c == '\n') '\n' else ' ')
            }
            textBlock -> {
                if (tripleQuoteAt(i)) { textBlock = false; litRun(i, 3); i += 3; continue }
                if (c == '\n') out.append('\n') else litChar(c)
            }
            str -> {
                if (c == '\\') { litRun(i, minOf(2, n - i)); i += 2; continue }
                if (c == '"') str = false
                litChar(c)
            }
            chr -> {
                if (c == '\\') { litRun(i, minOf(2, n - i)); i += 2; continue }
                if (c == '\'') chr = false
                litChar(c)
            }
            tick -> {
                if (c == '\\') { litRun(i, minOf(2, n - i)); i += 2; continue }
                if (c == '`') tick = false
                litChar(c)
            }
            c == '/' && ch(i + 1) == '/' -> { line = true; out.append("  "); i += 2; continue }
            c == '/' && ch(i + 1) == '*' -> { block = true; out.append("  "); i += 2; continue }
            tripleQuoteAt(i) -> { textBlock = true; litRun(i, 3); i += 3; continue }
            c == '"' -> { str = true; litChar('"'); i += 1; continue }
            c == '\'' -> { chr = true; litChar('\''); i += 1; continue }
            templateLiterals && c == '`' -> { tick = true; litChar('`'); i += 1; continue }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** Drop whitespace between tokens, keeping every string / char / text-block literal verbatim. */
fun squashBetweenLiterals(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '"' || c == '\'' -> {
                val close = if (c == '"' && src.startsWith("\"\"\"", i)) "\"\"\"" else c.toString()
                out.append(close)
                i += close.length
                while (i < src.length) {
                    if (src[i] == '\\') {
                        out.append(src, i, minOf(i + 2, src.length))
                        i += 2
                    } else if (src.startsWith(close, i)) {
                        out.append(close)
                        i += close.length
                        break
                    } else {
                        out.append(src[i])
                        i++
                    }
                }
            }
            c.isWhitespace() -> i++
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

/** Drop `import` / `package` lines from already-comment-blanked source, then squash. */
fun squashImportsOut(commentsBlanked: String): String =
    squashBetweenLiterals(
        commentsBlanked
            .lineSequence()
            .filterNot { val s = it.trimStart(); s.startsWith("import ") || s.startsWith("package ") }
            .joinToString("\n"))

/** The text a guard pattern is matched against: comments and imports gone, whitespace squashed. */
fun guardText(src: String): String = squashImportsOut(blankNonCode(src, blankStrings = false))

fun countIn(code: String, pattern: Regex): Int = pattern.findAll(code).count()

/** Occurrences of a fixed string. A plain scan, not a regex — the hot path counts 160 literals. */
fun countLiteral(code: String, literal: String): Int {
    var n = 0
    var i = code.indexOf(literal)
    while (i >= 0) {
        n++
        i = code.indexOf(literal, i + literal.length)
    }
    return n
}

/** Line number of [offset] in [text], 1-based. */
fun lineAt(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

/**
 * Code lines: comments, blanks and `package` / `import` lines do not count. A statement with a
 * trailing comment still counts. String and text-block contents count — a fixture is data.
 */
fun codeLines(p: Path, extension: String): Int {
    val src = text(p)
    if (src.isEmpty()) return 0
    val js = extension.equals("js", true) || extension.equals("mjs", true)
    // Java and Kotlin reuse the cached passes; JS needs its own, because a backtick template must
    // be lexed as a literal or a `/*` inside one eats the rest of the file.
    val visible = (if (js) blankNonCode(src, blankStrings = false, templateLiterals = true) else codeOf(p)).lines()
    val code = (if (js) blankNonCode(src, blankStrings = true, templateLiterals = true) else blankedOf(p)).lines()
    var lines = 0
    for (i in 0 until maxOf(visible.size, code.size)) {
        val seen = (visible.getOrNull(i) ?: "").trim()
        if (seen.isEmpty()) continue
        val body = (code.getOrNull(i) ?: "").trim()
        if (body.isEmpty() || !(body.startsWith("package ") || body.startsWith("import "))) lines++
    }
    return lines
}
/** Read an owner file, failing loudly when the guard has lost the thing it reads. */
fun owner(r: String): String {
    val p = at(r)
    if (!Files.isRegularFile(p)) error("the owner $r is gone, so this guard reads nothing. Restore it or retire the guard deliberately.")
    return text(p)
}

// ---------------------------------------------------------------------------
// Corpus floors. A green guard is evidence about the guard, not about the tree, so every
// scan states the population it was measured against and fails when it stops seeing it.
// ---------------------------------------------------------------------------

guard("G0", "checkCorpus") {
    val faults = mutableListOf<String>()
    if (moduleDirs.size < 25) faults.add("module dirs: ${moduleDirs.size}, measured against 33")
    if (mainJava.size < 1_150) faults.add("src/main/java: ${mainJava.size}, measured against 1,266")
    if (testJava.size < 900) faults.add("src/test/java: ${testJava.size}, measured against 1,009")
    if (pluginModules.size < 14) faults.add("plugins/*: ${pluginModules.size}, measured against 15")
    if (faults.isNotEmpty()) {
        error("The gate's corpus has shrunk below the population it was measured against, so every"
            + " scan below is reading less of the tree than it claims:\n" + bullets(faults)
            + "\n  Fix the walk before trusting a green run.")
    }
}

// ---------------------------------------------------------------------------
// G72 — the charter and the gate agree on the size caps, and the charter agrees with itself.
//
// The caps are enforced by [guards.file-size] in jk-guards.toml; a cap the charter states and the
// gate does not enforce is worse than no cap, because a reader trusts the table. The Contents
// list is a fact about the file, so it is derived and checked rather than maintained by hand.
// ---------------------------------------------------------------------------

guard("G72", "checkCharterTableParity") {
    val charterPath = at("docs/contributors/code-as-art.md")
    val charterLines = Files.readAllLines(charterPath)

    // Doc/guard parity: a cap the charter states and the gate does not enforce is worse than no
    // cap at all, because a reader trusts the table. An em dash means "exempt".
    val row = Regex("^\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|")
    val docCaps = LinkedHashMap<String, Int?>()
    var inTable = false
    charterLines.forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("| Language | Extensions |") -> inTable = true
            inTable && !line.startsWith("|") -> inTable = false
            inTable && !line.startsWith("|---") -> {
                row.find(line)?.let { m ->
                    val hardCell = m.groupValues[4].trim()
                    val hard = if (hardCell == "\u2014") null else hardCell.replace(",", "").toIntOrNull()
                    Regex("`\\.([a-z]+)`").findAll(m.groupValues[2]).forEach { docCaps[it.groupValues[1]] = hard }
                }
            }
        }
    }

    // The charter's Contents list is a fact about the file, so it is derived and checked rather
    // than maintained by hand.
    fun slug(t: String) = t.replace("`", "").lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().replace(' ', '-')
    val tocSlugs = charterLines.mapNotNull {
        Regex("^ *- \\[(.+)]\\(#([a-z0-9-]+)\\)$").find(it.trimEnd())?.groupValues?.get(2)
    }
    val headings = charterLines.mapNotNull { Regex("^(##|###) (.+)$").find(it)?.groupValues?.get(2) }
        .filter { it != "Contents" }.map(::slug)
    val tocDrift = mutableListOf<String>()
    headings.filterNot { it in tocSlugs }.forEach { tocDrift.add("missing from Contents: #$it") }
    tocSlugs.filterNot { it in headings }.forEach { tocDrift.add("in Contents, no such heading: #$it") }
    if (tocDrift.isEmpty() && tocSlugs != headings) {
        tocDrift.add("Contents lists every heading but in a different order")
    }
    if (tocDrift.isNotEmpty()) {
        error("code-as-art.md's Contents list and its headings disagree:\n" + bullets(tocDrift))
    }

    val drift = mutableListOf<String>()
    if (docCaps.isEmpty()) {
        drift.add("the Size table in code-as-art.md was not found; it must have a"
            + " `| Language | Extensions | Soft | Hard | Exception |` header and one backticked"
            + " extension per language")
    }
    // The enforced caps are [guards.file-size].cap in jk-guards.toml — the one owner.
    val rules = text(at("jk-guards.toml"))
    val capTable = Regex("""\[guards\.file-size]\n(?:(?!\n\[guards\.).)*?\ncap\s*=\s*\{([^}]*)}""", RegexOption.DOT_MATCHES_ALL)
        .find(rules)?.groupValues?.get(1)
        ?: error("jk-guards.toml no longer declares [guards.file-size] with a cap table, so this guard has lost the caps it compares.")
    val enforcedCaps = Regex("""(\w+)\s*=\s*(\d+)""").findAll(capTable).associate { it.groupValues[1] to it.groupValues[2].toInt() }
    if (enforcedCaps.isEmpty()) error("[guards.file-size].cap names no language, so this guard compares nothing.")
    (docCaps.keys + enforcedCaps.keys).toSortedSet().forEach { ext ->
        val doc = if (ext in docCaps) docCaps[ext]?.toString() ?: "exempt" else "absent"
        val task = enforcedCaps[ext]?.toString() ?: "exempt"
        if (doc != task) drift.add(".$ext: charter says $doc, gate enforces $task")
    }
    if (drift.isNotEmpty()) {
        error("The size caps in code-as-art.md and [guards.file-size] disagree. A cap the charter"
            + " states and the build does not enforce is worse than no cap:\n" + bullets(drift))
    }
}
// ---------------------------------------------------------------------------
// G2 — a hard process exit names its code, in `Exit`.
//
// `System.exit(n)` and `halt(n)` are the only integers a user's shell sees. A bare one is a
// meaning nobody wrote down: jk once shipped an exit `2` that meant eight things at once,
// including Ctrl-C. String bodies are blanked first, so an exit code inside a text block —
// generated source — is data, not this file's contract.
// ---------------------------------------------------------------------------

guard("G2", "checkNoBareExitCode") {
    val named = Regex("""public static final int (\w+) = (-?\d+);""")
        .findAll(owner("shared/host/src/main/java/cc/jumpkick/model/command/Exit.java"))
        .associate { it.groupValues[2] to it.groupValues[1] }
    val banned = listOf(Regex("""System\.exit\((-?\d+)\)"""), Regex("""\.halt\((-?\d+)\)"""))
    val hits = mainJava.flatMap { f ->
        val code = blankedOf(f).replace(Regex("\\s+"), "")
        banned.flatMap { p ->
            p.findAll(code).map { m ->
                val hint = named[m.groupValues[1]]?.let { "Exit.$it" } ?: "a named Exit constant (add one)"
                "${rel(f)}: ${m.value}  ->  $hint"
            }
        }
    }
    if (hits.isNotEmpty()) {
        error("A hard process exit is the one integer a user's script sees, and a bare one is a"
            + " meaning nobody wrote down. Name the code:\n" + bullets(hits.sorted())
            + "\n  cc.jumpkick.model.command.Exit is in :host, which every module already reaches."
            + " If no existing constant fits, add one with a javadoc line saying what it means — do"
            + " not reuse a code that already means something.")
    }
}
val deterministicZip = "shared/host/src/main/java/cc/jumpkick/host/DeterministicZip.java"

guard("G43", "checkArchiveStreamOwner") {
    if (!owner(deterministicZip).contains("public static OutputStream archiveStream(")) {
        error("DeterministicZip no longer declares archiveStream(...), so this guard has lost the"
            + " owner it points callers at.")
    }
    val ctor = Regex("""new\s+(Zip|Jar)OutputStream\s*\(""")
    val unbuffered = Regex("""new\s+(Zip|Jar)OutputStream\s*\(\s*Files\.newOutputStream""")
    val offenders = mutableListOf<String>()
    mainJava.filter { rel(it) != deterministicZip }.forEach { f ->
        val raw = text(f)
        if (!ctor.containsMatchIn(raw)) return@forEach
        val r = rel(f)
        raw.lines().forEachIndexed { i, line ->
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
            if (unbuffered.containsMatchIn(t)) offenders.add("$r:${i + 1}  $t")
        }
        if (raw.contains("Files.newOutputStream")
            && !raw.contains("DeterministicZip.archiveStream")
            && !raw.contains("DeterministicZip.newArchive")) {
            offenders.add("$r  builds an archive over Files.newOutputStream without DeterministicZip's sink")
        }
    }
    if (offenders.isNotEmpty()) {
        error("Unbuffered archive stream:\n" + bullets(offenders)
            + "\n  ZipOutputStream buffers at 512 bytes. Take the sink from the one owner:"
            + " new JarOutputStream(DeterministicZip.archiveStream(path)), or"
            + " DeterministicZip.newArchive(path) when a ZipOutputStream will do.")
    }
}
/** `key = ["a", "b"]` from a flat TOML section, or null when the key is absent. */
/** The body of `[name]` in [toml], up to the next table header. */
fun tomlSection(toml: String, name: String): String? {
    val start = Regex("""(?m)^\s*\[${Regex.escape(name)}]\s*$""").find(toml) ?: return null
    val rest = toml.substring(start.range.last + 1)
    val next = Regex("""(?m)^\s*\[""").find(rest)
    return if (next == null) rest else rest.substring(0, next.range.first)
}
// ---------------------------------------------------------------------------
// G29 — a worker's offline decision comes from the spec, not the daemon's environment.
//
// The engine is resident, so a `System.getenv("JK_OFFLINE")` inside a worker answers from
// whichever shell started the daemon: one `JK_OFFLINE=1 jk build` would pin every later build in
// that session offline. `TaskExec.offline()` is sealed by the engine at the fork.
// ---------------------------------------------------------------------------
val workerSources: List<Path> = pluginModules.flatMap { filesUnder(it.resolve("src/main/java"), ".java") } +
    filesUnder(at("shared/plugin-sdk/src/main/java"), ".java")

guard("G29", "checkWorkerOfflineFromSpec") {
    val taskExec = owner("shared/plugin-sdk/src/main/java/cc/jumpkick/plugin/build/TaskExec.java")
    if (!Regex("""boolean\s+offline\s*\(\s*\)""").containsMatchIn(taskExec)) {
        error("TaskExec no longer declares `boolean offline()`, so this guard has lost the owner it"
            + " points workers at.")
    }
    if (workerSources.size < 100) {
        error("scanned ${workerSources.size} worker Java files; measured against 119. The walk has"
            + " stopped seeing a tree.")
    }
    val propRead = Regex("""(?:getProperty|getBoolean)\s*\(\s*"([^"]*)"""")
    val argvLiteral = Regex(""""(-D[^"]*)"""")
    val hits = mutableListOf<String>()
    workerSources.forEach { f ->
        val code = codeOf(f)
        val r = rel(f)
        Regex("getenv").findAll(code).forEach { m ->
            val stop = code.indexOf(';', m.range.first).let { if (it < 0) code.length else it }
            if (code.substring(m.range.first, stop).contains("\"JK_OFFLINE\"")) {
                hits.add("$r:${lineAt(code, m.range.first)}: getenv of JK_OFFLINE")
            }
        }
        propRead.findAll(code).forEach { m ->
            if (m.groupValues[1].lowercase(Locale.ROOT).contains("offline")) {
                hits.add("$r:${lineAt(code, m.range.first)}: offline-keyed property read \"${m.groupValues[1]}\"")
            }
        }
        argvLiteral.findAll(code).forEach { m ->
            if (m.groupValues[1].lowercase(Locale.ROOT).contains("offline")) {
                hits.add("$r:${lineAt(code, m.range.first)}: offline -D literal \"${m.groupValues[1]}\"")
            }
        }
    }
    if (hits.isNotEmpty()) {
        error("A worker's offline decision comes from the spec — TaskExec.offline(), sealed by the"
            + " engine at the fork. Env/property reads here see the engine daemon's startup"
            + " environment, not this job:\n" + bullets(hits))
    }
}

// ---------------------------------------------------------------------------
// G30 — deterministic `.properties` rendering has one owner.
//
// `Properties.store()` prepends a #-dated comment line and emits keys in unspecified Hashtable
// order, so a caller that reaches for it ships a non-reproducible artifact.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// G30 — deterministic `.properties` rendering has one owner.
//
// `Properties.store()` prepends a #-dated comment line and emits keys in unspecified Hashtable
// order, so a caller that reaches for it ships a non-reproducible artifact. A pure ban with no
// owner that uses the primitive has no bite evidence a rule can carry until fixtures land, so it
// stays here for now.
// ---------------------------------------------------------------------------

guard("G30", "checkPropertiesStoreOwner") {
    val ownerPath = "shared/host/src/main/java/cc/jumpkick/host/DeterministicProperties.java"
    if (!Regex("""String\s+render\s*\(""").containsMatchIn(owner(ownerPath))) {
        error("DeterministicProperties no longer declares render(...), so this guard has lost the"
            + " owner it points callers at.")
    }
    var importing = 0
    val hits = mutableListOf<String>()
    mainJava.forEach { f ->
        // Strings blanked as well as comments: a javadoc that merely mentions Properties.store()
        // stays invisible, and so does a `.store(` inside a literal.
        val code = blankedOf(f)
        if (!code.contains("import java.util.Properties;")) return@forEach
        importing++
        code.lines().forEachIndexed { i, line ->
            if (line.contains(".store(")) hits.add("${rel(f)}:${i + 1}: ${line.trim()}")
        }
    }
    if (importing == 0) {
        error("found no main-source file importing java.util.Properties; measured against 14. The"
            + " walk has stopped seeing the tree.")
    }
    if (hits.isNotEmpty()) {
        error("Properties.store() writes a #-dated comment line in Hashtable order — a"
            + " non-reproducible artifact. Render through"
            + " cc.jumpkick.host.DeterministicProperties.render instead:\n" + bullets(hits))
    }
}

// ---------------------------------------------------------------------------
// G35 — a test that reads a checkout file locates it from the checkout root.
//
// Gradle runs a test with CWD at the owning module; a workspace `jk build` runs it with CWD at
// the engine's state dir. A test that spelled a shipped manifest as
// `Path.of(System.getProperty("user.dir"), "../../plugins/android/jk-plugin.toml")` went red
// under jk and stayed green under Gradle for months. The fix was one fixture, because the walk it
// replaced had been copied into fourteen test classes — three of which degraded to a skip when
// the search failed, so a broken search read as a pass.
// ---------------------------------------------------------------------------

guard("G35", "checkTestPathsFromCheckoutRoot") {
    val ownerPath = "shared/host/src/fixtures/java/cc/jumpkick/testing/RepoRoot.java"
    // Raw text, not guardText: a signature probe has to allow for whitespace or be written
    // unreadably as `staticPathfind(`.
    val ownerText = owner(ownerPath)
    val ownerApi = listOf(
        """getProtectionDomain\(""",
        """static\s+Path\s+find\s*\(""",
        """static\s+Path\s+file\s*\(""",
        """static\s+Path\s+dir\s*\(""")
        .filterNot { Regex(it).containsMatchIn(ownerText) }
    if (ownerApi.isNotEmpty()) {
        error("RepoRoot no longer has ${ownerApi.joinToString(", ")}, so this guard is redirecting"
            + " tests to something that cannot serve them.")
    }
    val hits = mutableListOf<String>()
    (testJava + fixtureJava).filter { rel(it) != ownerPath }.forEach { f ->
        val r = rel(f)
        val walks = countLiteral(guardTextOf(f), "getProtectionDomain")
        if (walks > 0) {
            hits.add("$r: $walks x getProtectionDomain  ->  RepoRoot.find/file/dir(<ThisTest>.class, \"<path-from-root>\")")
        }
        // codeOf, not guardTextOf, for the per-line arm: guardText joins lines, so a line number
        // taken from it is always 1.
        codeOf(f).lines().forEachIndexed { i, line ->
            if (line.contains("user.dir") && (line.contains("\"..") || line.contains("/..\""))) {
                hits.add("$r:${i + 1}: user.dir escaped with `..`  ->  RepoRoot.file(<ThisTest>.class, \"<path-from-root>\")")
            }
        }
    }
    if (hits.isNotEmpty()) {
        error("A test that reads a file out of the source tree names it from the checkout root, via"
            + " cc.jumpkick.testing.RepoRoot. These resolve it against the working directory instead,"
            + " which differs between Gradle and a workspace `jk build`:\n" + bullets(hits.sorted())
            + "\n  A path spelled from the root is the same under both builds; a path spelled from"
            + " CWD is not.")
    }
}

// ---------------------------------------------------------------------------
// G37 — recursive tree deletion has one owner, and it does not follow links.
//
// jk links its toolchain registry entries at host-installed JDKs, so a delete that follows a link
// reaches files jk did not install and must not remove. What makes it worth enforcing rather than
// documenting is how quiet the rule is: both `Files.walk` and `Files.walkFileTree` decline to
// follow links by DEFAULT, so the correct behaviour is the ABSENCE of a FileVisitOption —
// "cleanup left files behind" reads like a missing FOLLOW_LINKS, and adding that one enum
// constant turns a cleanup into a data-loss bug.
// ---------------------------------------------------------------------------
val recursiveDeleteExemptions = mapOf(
    "server/engine/src/main/java/cc/jumpkick/runtime/BuildLogicSupport.java"
        to "deleteContents keeps the directory and removes only what is under it",
    "server/engine/src/main/java/cc/jumpkick/runtime/CachePlans.java"
        to "tallies bytes per tag and honours --dry-run, so it cannot delegate the walk",
    "server/engine/src/main/java/cc/jumpkick/task/ActionCache.java"
        to "pruneUnowned deletes only files absent from the owned set, plus emptied dirs",
    "server/engine/src/main/java/cc/jumpkick/task/CacheRetention.java"
        to "pruneEmptyDirs deletes a directory only when it is already empty")

val pathUtilOwner = "shared/host/src/main/java/cc/jumpkick/host/PathUtil.java"

guard("G37", "checkOneRecursiveDelete") {
    val ownerText = owner(pathUtilOwner)
    val needs = listOf("walkFileTree", "NOFOLLOW_LINKS", "deleteRecursivelyOrThrow").filterNot { ownerText.contains(it) }
    if (needs.isNotEmpty()) {
        error("PathUtil no longer has ${needs.joinToString(", ")}, so this guard points at something"
            + " that cannot serve the callers it redirects.")
    }
    val hits = mutableListOf<String>()
    mainJava.filter { rel(it) != pathUtilOwner }.forEach { f ->
        val r = rel(f)
        val lines = codeOf(f).lines()
        // Delegation counts too: a file whose only delete is PathUtil.deleteRecursively still has
        // no business asking a walk to follow links.
        val deletes = lines.any {
            it.contains(".delete(") || it.contains("deleteIfExists(") || it.contains("deleteRecursively")
        }
        if (!deletes) return@forEach
        lines.forEachIndexed { i, line ->
            if (line.contains("FOLLOW_LINKS") && !line.contains("NOFOLLOW_LINKS")) {
                hits.add("$r:${i + 1}: FOLLOW_LINKS in a file that deletes  ->  drop it; a link is removed, never entered")
            }
        }
        if (r in recursiveDeleteExemptions) return@forEach
        lines.forEachIndexed { i, line ->
            if (!line.contains("reverseOrder")) return@forEachIndexed
            val window = lines.subList(i, minOf(i + 9, lines.size)).joinToString("\n")
            if (window.contains(".delete(") || window.contains("deleteIfExists(")) {
                hits.add("$r:${i + 1}: children-first walk that deletes  ->  PathUtil.deleteRecursively / deleteRecursivelyOrThrow")
            }
        }
    }
    if (hits.isNotEmpty()) {
        error("Recursive tree deletion belongs to cc.jumpkick.host.PathUtil, which removes a symbolic"
            + " link instead of entering it. These do it themselves, so each one decides that"
            + " question again:\n" + bullets(hits.sorted())
            + "\n  A delete that is genuinely selective (only empty directories, only unowned files,"
            + " only the contents) is not a tree delete: add it to `recursiveDeleteExemptions` with"
            + " the reason, so the next reader can tell the two apart.")
    }
}

// ---------------------------------------------------------------------------
// G38 — a toolchain env var is read from the request, not from the daemon.
//
// The engine is resident, so `System.getenv("JK_JDK")` inside it answers from whichever shell
// started the daemon — possibly days earlier. The user-visible consequence is not "the override
// is ignored" but "which JDK you compile against depends on how the daemon happened to be
// started", so `jk engine stop` changed build output.
//
// `clients/` is exempt BY SHAPE: the native CLI is a one-shot process running inside the caller's
// own shell, so there `System.getenv` IS the request.
// ---------------------------------------------------------------------------

guard("G38", "checkToolchainEnvFromRequest") {
    val names = Regex("""public static final List<String> TOOLCHAIN = List\.of\(([^)]*)\);""")
        .find(owner("shared/core/src/main/java/cc/jumpkick/config/BuildEnv.java"))?.groupValues?.get(1)
        ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
        .orEmpty()
    if (names.isEmpty()) {
        error("BuildEnv.TOOLCHAIN no longer declares its names as a List.of(...) literal, so this"
            + " guard has lost the owner it reads.")
    }
    val offenders = mutableListOf<String>()
    mainJava.filterNot { rel(it).startsWith("clients/") }.forEach { f ->
        val lines = text(f).lines()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
            for (n in names) {
                if (!line.contains("System.getenv(\"$n\")")) continue
                // Exempt by shape: a fallback for this process's own java.home is genuinely ambient.
                val window = lines.subList(maxOf(0, i - 8), i).joinToString(" ")
                if (window.contains("System.getProperty(\"java.home\")")) continue
                offenders.add("${rel(f)}:${i + 1}  $line")
            }
        }
    }
    if (offenders.isNotEmpty()) {
        error("Toolchain env read outside BuildEnv:\n" + bullets(offenders)
            + "\n  These select a toolchain, so on a resident engine System.getenv answers from the"
            + " shell that started the daemon. Use BuildEnv.forModule(dir) where a module directory"
            + " is in hand, or BuildEnv.ambient() where none is."
            + "\n  Ban list read from BuildEnv.TOOLCHAIN: ${names.joinToString(", ")}")
    }
}

guard("G42", "checkTreeCopyOwner") {
    if (!owner(pathUtilOwner).contains("public static void copyTree(")) {
        error("PathUtil no longer declares copyTree(...), so this guard has lost the owner it points callers at.")
    }
    val allowed = setOf(
        // Rewrites each path segment as it goes (leading '/' and '.' handling) and refuses to
        // recreate symlinks; the owner copies a tree verbatim and has no segment policy.
        "server/engine/src/main/java/cc/jumpkick/giter8/PluginTemplates.java")
    val offenders = mutableListOf<String>()
    mainJava.forEach { f ->
        val r = rel(f)
        if (r == pathUtilOwner || r in allowed) return@forEach
        val lines = text(f).lines()
        lines.forEachIndexed { i, raw ->
            if (!raw.contains("Files.copy(")) return@forEachIndexed
            // The shape of a *tree* copy: the target is rebuilt from a walk-relative path. A
            // single-file copy, an archive-entry extraction, and a flat first-wins merge over
            // Files.list all copy without relativizing, and none of them is what this bans.
            val window = lines.subList(maxOf(0, i - 6), i + 1).joinToString("\n")
            if (!window.contains("relativize(")) return@forEachIndexed
            offenders.add("$r:${i + 1}")
        }
    }
    if (offenders.isNotEmpty()) {
        error("A hand-rolled recursive copy:\n" + bullets(offenders)
            + "\n  Use PathUtil.copyTree(from, to): directories created once per directory,"
            + " attributes taken from the walk, and a byte-identical target left alone so its mtime"
            + " does not invalidate every downstream FreshnessStamp."
            + "\n  A copy that is genuinely not a tree copy belongs on this guard's exemption list"
            + " with the reason.")
    }
}

// ---------------------------------------------------------------------------
// Rules that read exactly ONE module are not here.
//
// They are JUnit tests in the module whose sources they read — the forecast/build key
// parity scan in :engine, the IDE-client wiring and the CLI reachability rules in :cli, and
// the two test-hygiene rules beside the suites they govern. The charter's warning about
// guards-as-tests is about CROSS-module reads: `ActionTreeTest` passed with a violation
// reintroduced because nothing declared other modules' sources as its inputs. A rule scoped to
// one module is covered by that module's own test inputs under both builds, and as a test it
// gets assertions, a debugger and a failure report instead of 660 lines of script.
//
// What stays here is what genuinely spans modules. See code-as-art.md, "Where a guard runs".
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Guards that had no twin here until the parity sweep
//
// Each of these was written on the Gradle side and stayed there, so `jk build` reported the house
// rules green while enforcing five fewer of them than `./gradlew build`. That is worse than an
// unenforced rule: the self-hosted build is the one contributors run, and it was the one lying.
// G51 below is the ratchet that stops the next one drifting.
// ---------------------------------------------------------------------------

guard("G47", "checkCaseConversionLocale") {
    if (mainJava.isEmpty()) {
        error("G47 scanned zero main sources — the gate has silently lost its scope.")
    }
    val bare = Regex("""\.to(?:Lower|Upper)Case\(\)""")
    val hits = mainJava.mapNotNull { f ->
        val n = countIn(guardTextOf(f), bare)
        if (n > 0) "${rel(f)}: $n" else null
    }
    if (hits.isNotEmpty()) {
        error("A case conversion without Locale.ROOT misparses under tr_TR/az ('i' ⇄ 'I' do not"
            + " round-trip — \"MAIN\".toLowerCase() is \"maın\"). Pass Locale.ROOT; identifiers"
            + " are not user language:\n" + bullets(hits))
    }
}

// ---------------------------------------------------------------------------
// Guard G61: a test that runs the install verb redirects the Maven local repo.
//
// `[m2] install` defaults on, so an install's primary destination is the Maven local repo — and a
// test JVM inherits the real home, so that is the developer's own ~/.m2. A workspace-install test
// with no --m2-dir published its fixture jar into ~/.m2/repository/cc/jumpkick on every run, and
// the same gap hid a defect: --m2-dir never rode the workspace wire, so the redirect the tests
// that DID pass it asked for was not applied by the engine either. Neither is visible from a
// green suite — the write lands outside the checkout, in a directory no assertion looks at.
//
// Every install invocation, not only the ones whose branch writes ~/.m2 today. A test cannot see
// which branch its install takes, and the file / coordinate modes are one refactor from the m2
// one; a flag that is inert on those paths is cheaper than a rule with exceptions.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Guard G51: both builds enforce the same house rules.
//
// The mirror of the Gradle `checkGuardParity` task, and deliberately duplicated: a parity check
// that only one build runs has exactly the shape of the problem it exists to prevent. What is NOT
// duplicated is the exception list — `guard-parity.txt` is the single owner, and both sides read
// it, so a letter can never be excused on one side and demanded on the other.
//
// What went wrong without it: G46, G47, G48, G49 and G50 were written on the Gradle side and never
// grew a twin here, so `jk build` printed "house rules clean" while enforcing 36 of the 41 it
// claimed. Both gates print a count, and neither count is wrong about itself, so nothing surfaced.
// ---------------------------------------------------------------------------

guard("G51", "checkGuardParity") {
    // The same four spellings the registry scan uses, for the same reason: a scan for any subset
    // silently misses the rest.
    val marker = Regex("""(?m)^\s*(?://|/\*)?\s*(?:Guard G(\d+)\b|G(\d+)\s*—)|guard\("G(\d+)"""")
    fun lettersIn(body: String): Set<Int> = marker.findAll(body)
        .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() }.toInt() }
        .toSortedSet()

    val gradleScripts = treeFiles.filter {
        val here = rel(it)
        here.endsWith(".gradle.kts") || (here.startsWith("buildSrc/src/main/kotlin/") && here.endsWith(".kts"))
    }
    if (gradleScripts.isEmpty()) {
        error("Found no Gradle build scripts to compare against — the tree walk broke and this"
            + " guard is passing vacuously.")
    }
    val gradle = lettersIn(gradleScripts.joinToString("\n") { text(it) })
    // A letter is also jk-enforced when its registered jk-guards.toml rule exists. The registry
    // (`Guards.kt`) is read by regex here, as the registry scan does: each `spec(` block that names
    // a letter and a `ruleId`. A registered rule with no table, or a table no letter claims, is the
    // registry lagging the code.
    val specBlock = Regex("""spec\(\s*(\d+),(?:(?!spec\().)*?ruleId = "([a-z0-9][a-z0-9-]*)"""", RegexOption.DOT_MATCHES_ALL)
    val mapped = specBlock.findAll(text(at("buildSrc/src/main/kotlin/Guards.kt")))
        .associate { it.groupValues[1].toInt() to it.groupValues[2] }
    val rulesFile = at("jk-guards.toml")
    val tables = if (Files.isRegularFile(rulesFile)) {
        Regex("""(?m)^\[guards\.([a-z0-9][a-z0-9-]*)]""").findAll(text(rulesFile)).map { it.groupValues[1] }.toSortedSet()
    } else sortedSetOf<String>()
    val registryFaults = mutableListOf<String>()
    mapped.filterValues { it !in tables }.forEach { (n, id) ->
        registryFaults.add("Guards.kt says G$n is enforced by [guards.$id] but jk-guards.toml has no such table")
    }
    (tables - mapped.values.toSet()).forEach { id ->
        registryFaults.add("jk-guards.toml declares [guards.$id] but no Guards.kt letter claims it (ruleId = \"$id\")")
    }
    if (registryFaults.isNotEmpty()) {
        error("The guard registry and jk-guards.toml disagree:\n" + bullets(registryFaults))
    }
    // A letter is also jk-enforced when the registry marks it an engine validation: the engine runs
    // it in the guard lanes of every `jk build`, so there is no table and no script block to find.
    val engineBlock = Regex("""spec\(\s*(\d+),(?:(?!spec\().)*?engineCode = "([a-z0-9-]+)"""", RegexOption.DOT_MATCHES_ALL)
    val engine = engineBlock.findAll(text(at("buildSrc/src/main/kotlin/Guards.kt"))).map { it.groupValues[1].toInt() }.toSet()
    // ... and when it names the @Guard (a guard test under some module's src/guard) that enforces it.
    val guardTestBlock = Regex("""spec\(\s*(\d+),(?:(?!spec\().)*?guardTestId = "([a-z0-9-]+)"""", RegexOption.DOT_MATCHES_ALL)
    val guardTests = guardTestBlock.findAll(text(at("buildSrc/src/main/kotlin/Guards.kt"))).map { it.groupValues[1].toInt() }.toSet()
    val jk = (lettersIn(text(at(".jk/after-build.kts"))) + mapped.filterValues { it in tables }.keys + engine + guardTests).toSortedSet()
    if (gradle.isEmpty() || jk.isEmpty()) {
        error("Read no guard letters from one of the two sides (gradle=${gradle.size},"
            + " jk=${jk.size}) — the scan broke and this guard is passing vacuously.")
    }
    val excused = Regex("""(?m)^G(\d+)\s""")
        .findAll(text(at("guard-parity.txt"))).map { it.groupValues[1].toInt() }.toSet()
    if (excused.isEmpty()) {
        error("guard-parity.txt lists no letters, so this guard would pass over anything.")
    }
    val gradleOnly = (gradle - jk - excused).sorted()
    val jkOnly = (jk - gradle - excused).sorted()
    // An exception nobody needs is a rule quietly weakened: it tells a reader parity was
    // impossible when it is now a fact.
    val stale = excused.filter { it in gradle && it in jk }.sorted()
    val faults = mutableListOf<String>()
    if (gradleOnly.isNotEmpty()) {
        faults.add("enforced by Gradle only: " + gradleOnly.joinToString(", ") { "G$it" }
            + " — add the twin here")
    }
    if (jkOnly.isNotEmpty()) {
        faults.add("enforced by this gate only: " + jkOnly.joinToString(", ") { "G$it" }
            + " — add the twin to buildSrc, or record in guard-parity.txt why it is"
            + " self-hosted-only")
    }
    if (stale.isNotEmpty()) {
        faults.add("excused in guard-parity.txt but present on BOTH sides: "
            + stale.joinToString(", ") { "G$it" } + " — drop the entry, parity is real now")
    }
    if (faults.isNotEmpty()) {
        error("The two builds do not enforce the same house rules:\n" + bullets(faults)
            + "\n  A contributor runs `jk build`; a gate that enforces less than it claims is worse"
            + " than no gate. Port the rule, or record in guard-parity.txt why the letter cannot"
            + " live in both.")
    }
}

// ---------------------------------------------------------------------------
// Verdict
// ---------------------------------------------------------------------------

notes.forEach { println("jk gate: $it") }

if (problems.isEmpty()) {
    println("jk gate: $guardsRun guards over ${mainJava.size} main + ${testJava.size} test sources in ${moduleDirs.size} modules — clean")
} else {
    val body = problems.entries.joinToString("\n\n") { (id, msg) ->
        "──────── $id ────────\n$msg"
    }
    error("\n\n${problems.size} of $guardsRun house rules are broken"
        + " (docs/contributors/code-as-art.md):\n\n$body\n")
}
