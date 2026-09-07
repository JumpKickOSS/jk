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

/** A Java char literal, escaped or not. */
val charLiteral = """'(?:\\.|[^\\'])'"""

/** Every char appearing as a `case` label in already-squashed [code], multi-label arms included. */
fun caseLabelChars(code: String): Set<String> =
    Regex("""(?<![\w$])case\s*((?:$charLiteral\s*,\s*)*$charLiteral)\s*(?:->|:)""")
        .findAll(code)
        .flatMap { arm -> Regex(charLiteral).findAll(arm.groupValues[1]) }
        .map { it.value.removeSurrounding("'") }
        .toSet()

/**
 * Judge per-file hit counts against an allowlist: `(grew, unlisted, loose)`. Growth and an
 * unlisted file are failures. A file that shrank — or went clean — is loose, which passes and
 * prints the tightened entry to paste back, so the sweep that clears a guard is never blocked
 * by the guard itself.
 */
fun ratchetVerdict(
    hits: Map<String, Int>,
    allowed: Map<String, Int>,
): Triple<List<String>, List<String>, List<String>> {
    val grew = mutableListOf<String>()
    val unlisted = mutableListOf<String>()
    val loose = mutableListOf<String>()
    hits.toSortedMap().forEach { (r, n) ->
        val a = allowed[r]
        when {
            a == null -> unlisted.add("%5d  %s".format(n, r))
            n > a -> grew.add("$r: $n sites, allowed $a (+${n - a})")
            n < a -> loose.add("%5d  %s   (was %d)".format(n, r, a))
        }
    }
    allowed.toSortedMap().forEach { (r, a) -> if (r !in hits) loose.add("(clean) $r   (was $a)") }
    return Triple(grew, unlisted, loose)
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
// G1 — one owner for a JDK's launcher path.
//
// Hand-building `<javaHome>/bin/java` silently drops the Windows `.exe` and the fork is dead
// there. The ban list is derived from the owner's own `public static Path <name>(Path javaHome)`
// shorthands and its Windows suffix, so teaching the owner a third launcher bans hand-building
// that one the same minute.
// ---------------------------------------------------------------------------
val jdkFingerprint = "shared/host/src/main/java/cc/jumpkick/jdk/JdkFingerprint.java"

guard("G1", "checkNoHandBuiltJavaBinary") {
    val ownerText = owner(jdkFingerprint)
    val names = Regex("""public static Path (\w+)\(Path javaHome\)""")
        .findAll(ownerText).map { it.groupValues[1] }.toList()
    if (names.isEmpty()) {
        error("JdkFingerprint declares no `public static Path <name>(Path javaHome)` shorthand, so"
            + " this guard has lost the ban list it reads.")
    }
    val suffix = Regex("""tool \+ "([^"]+)"""").find(ownerText)?.groupValues?.get(1)
        ?: error("JdkFingerprint.toolName no longer appends a literal suffix, so this guard cannot"
            + " see the rule it enforces.")
    val banned = names.flatMap { n ->
        listOf(
            """resolve("bin/$n")""",
            """resolve("bin").resolve("$n")""",
            """resolve("bin").resolve("$n$suffix")""",
            ""","bin","$n"""",
            ""","bin","$n$suffix"""",
            """"$n$suffix":"$n"""",
            """"$n":"$n$suffix"""")
    }
    val hits = mainJava.filter { rel(it) != jdkFingerprint }.flatMap { f ->
        val code = guardTextOf(f)
        banned.filter { code.contains(it) }.map { "${rel(f)}: $it" }
    }
    if (hits.isNotEmpty()) {
        error("A hand-built <javaHome>/bin/java drops the Windows `.exe` and the fork is dead there."
            + " ${hits.size} site(s):\n" + bullets(hits)
            + "\n  Call JdkFingerprint.java(javaHome), .javac(javaHome), or .tool(javaHome, name)."
            + " It is on the :host leaf, so every module reaches it — including a plugin worker."
            + " A launcher that is NOT a JDK tool under <home>/bin (mvn, gradle, kotlinc,"
            + " native-image) is a different vocabulary and is not in scope.")
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

// ---------------------------------------------------------------------------
// G7 — one truth set, and it lives in `EnvValues.parseBool`.
//
// `JK_FOO=yes` working in one reader and not the next. The pattern is the WHOLE truth set on
// either side of equals/equalsIgnoreCase: a one-sided pattern measures at most half the defect,
// and the false side is where the bypasses were hiding. Every entry below is permanent — a
// reader of someone else's format, or a comparison that is not a boolean at all.
// ---------------------------------------------------------------------------
val truthSetRatchet = mapOf(
    // Maven POM XML: `<optional>` and `<activeByDefault>` are xs:boolean, `true` only.
    "server/io/src/main/java/cc/jumpkick/repo/PomParser.java" to 1,
    "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java" to 1,
    // The disco JDK catalog is JSON: `true`/`false`, never `yes`.
    "shared/toolchain-jdk/src/main/java/cc/jumpkick/jdk/JdkCatalogClient.java" to 2,
    // giter8 template booleans are `y`/`yes`/`true` — a different set on purpose.
    "server/engine/src/main/java/cc/jumpkick/giter8/Giter8Value.java" to 2,
    // jk's own on-disk memo row stores the bit as literal `1`, so parts[2] is not user input.
    "server/engine/src/main/java/cc/jumpkick/runtime/PreflightMemo.java" to 1,
    // A version string that is literally "0", and a version segment that is literally "0".
    "plugins/quarkus/src/main/java/cc/jumpkick/quarkus/LockedClosure.java" to 1,
    "server/resolver/src/main/java/cc/jumpkick/resolver/VersionSelectors.java" to 1,
    // Wizard menu ids. The prompt offers exactly two and a lenient parse would silently accept
    // an answer the menu never showed.
    "clients/cli/src/main/java/cc/jumpkick/command/ActivateCommand.java" to 1,
    "clients/cli/src/main/java/cc/jumpkick/command/JdkInstallWizard.java" to 1)

guard("G7", "checkSingleTruthSet") {
    val truthy = "true|1|yes|on|false|0|no|off"
    val handRolled = Regex(
        """"(?:$truthy)"\.equals(?:IgnoreCase)?\(|\.equals(?:IgnoreCase)?\("(?:$truthy)"\)""")
    val hits = LinkedHashMap<String, Int>()
    mainJava.forEach { f ->
        val n = countIn(guardTextOf(f), handRolled)
        if (n > 0) hits[rel(f)] = n
    }
    val (grew, unlisted, loose) = ratchetVerdict(hits, truthSetRatchet)
    val faults = mutableListOf<String>()
    if (unlisted.isNotEmpty()) {
        faults.add("jk has one boolean truth set and it is EnvValues.parseBool — 1/true/yes/on against"
            + " 0/false/no/off, trimmed, case-insensitive. These compare by hand and are not on the"
            + " ratchet:\n" + bullets(unlisted)
            + "\n  Call cc.jumpkick.config.EnvValues.parseBool(raw) (or .bool(env, name) for a JK_*"
            + " variable); it is in :host, so every module can reach it. A reader of someone else's"
            + " format — Maven's xs:boolean, giter8's y/yes — or a comparison that is not a boolean"
            + " at all is an exemption, and says so here.")
    }
    if (grew.isNotEmpty()) faults.add("A file on the truth-set ratchet may only shrink. These grew:\n" + bullets(grew))
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
    if (loose.isNotEmpty()) notes.add("truthSetRatchet is loose (these shrank — tighten it in this commit):\n" + bullets(loose))
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

// ---------------------------------------------------------------------------
// G21 — JSON is escaped in one place and parsed in one place.
//
// Exemption is BY SPEC, not by filename, because two escapers can both be correct:
// `MinimalToml.quote` is character-for-character the same method as `Jsonl.quote`, and merging
// them would be a regression. So each arm carries the discriminator its direction actually has —
// the write arm needs a JSON OBJECT literal beside the escaper, and the read arm keys on `\/`,
// which is legal in JSON and illegal in a TOML basic string.
// ---------------------------------------------------------------------------
val jsonCodecOwners = listOf(
    "shared/host/src/main/java/cc/jumpkick/jsonl/Jsonl.java",
    "shared/host/src/main/java/cc/jumpkick/jsonl/MiniJson.java")

/** A JSON object literal written into Java source: `"…\"key\":…"`. Nothing else spells that. */
val jsonObjectLiteral = Regex("""\\"[A-Za-z_][A-Za-z0-9_.\-]*\\"\s*:""")

guard("G21", "checkOneJsonCodec") {
    val ownerCode = guardText(owner(jsonCodecOwners.first()))
    val unicodeEscape = Regex("""String\.format\(("[^"]*u%04[xX]")""").find(ownerCode)?.groupValues?.get(1)
    val ownerEscapes = caseLabelChars(ownerCode)
    if (unicodeEscape == null || !ownerEscapes.containsAll(listOf("/", "u", "n"))) {
        error("Jsonl no longer yields the escape alphabet this guard reads from it: unicode fallback"
            + " ${unicodeEscape ?: "MISSING"}, decoded escapes $ownerEscapes. Restore the codec's"
            + " shape or retire this guard deliberately — do not re-type the alphabet here, which is"
            + " the defect the guard exists to prevent.")
    }
    // Case-insensitive on the hex conversion only: `%04X` is the same escaper, shouting.
    val unicodePattern = Regex(Regex.escape(unicodeEscape), RegexOption.IGNORE_CASE)
    val decodesJson = ownerEscapes.filter { it != "/" }

    val writers = mutableListOf<String>()
    val readers = mutableListOf<String>()
    mainJava.forEach { f ->
        val r = rel(f)
        if (r in jsonCodecOwners) return@forEach
        val code = guardTextOf(f)
        val labels = caseLabelChars(code)
        if (unicodePattern.containsMatchIn(code) && "\"" in labels && jsonObjectLiteral.containsMatchIn(code)) {
            writers.add(r)
        }
        if ("/" in labels && labels.count { it in decodesJson } >= 4) readers.add(r)
    }
    val faults = mutableListOf<String>()
    if (writers.isNotEmpty()) {
        faults.add("jk escapes a JSON string in one place, `Jsonl.quote`. These assemble a JSON object"
            + " with an escaper of their own:\n" + bullets(writers)
            + "\n  Call Jsonl.quote for one string, or hand the whole document to MiniJson.write /"
            + " writePretty. An escaper for a DIFFERENT format is not in scope and must not borrow"
            + " Jsonl either: TOML basic strings go through MinimalToml.quote, XML through"
            + " MinimalXml, and a new format gets its own owner beside them.")
    }
    if (readers.isNotEmpty()) {
        faults.add("jk parses JSON in one place, `MiniJson`. These decode JSON's escape alphabet"
            + " themselves:\n" + bullets(readers)
            + "\n  MiniJson.parse gives you Map/List/String/Double/Boolean, with a nesting cap a"
            + " hand-rolled recursive descent does not have — and a StackOverflowError is an Error,"
            + " so the catch that was meant to make a bad document a no-op will not catch it.")
    }
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
}

// ---------------------------------------------------------------------------
// G23 — every @Tag is run by exactly one test tier.
//
// A `@Tag` is a routing decision, and when the routing lives in two half-tables nobody can see
// together, a tag ends up excluded by one and included by none — a test that never executes and
// never goes red. The table is jk's own: `[test]` and `[profiles.*]` in the root manifest, read
// here rather than restated, so the routing and the check cannot drift.
//
// Three arms. TOTALITY is the prover and reads no source at all: every subset of the vocabulary,
// enumerated rather than sampled, must be run by exactly one tier. NO ORPHAN is the locator —
// given totality it cannot fail, and it exists to name the FILE AND LINE when totality does.
// NO UNOWNED VOCABULARY is the closure check that catches `@Tag("intergration")`.
// ---------------------------------------------------------------------------

/** One tier: the tag filters a `jk test` invocation runs with. */
data class Tier(val name: String, val include: Set<String>, val exclude: Set<String>) {
    /** JUnit's own tag semantics: runs when the include set is empty or intersects, and exclude does not. */
    fun runs(tags: Set<String>): Boolean =
        (include.isEmpty() || tags.any { it in include }) && tags.none { it in exclude }
}

/** `key = ["a", "b"]` from a flat TOML section, or null when the key is absent. */
fun tomlStringList(section: String, key: String): List<String>? {
    val m = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*\[([^\]]*)]""").find(section) ?: return null
    return Regex(""""([^"]*)"""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
}

/** The body of `[name]` in [toml], up to the next table header. */
fun tomlSection(toml: String, name: String): String? {
    val start = Regex("""(?m)^\s*\[${Regex.escape(name)}]\s*$""").find(toml) ?: return null
    val rest = toml.substring(start.range.last + 1)
    val next = Regex("""(?m)^\s*\[""").find(rest)
    return if (next == null) rest else rest.substring(0, next.range.first)
}

/** Tags that are deliberately NOT tier routing, one per line as `tag — why`. */
val testTagFixtures = mapOf(
    "[slow]" to "LauncherPathTest fixture: a tag with regex metacharacters in it",
    "brackets" to "LauncherPathTest fixture: the sibling plain tag it is compared against")

guard("G23", "checkNoOrphanTestTags") {
    val manifest = text(at("jk.toml"))
    val fast = tomlSection(manifest, "test")
        ?: error("the root jk.toml has no [test] table, so the tier vocabulary has no baseline.")
    val tiers = mutableListOf(
        Tier("jk test",
            tomlStringList(fast, "include-tags").orEmpty().toSet(),
            tomlStringList(fast, "exclude-tags").orEmpty().toSet()))
    // Every profile except `ci`, which is the fast tier under another name (auto-selected on CI)
    // rather than a tier of its own.
    Regex("""(?m)^\s*\[profiles\.([A-Za-z0-9_-]+)]\s*$""").findAll(manifest)
        .map { it.groupValues[1] }
        .filter { it != "ci" }
        .forEach { name ->
            val body = tomlSection(manifest, "profiles.$name")!!
            tiers.add(Tier("jk test --profile $name",
                tomlStringList(body, "include-tags").orEmpty().toSet(),
                tomlStringList(body, "exclude-tags").orEmpty().toSet()))
        }
    if (tiers.size < 2) {
        error("the root jk.toml declares ${tiers.size} tier(s); the tag table needs the fast tier plus"
            + " one profile per slow tag. This guard is reading nothing.")
    }
    val vocabulary = tiers.flatMap { it.include + it.exclude }.distinct().sorted()
    fun tiersFor(tags: Set<String>) = tiers.filter { it.runs(tags) }.map { it.name }

    val faults = mutableListOf<String>()

    // --- arm 1: TOTALITY. Exhaustive over 2^vocabulary, not sampled. ------------------------
    val partitionFaults = mutableListOf<String>()
    for (mask in 0 until (1 shl vocabulary.size)) {
        val tags = vocabulary.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
        val run = tiersFor(tags)
        if (run.size != 1) {
            val what = if (run.isEmpty()) "no tier runs it" else "run by ${run.size} tiers: $run"
            partitionFaults.add("@Tag" + (tags.ifEmpty { setOf("(untagged)") }) + " — $what")
        }
    }
    if (partitionFaults.isNotEmpty()) {
        faults.add("Every tag combination must be run by exactly one tier. The tier table in jk.toml"
            + " does not partition its own vocabulary:\n" + bullets(partitionFaults)
            + "\n  Adding a tag to [test] exclude-tags takes it out of the fast tier; it needs a"
            + " profile that includes it, or the exclusion is a hole. Two tiers running the same"
            + " combination charges one test to two budgets.")
    }

    // --- arms 2 + 3: what the tree actually declares -----------------------------------------
    val tagLiteral = Regex("""@Tag\("([^"]*)"\)""")
    // Between two @Tag annotations of the SAME declaration there is only whitespace and other
    // annotations. Anything else — a modifier, a type, a brace — starts a new declaration.
    val sameDeclaration = Regex("""\s*(?:@\w+(?:\([^)]*\))?\s*)*""")
    val orphans = mutableListOf<String>()
    val unowned = mutableListOf<String>()
    val blind = mutableListOf<String>()
    var importers = 0
    var elements = 0
    testJava.forEach { f ->
        val raw = text(f)
        val r = rel(f)
        val imports = Regex("""^\s*import\s+org\.junit\.jupiter\.api\.Tag\s*;""", RegexOption.MULTILINE)
            .containsMatchIn(raw)
        if (imports) importers++
        val code = blankNonCode(raw, blankStrings = false)
        val hits = tagLiteral.findAll(code).toList()
        if (hits.isEmpty()) {
            if (imports) blind.add("$r: imports org.junit.jupiter.api.Tag and declares no @Tag(\"...\") literal")
            return@forEach
        }
        var i = 0
        while (i < hits.size) {
            var j = i
            while (j + 1 < hits.size
                && sameDeclaration.matchEntire(code.substring(hits[j].range.last + 1, hits[j + 1].range.first)) != null) {
                j++
            }
            val tags = hits.subList(i, j + 1).map { it.groupValues[1] }.toSet()
            val line = lineAt(code, hits[i].range.first)
            elements++
            val run = tiersFor(tags)
            if (run.size != 1) {
                orphans.add("$r:$line: @Tag${tags.sorted()} — " + if (run.isEmpty()) "NO tier runs it" else "run by $run")
            }
            tags.filterNot { it in vocabulary || it in testTagFixtures }.sorted()
                .forEach { unowned.add("$r:$line: @Tag(\"$it\")") }
            i = j + 1
        }
    }

    if (orphans.isNotEmpty()) {
        faults.add("Every @Tag must be run by exactly one tier. These are not:\n" + bullets(orphans)
            + "\n  A tag excluded from the fast tier and included by no profile is a test that never"
            + " executes and never goes red. Give the tag a profile in the root jk.toml, or stop"
            + " excluding it.")
    }
    if (unowned.isNotEmpty()) {
        faults.add("A @Tag that no tier owns runs in the fast tier by default, which is how a typo"
            + " becomes a slow `jk test`. These tags are in neither the tier vocabulary nor"
            + " testTagFixtures:\n" + bullets(unowned.sorted())
            + "\n  Spell it as one of $vocabulary, give it a profile of its own, or — if it is a"
            + " fixture for jk's own tag filtering rather than a routing decision — name it in"
            + " testTagFixtures with the reason.")
    }
    if (blind.isNotEmpty()) {
        faults.add("This guard reads @Tag(\"...\") literals out of the source. These files import the"
            + " annotation and yield none, so it is reading nothing about them:\n" + bullets(blind)
            + "\n  Either the annotation is written as @Tag(SOME_CONSTANT) — which no scan can route"
            + " and which hides the tier a test runs in from anyone grepping — or the import is dead.")
    }
    if (importers > 0 && elements == 0) {
        faults.add("$importers test files import org.junit.jupiter.api.Tag and the scan resolved no"
            + " tagged element at all. The annotation shape moved; arms 2 and 3 are blind.")
    }
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
}

/** [src] with comments blanked to spaces (newlines and offsets kept), string literals verbatim. */
fun familyGuardCode(src: String): String = blankNonCode(src, blankStrings = false)

/**
 * Plugin sources that legitimately construct their own `ProcessBuilder`, by module-relative path.
 * Every entry is a fork `ToolRun` cannot express, and says which.
 */
val engineMainJava: List<Path> = children(at("server")).flatMap { filesUnder(it.resolve("src/main/java"), ".java") }

guard("G25", "checkPluginFamily") {
    if (engineMainJava.size < 350) {
        error("scanned ${engineMainJava.size} engine Java files; measured against 425. The walk has"
            + " stopped seeing server/*/src/main/java.")
    }
    val quotedPrefix = Regex(""""(##JK[A-Z]+:)"""")
    val variantsOwner = owner("shared/jk-api/src/main/java/cc/jumpkick/model/Variants.java")
    val variantApplyOwner = owner("shared/core/src/main/java/cc/jumpkick/plugin/manifest/VariantApply.java")
    val buildType = Regex("""String\s+BUILD_TYPE\s*=\s*"([^"]+)"""").find(variantsOwner)?.groupValues?.get(1)
        ?: error("Variants no longer declares String BUILD_TYPE, so this guard has lost the owner of"
            + " the injected config keys.")
    val variantPrefix = Regex(""""(variant\.)"\s*\+""").find(familyGuardCode(variantApplyOwner))?.groupValues?.get(1)
        ?: error("VariantApply no longer builds a \"variant.\" + dimension config key, so this guard"
            + " has lost the second injected shape.")

    val faults = mutableListOf<String>()
    pluginModules.forEach { module ->
        val moduleName = module.fileName.toString()
        val moduleFiles = filesUnder(module.resolve("src/main/java"), ".java")
        if (moduleFiles.isEmpty()) {
            faults.add("$moduleName: no Java source under src/main/java, so the family guard verified"
                + " nothing for it. Fix the walk before trusting a green run.")
            return@forEach
        }

        // --- arm 1: one prefix, declared in this module ---
        val declared = LinkedHashMap<String, MutableList<String>>()
        moduleFiles.forEach { f ->
            quotedPrefix.findAll(familyGuardCode(text(f))).forEach { m ->
                declared.getOrPut(m.groupValues[1]) { mutableListOf() }.add(rel(f))
            }
        }
        if (declared.size != 1) {
            faults.add("$moduleName must declare exactly one `##JK*:` wire prefix in its own"
                + " src/main/java — it is one worker speaking one protocol. Found ${declared.size}:"
                + declared.entries.joinToString("") { (p, a) -> "\n    $p at ${a.distinct()}" }
                + "\n    Zero means the worker has no protocol and nothing can talk to it; two means"
                + " two workers sharing a module, and a reader cannot tell whose line is whose.")
            return@forEach
        }
        val prefix = declared.keys.first()
        val engineSites = engineMainJava.filter { f ->
            val raw = text(f)
            raw.contains("##JK") && quotedPrefix.findAll(familyGuardCode(raw)).any { it.groupValues[1] == prefix }
        }.map { rel(it) }

        val descriptor = module.resolve("jk-plugin.toml")
        if (Files.isRegularFile(descriptor)) {
            // --- arm 2: SPI plugin ---
            val code = text(descriptor).lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
            val stated = Regex("""protocol-prefix\s*=\s*"([^"]+)"""").find(code)?.groupValues?.get(1)
            if (stated != prefix) {
                faults.add("$moduleName ships a jk-plugin.toml, so the engine loads it by descriptor and"
                    + " takes its wire prefix from [code].protocol-prefix. The descriptor says"
                    + " ${stated ?: "nothing"} and the code says $prefix, so the engine would tag one"
                    + " end of the conversation and the plugin the other.")
                return@forEach
            }
            if (engineSites.isNotEmpty()) {
                faults.add("$moduleName is an SPI plugin (it ships a jk-plugin.toml), so its prefix"
                    + " belongs to the descriptor and the plugin — the engine discovers it and never"
                    + " spells it. These engine sources name $prefix:\n" + bullets(engineSites)
                    + "\n    That is a second, hardcoded discovery path for a module that already"
                    + " declares itself.")
                return@forEach
            }

            // --- arm 4: the descriptor's schema is total ---
            val schemaKeys = mutableSetOf<String>()
            var inSchema = false
            code.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.startsWith("[")) {
                    inSchema = t == "[schema]"
                    Regex("""^\[sub-schema\.([^\]]+)]$""").find(t)?.let { schemaKeys.add(it.groupValues[1]) }
                } else if (inSchema) {
                    Regex("""^([A-Za-z0-9._-]+)\s*=""").find(t)?.let { schemaKeys.add(it.groupValues[1]) }
                }
            }
            if (schemaKeys.isEmpty()) {
                faults.add("$moduleName's jk-plugin.toml declares no [schema] keys, so the totality arm"
                    + " verified nothing. A plugin with config has a schema.")
                return@forEach
            }
            val reads = LinkedHashMap<String, MutableList<String>>()
            val accessor = Regex("""\.(?:string|stringOpt|bool|stringList|group|intValue)\(\s*"([^"]+)"""")
            moduleFiles.forEach { f ->
                accessor.findAll(familyGuardCode(text(f))).forEach { m ->
                    reads.getOrPut(m.groupValues[1]) { mutableListOf() }.add(rel(f))
                }
            }
            // The descriptor reads its own keys too: `when = { config = "k" }` conditions and
            // `${config.k}` interpolations in contributed coordinates.
            val descRel = rel(descriptor)
            Regex("""config\s*=\s*"([^"]+)"""").findAll(code)
                .forEach { reads.getOrPut(it.groupValues[1]) { mutableListOf() }.add(descRel) }
            Regex("""\$\{config\.([^}]+)}""").findAll(code)
                .forEach { reads.getOrPut(it.groupValues[1]) { mutableListOf() }.add(descRel) }
            val undeclared = reads.filterKeys { key ->
                val top = key.substringBefore('.')
                top != buildType && !key.startsWith(variantPrefix) && top !in schemaKeys
            }
            if (undeclared.isNotEmpty()) {
                faults.add("$moduleName reads config keys its jk-plugin.toml [schema] does not declare."
                    + " An undeclared key has no type, no default and no hint, so a user typo is"
                    + " silently the default and two spellings of one knob can both be live:\n"
                    + bullets(undeclared.entries.map { (k, a) -> "$k read at ${a.distinct()}" })
                    + "\n    Declared: ${schemaKeys.sorted()}"
                    + "\n    Injected by core: $buildType, $variantPrefix<dimension>")
            }
        } else if (engineSites.isEmpty()) {
            // --- arm 3: forked worker ---
            faults.add("$moduleName ships no jk-plugin.toml, so it is a forked worker: the engine"
                + " hardcodes its argv and therefore has to spell $prefix to read its lines. No source"
                + " under server/*/src/main/java does, which means either the worker is unreachable,"
                + " or it has grown a descriptor and is now an SPI plugin — in which case the engine's"
                + " hardcoded fork is the thing to delete.")
        }
    }
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
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
// G33 — the Gradle version catalog and jk-lock.toml agree on every coordinate they share.
//
// The repo builds itself twice. A module once bundled apksig from an 8.7.3 catalog literal while
// the self-host lock resolved 9.3.2 — a full major apart — so the two builds shipped worker jars
// signing with different apksig, and a 9.x-only API would compile under one build and break the
// other. Known drift rides the ratchet below: a NEW mismatch fails, and a listed entry that no
// longer mismatches fails until it is removed.
// ---------------------------------------------------------------------------
val knownCatalogLockDrift = setOf<String>()

guard("G33", "checkCatalogLockParity") {
    val catalogPath = at("gradle/libs.versions.toml")
    if (!Files.isRegularFile(catalogPath)) {
        error("gradle/libs.versions.toml is gone. If the Gradle build has been retired, retire this"
            + " guard in the same change; until then the two builds still have to agree.")
    }
    val catalogText = text(catalogPath)
    val versionKeys = Regex("""(?m)^([A-Za-z0-9-]+)\s*=\s*"([^"]+)"""")
        .findAll(catalogText.substringBefore("[libraries]"))
        .associate { it.groupValues[1] to it.groupValues[2] }
    val catalog = Regex(
        """(?m)^[A-Za-z0-9-]+\s*=\s*\{\s*module\s*=\s*"([^"]+)"\s*,\s*(?:version\.ref\s*=\s*"([A-Za-z0-9-]+)"|version\s*=\s*"([^"]+)")""")
        .findAll(catalogText)
        .mapNotNull { m ->
            val version = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(versionKeys::get)
                ?: m.groupValues[3].takeIf { it.isNotEmpty() }
            version?.let { m.groupValues[1] to it }
        }.toMap()
    val lock = mutableMapOf<String, MutableSet<String>>()
    Regex("""name\s*=\s*"([^"]+)"\s*\n\s*version\s*=\s*"([^"]+)"""").findAll(text(at("jk-lock.toml")))
        .forEach { m ->
            val parts = m.groupValues[1].split(":")
            if (parts.size >= 2) lock.getOrPut("${parts[0]}:${parts[1]}") { mutableSetOf() }.add(m.groupValues[2])
        }
    if (catalog.size < 25 || lock.size < 180) {
        error("parsed ${catalog.size} catalog libraries and ${lock.size} lock artifacts; measured"
            + " against 34 and 202. A regex has stopped seeing its file.")
    }
    val mismatches = catalog.filterKeys { it in lock }.filter { (m, v) -> v !in lock.getValue(m) }
    val unexpected = mismatches.keys - knownCatalogLockDrift
    val healed = knownCatalogLockDrift - mismatches.keys
    if (unexpected.isNotEmpty() || healed.isNotEmpty()) {
        val lines = unexpected.sorted().map {
            ("NEW MISMATCH $it: catalog=${catalog[it]} lock=${lock.getValue(it).sorted()} — the two"
                + " builds compile against different bytes; align the catalog with the lock (the"
                + " lock is the pin), or re-lock")
        } + healed.sorted().map {
            "RECONCILED $it: no longer mismatched — delete it from knownCatalogLockDrift so the ratchet tightens"
        }
        error("gradle/libs.versions.toml and jk-lock.toml disagree:\n" + bullets(lines))
    }
}

// ---------------------------------------------------------------------------
// G34 — a test fixture never reaches production.
//
// `:host` and `:plugin-sdk` are the two modules every jk process and every third-party plugin
// links. A `testFixtures` variant is one keyword (`implementation` where `testImplementation` was
// meant) away from landing inside the native image or a worker jar, where nothing else would
// notice until GraalVM failed to see a reflective JUnit lookup at runtime.
//
// The arm that reads the two modules' GENERATED publication metadata stays with Gradle: jk does
// not generate a POM until `jk publish`, so there is no file here to read. This is the wiring arm
// — a text scan over every build script, which is where the mistake is actually typed.
// ---------------------------------------------------------------------------
val buildScripts: List<Path> = (listOf(at("build.gradle.kts")) +
    moduleDirs.map { it.resolve("build.gradle.kts") } +
    filesUnder(at("buildSrc/src/main/kotlin"), ".gradle.kts"))
    .filter { Files.isRegularFile(it) }

guard("G34", "checkTestFixturesStayOutOfProduction") {
    if (buildScripts.isEmpty()) return@guard // the Gradle build is gone; nothing to reconcile
    if (buildScripts.size < 20) {
        error("scanned ${buildScripts.size} build scripts; the tree has ${moduleDirs.size} modules."
            + " The walk has stopped seeing them.")
    }
    val productionConfigurations =
        Regex("""^\s*(api|implementation|compileOnly|compileOnlyApi|runtimeOnly|annotationProcessor)\s*\(""")
    var fixtureUses = 0
    val wiring = mutableListOf<String>()
    buildScripts.forEach { f ->
        text(f).lines().forEachIndexed { i, raw ->
            val line = raw.substringBefore("//")
            if (!line.contains("testFixtures(")) return@forEachIndexed
            fixtureUses++
            if (productionConfigurations.containsMatchIn(line)) wiring.add("${rel(f)}:${i + 1}: ${line.trim()}")
        }
    }
    if (fixtureUses == 0) {
        error("found no `testFixtures(` dependency in any build script. Either the shared fixtures"
            + " were deleted — in which case delete this guard deliberately — or the scan is broken.")
    }
    if (wiring.isNotEmpty()) {
        error("A testFixtures variant is wired into a production configuration. That puts test code"
            + " on a worker's flattened POM and inside the native image:\n" + bullets(wiring)
            + "\n  Use testImplementation / testCompileOnly / testRuntimeOnly.")
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
// G36 — a module's two manifests declare the same dependencies.
//
// The repo builds itself both ways, so a dependency declared only to Gradle compiles under
// `./gradlew check` and fails under `jk build` — and vice versa. Scope buckets are coarse on
// purpose (main-ish vs test-ish), because that is the distinction that decides whether javac can
// see a type; within a bucket the two builds are free to spell a dependency differently.
// ---------------------------------------------------------------------------

guard("G36", "checkManifestDepParity") {
    val settings = at("settings.gradle.kts")
    if (!Files.isRegularFile(settings)) return@guard // the Gradle build is gone; nothing to reconcile

    // ":wire" -> "shared/wire", straight out of settings.gradle.kts.
    val dirOf = Regex("""project\("(:[\w-]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)""")
        .findAll(text(settings))
        .associate { it.groupValues[1] to it.groupValues[2] }
    // "shared/wire" -> "jk-engine-api", straight out of that module's own manifest. The two names
    // differ often enough (:wire is jk-engine-api, :jk-api is jk-model) that guessing from the
    // project path would make this guard lie.
    val nameOf = dirOf.mapNotNull { (proj, dir) ->
        val m = at("$dir/jk.toml")
        if (!Files.isRegularFile(m)) return@mapNotNull null
        Regex("""^name\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(text(m))?.let { proj to it.groupValues[1] }
    }.toMap()
    if (nameOf.size < 20) {
        error("mapped only ${nameOf.size} project paths to artifact names, so this guard has lost"
            + " settings.gradle.kts or the manifests.")
    }

    val testConfs = setOf("testImplementation", "testApi", "testRuntimeOnly", "testCompileOnly",
        "testFixturesApi", "testFixturesImplementation", "integrationTestImplementation")
    val mainConfs = setOf("implementation", "api", "compileOnly", "runtimeOnly",
        "annotationProcessor", "compileOnlyApi")

    val faults = mutableListOf<String>()
    moduleDirs.forEach { module ->
        val script = module.resolve("build.gradle.kts")
        val manifest = module.resolve("jk.toml")
        // A module built by only one of the two builds has nothing to reconcile here; the
        // both-builds-see-it rule is G44's, below.
        if (!Files.isRegularFile(script) || !Files.isRegularFile(manifest)) return@forEach

        val gradleMain = mutableSetOf<String>()
        val gradleTest = mutableSetOf<String>()
        val fixtures = mutableSetOf<String>()
        var edges = 0
        val scriptText = text(script)
        blankNonCode(scriptText, blankStrings = false).lines().forEach { line ->
            Regex("""(\w+)\(\s*(testFixtures\(\s*)?project\("(:[\w-]+)"\)""").findAll(line).forEach { m ->
                val conf = m.groupValues[1]
                val isFixture = m.groupValues[2].isNotEmpty()
                val name = nameOf[m.groupValues[3]] ?: return@forEach
                edges++
                when (conf) {
                    in testConfs -> { gradleTest.add(name); if (isFixture) fixtures.add(name) }
                    in mainConfs -> gradleMain.add(name)
                }
            }
        }
        if (scriptText.contains("project(\":") && edges == 0) {
            faults.add("found no project dependency in ${rel(script)} although the text contains one."
                + " The scan broke; fix it rather than letting it pass.")
            return@forEach
        }

        // `[test-*]` tables are the test bucket; everything else is main.
        val jkMain = mutableSetOf<String>()
        val jkTest = mutableSetOf<String>()
        val jkFixtures = mutableSetOf<String>()
        var table = ""
        Files.readAllLines(manifest).forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) { table = line.trim('[', ']'); return@forEach }
            if (!table.endsWith("dependencies")) return@forEach
            val dotted = Regex("""^([\w-]+)\.workspace\s*=\s*true""").find(line)
            val inline = Regex("""^([\w-]+)\s*=\s*\{(.*)}""").find(line)
            val name = dotted?.groupValues?.get(1)
                ?: inline?.takeIf { it.groupValues[2].contains("workspace") && it.groupValues[2].contains("true") }
                    ?.groupValues?.get(1)
                ?: return@forEach
            val takesFixtures = inline != null && Regex("""fixtures\s*=\s*true""").containsMatchIn(inline.groupValues[2])
            if (table.startsWith("test-")) {
                jkTest.add(name)
                if (takesFixtures) jkFixtures.add(name)
            } else {
                jkMain.add(name)
            }
        }

        val lines = mutableListOf<String>()
        (gradleMain - jkMain).sorted().forEach { lines.add("Gradle declares $it for the main tier; jk.toml [dependencies] does not") }
        (jkMain - gradleMain).sorted().forEach { lines.add("jk.toml [dependencies] declares $it; build.gradle.kts does not") }
        // A test-tier need is satisfied by a main declaration in either build, so compare the union.
        ((gradleTest - fixtures) - jkTest - jkMain).sorted().forEach { lines.add("Gradle declares $it for the test tier; jk.toml [test-dependencies] does not") }
        (jkTest - gradleTest - gradleMain).sorted().forEach { lines.add("jk.toml [test-dependencies] declares $it; build.gradle.kts does not") }
        (fixtures - jkFixtures).sorted().forEach {
            lines.add("Gradle takes $it's testFixtures; jk.toml needs `$it = { workspace = true, fixtures = true }`"
                + " under a [test-*dependencies] table")
        }
        (jkFixtures - fixtures).sorted().forEach { lines.add("jk.toml takes $it with fixtures = true; build.gradle.kts does not take its testFixtures") }
        if (lines.isNotEmpty()) {
            faults.add("${rel(module)} declares different dependencies to its two builds:\n" + bullets(lines))
        }
    }
    if (faults.isNotEmpty()) {
        error("This repo builds itself with Gradle and with jk, so an edge in only one of them is"
            + " green in one build and broken in the other:\n\n" + faults.joinToString("\n\n")
            + "\n\n  jk's `fixtures = true` is Gradle's `testFixtures(...)`; jk's [test-dependencies]"
            + " is Gradle's testImplementation. Fix whichever manifest is wrong — do not silence this"
            + " by deleting the other declaration.")
    }
}

// ---------------------------------------------------------------------------
// G44 — both builds see the same set of modules.
//
// G36 reconciles the dependencies of a module both builds know about. This is the arm above it:
// a module that only ONE build knows about at all. `plugins/micronaut` was in
// settings.gradle.kts and in no jk.toml, so `jk build` never compiled it and `jk test` never ran
// its suite — a whole module outside the self-host gate, invisible to a guard that only compares
// modules present on both sides.
//
// `clients/intellij` is the one deliberate exception: a standalone Gradle build that must never
// see a jk jar, and is not in settings.gradle.kts either.
// ---------------------------------------------------------------------------
val singleBuildModules = mapOf(
    "clients/intellij" to "a standalone IntelliJ plugin build; it is in neither this Gradle build nor the workspace")

guard("G44", "checkBothBuildsSeeEveryModule") {
    val settings = at("settings.gradle.kts")
    if (!Files.isRegularFile(settings)) return@guard
    val gradleDirs = Regex("""project\("(:[\w-]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)""")
        .findAll(text(settings)).map { it.groupValues[2] }.toSortedSet()
    if (gradleDirs.size < 25) {
        error("settings.gradle.kts yielded ${gradleDirs.size} module directories; measured against 30.")
    }
    val workspaceDirs = tomlStringList(text(at("jk.toml")).substringAfter("[workspace]"), "modules").orEmpty().toSortedSet()
    if (workspaceDirs.size < 25) {
        error("the root jk.toml lists ${workspaceDirs.size} workspace modules; measured against 31.")
    }
    val lines = mutableListOf<String>()
    (gradleDirs - workspaceDirs).filterNot { it in singleBuildModules }.forEach {
        lines.add("$it is in settings.gradle.kts and not in the root jk.toml's [workspace] modules —"
            + " `jk build` never compiles it and `jk test` never runs its suite")
    }
    (workspaceDirs - gradleDirs).filterNot { it in singleBuildModules }.forEach {
        // A jk-only module is legal when it carries no Gradle script at all — the gate itself is
        // one. It is a defect only when Gradle is expected to build it.
        if (Files.isRegularFile(at("$it/build.gradle.kts"))) {
            lines.add("$it has a build.gradle.kts and is not in settings.gradle.kts — Gradle never builds it")
        }
    }
    singleBuildModules.forEach { (dir, why) ->
        if (dir in gradleDirs || dir in workspaceDirs) {
            lines.add("$dir is declared a single-build exception ($why) but one of the two builds now"
                + " includes it. Delete the exception, or the build entry.")
        }
    }
    if (lines.isNotEmpty()) {
        error("A module only one build knows about is a module outside half the gate:\n" + bullets(lines))
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

guard("G39", "checkCheapestRejectionFirst") {
    val nameOnly = Regex("""getFileName|endsWith\(|startsWith\(|\.equals\(""")
    val offenders = mutableListOf<String>()
    mainJava.forEach { f ->
        val lines = text(f).lines()
        lines.forEachIndexed { i, raw ->
            if (!raw.trimEnd().endsWith(".filter(Files::isRegularFile)")) return@forEachIndexed
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("//"))) j++
            if (j >= lines.size) return@forEachIndexed
            val next = lines[j].trim()
            if (!next.startsWith(".filter(")) return@forEachIndexed
            // A predicate that touches the filesystem is legitimately ordered after the stat.
            if (next.contains("Files.")) return@forEachIndexed
            if (!nameOnly.containsMatchIn(next)) return@forEachIndexed
            offenders.add("${rel(f)}:${i + 1}  $next")
        }
    }
    if (offenders.isNotEmpty()) {
        error("A stat runs before a free name test:\n" + bullets(offenders)
            + "\n  Put the string predicate first. The walk already paid for the entry;"
            + " Files::isRegularFile re-resolves the path for a fresh stat, so ordering it first"
            + " spends a syscall on every entry the name test was going to reject.")
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

guard("G52", "checkTestTierDocs") {
    val model = text(at("buildSrc/src/main/kotlin/TestTiers.kt"))
    val constants = Regex("""const val (\w+) = "([^"]+)"""")
        .findAll(model)
        .associate { it.groupValues[1] to it.groupValues[2] }
    val tierPattern = Regex(
        """TestTier\(\s*(\w+),\s*include\s*=\s*(emptySet\(\)|setOf\([^)]*\)),\s*exclude\s*=\s*(emptySet\(\)|setOf\([^)]*\)|slowTags\.toSet\(\))\s*,?\s*\)""")
    val slowTags = Regex("""val slowTags = listOf\(([^)]*)\)""")
        .find(model)?.groupValues?.get(1)
        ?.let { Regex(""""([^"]+)"""").findAll(it).map { match -> match.groupValues[1] }.toSet() }
        ?: error("G52 cannot read TestTiers.slowTags")
    fun values(expression: String): Set<String> =
        if (expression == "slowTags.toSet()") slowTags
        else Regex(""""([^"]+)"""").findAll(expression).map { it.groupValues[1] }.toCollection(linkedSetOf())
    val tiers = tierPattern.findAll(model).map { match ->
        Triple(
            constants[match.groupValues[1]]
                ?: error("G52 cannot resolve tier constant ${match.groupValues[1]}"),
            values(match.groupValues[2]),
            values(match.groupValues[3]))
    }.toList()
    if (tiers.size != 5) error("G52 read ${tiers.size} tiers from TestTiers.all; expected 5")
    val gatingNames = Regex("""val gating = setOf\(([^)]*)\)""")
        .find(model)?.groupValues?.get(1)
        ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        ?: error("G52 cannot read TestTiers.gating")
    val gating = gatingNames.map { constants[it] ?: error("G52 cannot resolve gating constant $it") }.toSet()
    fun tags(items: Set<String>, fallback: String = "—") =
        if (items.isEmpty()) fallback else items.joinToString(", ") { "`$it`" }
    val expected = buildString {
        appendLine("<!-- test-tiers:start -->")
        appendLine("| Command | Includes | Excludes | In `checkAll`? |")
        appendLine("|---------|----------|----------|----------------|")
        tiers.forEach { (task, include, exclude) ->
            appendLine(
                "| `./gradlew $task` | "
                    + tags(include, "untagged")
                    + " | "
                    + tags(exclude)
                    + " | "
                    + if (task in gating) "yes |" else "no |")
        }
        append("<!-- test-tiers:end -->")
    }
    val docs = text(at("docs/contributors/test-suite-tiers.md"))
    val actual = Regex("""(?s)<!-- test-tiers:start -->.*?<!-- test-tiers:end -->""")
        .find(docs)?.value
        ?: error("test-suite-tiers.md is missing its generated tier table markers")
    if (actual != expected) {
        error("test-suite-tiers.md differs from TestTiers; replace its marked table with:\n$expected")
    }
}

guard("G55", "checkEngineConfigDocs") {
    val model = text(at("shared/core/src/main/java/cc/jumpkick/config/EngineControls.java"))
    val row = Regex("""control\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*(?:"([^"]*)"|([A-Z_]+))\s*,\s*"([^"]*)"\s*\)""")
    // The fourth argument is a literal or one of the class's own String constants (ENGINE_START).
    val constants = Regex("""static final String ([A-Z_]+) = "([^"]*)";""")
        .findAll(model).associate { it.groupValues[1] to it.groupValues[2] }
    val rows = row.findAll(model).map {
        val read = it.groupValues[4].ifEmpty {
            constants[it.groupValues[5]] ?: error("EngineControls: unknown constant ${it.groupValues[5]}")
        }
        listOf(it.groupValues[1], it.groupValues[2], it.groupValues[3], read, it.groupValues[6])
    }.toList()
    val table = rows.filter { it[0].isNotEmpty() }
    val process = rows.filter { it[0].isEmpty() }
    if (table.size < 5) error("EngineControls TABLE parsed ${table.size} rows; expected at least 5")
    if (process.size < 6) error("EngineControls PROCESS parsed ${process.size} rows; expected at least 6")
    fun block(id: String, header: String, rule: String, body: List<List<String>>, line: (List<String>) -> String): String {
        val sb = StringBuilder()
        sb.appendLine("<!-- $id:start -->")
        sb.appendLine(header)
        sb.appendLine(rule)
        body.forEach { sb.appendLine(line(it)) }
        sb.append("<!-- $id:end -->")
        return sb.toString()
    }
    val expectedTable = block(
        "engine-config",
        "| Key | Env | Default | Read | Meaning |",
        "|---|---|---|---|---|",
        table,
    ) { "| `${it[0]}` | `${it[1]}` | ${it[2]} | ${it[3]} | ${it[4]} |" }
    val expectedProcess = block(
        "engine-process",
        "| Env | Default | Meaning |",
        "|---|---|---|",
        process,
    ) { "| `${it[1]}` | ${it[2]} | ${it[4]} |" }
    val docs = text(at("docs/user/engine.md"))
    fun present(id: String): String =
        Regex("""(?s)<!-- $id:start -->.*?<!-- $id:end -->""").find(docs)?.value
            ?: error("docs/user/engine.md is missing its $id table markers")
    val drift = mutableListOf<String>()
    if (present("engine-config") != expectedTable) {
        drift.add("replace the engine-config table with:\n$expectedTable")
    }
    if (present("engine-process") != expectedProcess) {
        drift.add("replace the engine-process table with:\n$expectedProcess")
    }
    if (drift.isNotEmpty()) {
        error("docs/user/engine.md differs from EngineControls:\n" + drift.joinToString("\n"))
    }
}

guard("G56", "checkBootstrapVersions") {
    val problems = mutableListOf<String>()
    val fromProps = Regex("""gradle-(\d+\.\d+(?:\.\d+)?)""")
        .find(text(at("gradle/wrapper/gradle-wrapper.properties")))?.groupValues?.get(1)
    val fromTask = Regex("""gradleVersion\s*=\s*"([^"]+)"""")
        .find(text(at("build.gradle.kts")))?.groupValues?.get(1)
    if (fromProps.isNullOrBlank()) {
        problems.add("gradle-wrapper.properties has no gradle-N.N.N distribution")
    }
    if (fromTask.isNullOrBlank()) {
        problems.add("tasks.wrapper in build.gradle.kts does not set gradleVersion")
    }
    if (!fromProps.isNullOrBlank() && !fromTask.isNullOrBlank() && fromProps != fromTask) {
        problems.add("wrapper task is $fromTask but gradle-wrapper.properties is $fromProps")
    }
    val nvmrcFile = at(".nvmrc")
    val pin = if (Files.isRegularFile(nvmrcFile)) text(nvmrcFile).trim() else ""
    if (!Regex("""^\d+(\.\d+)*$""").matches(pin)) {
        problems.add(".nvmrc must be a Node version token (got ${pin.ifBlank { "missing" }})")
    }
    val workflows = treeFiles.filter {
        val here = rel(it)
        here.startsWith(".github/workflows/") && here.endsWith(".yml")
    }
    val setupNode = workflows.filter { text(it).contains("actions/setup-node") }
    if (setupNode.isEmpty()) {
        problems.add("no workflow uses actions/setup-node — the dashboard JS gate would skip Node")
    }
    setupNode.forEach { wf ->
        val body = text(wf)
        if (!Regex("""node-version-file:\s*['\"]\.nvmrc['\"]""").containsMatchIn(body)) {
            problems.add("${wf.fileName}: setup-node must set node-version-file: '.nvmrc'")
        }
        if (Regex("""(?m)^\s+node-version:\s""").containsMatchIn(body)) {
            problems.add("${wf.fileName}: setup-node must not also set node-version (the pin is .nvmrc)")
        }
    }
    if (problems.isNotEmpty()) {
        error("Bootstrap versions disagree:\n" + bullets(problems))
    }
}

guard("G57", "checkCiCadence") {
    val problems = mutableListOf<String>()
    val nightly = text(at(".github/workflows/ci-nightly.yml"))
    val branch = text(at(".github/workflows/ci.yml"))
    if (!Files.isRegularFile(at("scripts/ci-product-smoke.sh"))) {
        problems.add("scripts/ci-product-smoke.sh is missing")
    }
    if (!nightly.contains("./gradlew benchTest")) {
        problems.add("ci-nightly.yml must run ./gradlew benchTest")
    }
    if (!nightly.contains("coverageReport") || !nightly.contains("-Pjk.coverage")) {
        problems.add("ci-nightly.yml must run coverageReport -Pjk.coverage")
    }
    if (!nightly.contains("macos-")) {
        problems.add("ci-nightly.yml must have a macOS smoke runner")
    }
    if (!nightly.contains("windows-")) {
        problems.add("ci-nightly.yml must have a Windows smoke runner")
    }
    if (!nightly.contains("ci-product-smoke.sh")) {
        problems.add("ci-nightly.yml must run scripts/ci-product-smoke.sh")
    }
    if (branch.contains("coverageReport")
            || branch.contains("-Pjk.coverage")
            || branch.contains("benchTest")) {
        problems.add("ci.yml must not run coverage or benches (they are nightly, non-gating)")
    }
    if (!text(at("build.gradle.kts")).contains("\"coverageReport\"")) {
        problems.add("build.gradle.kts must register coverageReport")
    }
    // Gradle is the bootstrap oracle, so the only evidence that jk can still build jk is a job
    // that does it. Deleting the job leaves the self-host claim in the docs with nothing behind
    // it, and nothing else goes red.
    if (!branch.contains("self-host:")) {
        problems.add("ci.yml must keep the self-host job — a pull request has to prove jk still"
            + " builds and tests this checkout")
    }
    if (!branch.contains("JK_HOME:")) {
        problems.add("the self-host job must run against an isolated JK_HOME, or it can pass on"
            + " state the pull request did not produce")
    }
    listOf("jk build", "jk test").forEach { verb ->
        if (!branch.contains(verb)) {
            problems.add("ci.yml's self-host job must run `$verb`")
        }
    }
    // The lane's authority, pinned. A `continue-on-error: true` on this job would leave every
    // other assertion here green while turning the only evidence that jk builds jk into a
    // courtesy run — the exact demotion this ticket's decision rejects, and one nothing else
    // would catch. `wall-measure.yml` keeps its own flag: a wall time is a record, not a verdict.
    if (branch.contains("continue-on-error")) {
        problems.add("ci.yml must not carry continue-on-error — the self-host job is a merge"
            + " requirement, and a flag that makes it advisory retires the oracle without deleting"
            + " the job (docs/contributors/self-host.md, \"Which build is the oracle\")")
    }
    if (!Files.isRegularFile(at("scripts/dogfood-wall-measure.sh"))) {
        problems.add("scripts/dogfood-wall-measure.sh is missing")
    }
    val wall = at(".github/workflows/wall-measure.yml")
    if (!Files.isRegularFile(wall)) {
        problems.add(".github/workflows/wall-measure.yml is missing — the Gradle/jk wall"
            + " comparison is scheduled, not something a contributor has to remember")
    } else {
        val wallText = text(wall)
        listOf("schedule:", "dogfood-wall-measure.sh", "upload-artifact", "row.jsonl")
            .filterNot(wallText::contains)
            .forEach { missing ->
                problems.add("wall-measure.yml must keep '$missing': a measurement nobody"
                    + " schedules, or whose machine-readable result nobody keeps, is not a"
                    + " baseline")
            }
    }
    if (problems.isNotEmpty()) {
        error("CI cadence is incomplete:\n" + bullets(problems))
    }
}

guard("G58", "checkSecurityDocs") {
    val problems = mutableListOf<String>()
    val advisory = "security/advisories"
    val policy = at("SECURITY.md")
    val page = at("docs/user/security.md")
    if (!Files.isRegularFile(policy)) {
        problems.add("SECURITY.md is missing")
    } else {
        val body = text(policy)
        if (!body.contains("docs/user/security.md")) {
            problems.add("SECURITY.md must point at docs/user/security.md")
        }
        if (!body.contains(advisory)) {
            problems.add("SECURITY.md must name the GitHub security/advisories URL")
        }
    }
    if (!Files.isRegularFile(page)) {
        problems.add("docs/user/security.md is missing")
    } else if (!text(page).contains(advisory)) {
        problems.add("docs/user/security.md must name the GitHub security/advisories URL")
    }
    if (!text(at("docs/user/README.md")).contains("](security.md)")) {
        problems.add("docs/user/README.md must link security.md")
    }
    if (problems.isNotEmpty()) {
        error("Security docs are incomplete:\n" + bullets(problems))
    }
}

// ---------------------------------------------------------------------------
// Guard G62: the ship layout is one shape, and three files have to agree on it.
//
// `install.sh <binary>` reads the engine from `<dir-of-binary>/lib/`. Two builds write that
// directory — Gradle's `dist` task and `.jk/after-build-dist.kts` — and neither can see the other.
// A rename in one is silent in the other: the installer keeps reading `lib/`, one build keeps
// filling it, and the other produces a directory the installer walks straight past. That failure
// mode is a local install that silently pairs a freshly built client with the RELEASED engine —
// the binary you just built running an engine you did not, with nothing on screen saying so.
//
// So the name is compared, not just present: all three must spell the same directory. Self-fails
// when any of the three anchors stops matching, because a guard that quietly finds nothing to
// compare is worse than no guard.
// ---------------------------------------------------------------------------

guard("G62", "checkShipLayoutAgrees") {
    val readers = listOf(
        Triple("install.sh", Regex("""SRC_LIB=.*pwd\)/([A-Za-z0-9_-]+)""""), "the installer's engine dir"),
        Triple("build.gradle.kts", Regex("""shadowJar"\)\) \{ into\("([A-Za-z0-9_-]+)"\)"""), "Gradle's dist task"),
        Triple(".jk/after-build-dist.kts", Regex("""dist\.resolve\("([A-Za-z0-9_-]+)"\)"""), "jk's dist script"))

    val found = LinkedHashMap<String, String>()
    val lost = mutableListOf<String>()
    readers.forEach { (rel, pattern, label) ->
        val file = at(rel)
        val hit = if (Files.isRegularFile(file)) pattern.find(text(file)) else null
        if (hit == null) lost.add("  $rel — $label") else found[rel] = hit.groupValues[1]
    }
    if (lost.isNotEmpty()) {
        error("G62 can no longer read the ship layout out of these, so it is comparing nothing:\n"
            + lost.joinToString("\n")
            + "\n  Re-anchor the scan on how the file spells it now, or retire the guard deliberately.")
    }
    val names = found.values.toSet()
    if (names.size != 1) {
        error("the ship layout is one directory and these disagree about its name:\n"
            + found.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
            + "\n  install.sh reads <dir-of-binary>/<name>/jk-engine-<version>.jar, so a build that"
            + " writes a different name produces a dist the installer ignores — and a local install"
            + " that falls back to the released engine without saying so.")
    }
}

// ---------------------------------------------------------------------------
// Guard G63: the curated integration lane's registry is real, covered, and still wired to CI.
//
// `curated-integration.txt` is the single owner both builds read; this side re-derives the rules
// from the file rather than from Gradle's parser, so a green verdict here is independent evidence.
//
// Every way the lane rots is silent: a renamed class stops being selected, an entry loses its
// `@Tag("integration")` and leaves the tier the lane filters, a surface ends up with only happy
// paths, or the CI job is deleted while the registry stays behind reading like coverage.
//
// The `failure` column is checked for visibility, not truth: a class claiming a refused path must
// assert a throw or a non-zero exit, or name a refusal in a test method. That falsifies a wrong
// claim without pretending to verify a right one.
// ---------------------------------------------------------------------------

guard("G63", "checkCuratedIntegration") {
    val registry = "curated-integration.txt"
    val surfaces = mapOf(
        "wire" to "CLI to engine JSONL wire",
        "spawn" to "engine spawn, election and takeover",
        "workers" to "worker and plugin process launch",
        "workspace" to "workspace build and test",
        "install" to "install and materialize",
        "lock" to "lockfile and action cache")
    val outcomes = setOf("success", "failure")
    val modules = mapOf(":cli" to "clients/cli", ":engine" to "server/engine")
    val failureAssertions = listOf(
        "assertThatThrownBy", "assertThrows", "catchThrowable", "assertThatExceptionOfType",
        "isNotEqualTo(0)", "isNotZero")
    val refusalWords = listOf(
        "fail", "refus", "reject", "denie", "invalid", "missing", "unknown", "error", "stale",
        "mismatch", "conflict", "nonzero", "orphan", "ghost", "corrupt", "crash", "timeout",
        "noop", "no_op", "not_", "never", "without", "bad_", "loses")
    val disqualifying = listOf("slow", "network", "bench")

    val faults = mutableListOf<String>()
    data class Entry(val module: String, val fqcn: String, val surface: String,
                     val paths: Set<String>, val line: Int)
    val entries = mutableListOf<Entry>()
    text(at(registry)).lines().forEachIndexed { index, raw ->
        val body = raw.trim()
        if (body.isEmpty() || body.startsWith("#")) return@forEachIndexed
        val n = index + 1
        val fields = body.split("|").map { it.trim() }
        if (fields.size != 5) {
            faults.add("$registry:$n: ${fields.size} fields, expected 5 (module | class | surface | outcomes | why)")
            return@forEachIndexed
        }
        val paths = fields[3].split(",").map { it.trim() }.toSet()
        when {
            fields[0] !in modules -> faults.add("$registry:$n: unknown module '${fields[0]}'; one of ${modules.keys}")
            !fields[1].contains('.') -> faults.add("$registry:$n: '${fields[1]}' is not a fully-qualified class name")
            fields[2] !in surfaces -> faults.add("$registry:$n: unknown surface '${fields[2]}'; one of ${surfaces.keys}")
            !outcomes.containsAll(paths) -> faults.add("$registry:$n: unknown outcome(s) ${paths - outcomes}; one or both of $outcomes")
            fields[4].length < 20 -> faults.add("$registry:$n: the reason is ${fields[4].length} characters; say what merges broken without this class")
            else -> entries.add(Entry(fields[0], fields[1], fields[2], paths, n))
        }
    }
    if (entries.isEmpty()) {
        error("$registry names no classes, so the curated lane would run nothing and report green."
            + " Fix the file or this parse.")
    }

    // Floor under the scan: the lane is carved out of the integration tier, so a walk that stops
    // finding the tier is a green verdict about nothing. Measured at 109 classes. Read from
    // `codeOf`, not the raw file: a class whose javadoc quotes `@Tag("integration")` to explain why
    // it is NOT tagged reads as tagged otherwise, and one did.
    val tagged = testJava.filter { codeOf(it).contains("@Tag(\"integration\")") }
    if (tagged.size < 90) {
        error("found ${tagged.size} @Tag(\"integration\") classes; measured against 109 and floored"
            + " at 90. Either the tier shrank into the curated subset — the one thing this lane must"
            + " not cause — or the scan broke and is passing vacuously.")
    }

    entries.groupBy { it.fqcn }.filterValues { it.size > 1 }.forEach { (fqcn, dupes) ->
        faults.add("$fqcn is listed ${dupes.size} times (lines ${dupes.map { it.line }})")
    }

    val methodName = Regex("""\bvoid\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    entries.forEach { entry ->
        val relPath = modules.getValue(entry.module) + "/src/test/java/" + entry.fqcn.replace('.', '/') + ".java"
        val source = at(relPath)
        if (!Files.isRegularFile(source)) {
            faults.add("${entry.fqcn} (line ${entry.line}): no source at $relPath — the class was"
                + " renamed, moved, or deleted; update the registry")
            return@forEach
        }
        val body = codeOf(source)
        if (!body.contains("@Tag(\"integration\")")) {
            faults.add("${entry.fqcn} (line ${entry.line}): not @Tag(\"integration\"), so the lane's"
                + " filters never select it")
        }
        disqualifying.filter { body.contains("@Tag(\"$it\")") }.forEach {
            faults.add("${entry.fqcn} (line ${entry.line}): carries @Tag(\"$it\"), which is a nightly"
                + " tier — the branch gate must not need the network, a framework toolchain, or a"
                + " benchmark")
        }
        if ("failure" in entry.paths) {
            val asserts = failureAssertions.any { body.contains(it) }
            val names = methodName.findAll(body).map { it.groupValues[1].lowercase() }.toList()
            val refuses = names.any { name -> refusalWords.any { name.contains(it) } }
            if (!asserts && !refuses) {
                faults.add("${entry.fqcn} (line ${entry.line}): claims a failure path and shows none"
                    + " — no $failureAssertions and no test method naming a refusal. Drop the claim,"
                    + " or register a class that has one")
            }
        }
    }

    // The lane runs a subset of the tier's classes, so it has to inherit the tier's per-module
    // setup — worker jars, engine jar, sandbox roots, transport. A module script that configures
    // `integrationTest` by name gives the lane none of it, and the lane then fails for reasons the
    // change under review did not cause.
    entries.map { it.module }.distinct().sorted().forEach { module ->
        val script = at(modules.getValue(module) + "/build.gradle.kts")
        val body = if (Files.isRegularFile(script)) text(script) else ""
        if (body.contains("integrationTest") && !body.contains("CuratedIntegration.integrationTasks")) {
            faults.add("$module configures integrationTest by name; the registry names classes in"
                + " it, so it must configure CuratedIntegration.integrationTasks instead and give"
                + " the lane the tier's environment")
        }
    }

    val bySurface = entries.groupBy { it.surface }
    surfaces.forEach { (id, what) ->
        val here = bySurface[id].orEmpty()
        val gaps = outcomes.filterNot { path -> here.any { path in it.paths } }
        if (here.isEmpty()) {
            faults.add("surface '$id' ($what) has no entry")
        } else if (gaps.isNotEmpty()) {
            faults.add("surface '$id' ($what) has no " + gaps.sorted().joinToString(" or ") + " path")
        }
    }

    if (!text(at(".github/workflows/ci.yml")).contains("curatedIntegrationTest")) {
        faults.add(".github/workflows/ci.yml must run ./gradlew curatedIntegrationTest — a registry"
            + " no pull request executes is documentation, not a gate")
    }
    if (!text(at(".github/workflows/ci-nightly.yml")).contains("./gradlew integrationTest")) {
        faults.add(".github/workflows/ci-nightly.yml must still run the full ./gradlew"
            + " integrationTest; the curated lane is a subset, never a replacement")
    }
    val doc = text(at("docs/contributors/test-suite-tiers.md"))
    if (!doc.contains(registry)) {
        faults.add("docs/contributors/test-suite-tiers.md must name $registry")
    }
    if (!doc.contains("8 minutes")) {
        faults.add("docs/contributors/test-suite-tiers.md must state the lane's budget as '8"
            + " minutes', the number buildSrc/src/main/kotlin/CuratedIntegration.kt owns")
    }

    if (faults.isNotEmpty()) {
        error("the curated integration lane does not cover what it claims:\n" + bullets(faults)
            + "\n  ${entries.size} entries over ${tagged.size} integration classes. The lane is the"
            + " only integration coverage a pull request gets; an entry that no longer runs is a"
            + " boundary nobody is watching until the nightly build.")
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
    val jk = (lettersIn(text(at(".jk/after-build.kts"))) + mapped.filterValues { it in tables }.keys).toSortedSet()
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
// Guard G53: every production package in an enforced source root is @NullMarked.
guard("G53", "checkNullMarkedApiPackages") {
    // Mirrors buildSrc's NullMarking.enforcedRoots; this gate cannot read buildSrc.
    val roots = listOf(
        "shared/jk-api/src/main/java/",
        "shared/wire/src/main/java/",
        "shared/plugin-sdk/src/main/java/",
        "shared/core/src/main/java/",
        "clients/cli/src/main/java/",
        "shared/guard-api/src/main/java/",
        "server/guard/src/main/java/")
    // Mirrors NullMarking.excludedPackages: a package left unmarked on purpose, with its reason.
    val excluded = emptyMap<String, String>()
    val packagePattern = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;""")
    val sources = treeFiles.filter { file ->
        val path = rel(file)
        roots.any(path::startsWith) && path.endsWith(".java")
    }
    val packages = sources.asSequence()
        .filterNot { it.fileName.toString() == "package-info.java" }
        .mapNotNull { packagePattern.find(text(it))?.groupValues?.get(1) }
        .toSortedSet()
    if (packages.size != 48) {
        error("Found ${packages.size} enforced production packages; measured against 48. The source"
            + " roots or package parser drifted, so this guard cannot report green.")
    }
    val markers = sources.filter { it.fileName.toString() == "package-info.java" }.associateBy { file ->
        packagePattern.find(text(file))?.groupValues?.get(1)
    }
    val marked = packages.filter { pkg ->
        val marker = markers[pkg]
        marker != null && text(marker).contains("@NullMarked")
    }.toSet()
    val faults = mutableListOf<String>()
    (packages - marked - excluded.keys).forEach { faults.add("$it lacks package-level @NullMarked") }
    (excluded.keys - packages).forEach { faults.add("$it is excluded but is not a production package") }
    excluded.keys.filter { it in marked }.forEach { faults.add("$it is marked now - drop its exclusion") }
    if (faults.isNotEmpty()) {
        error("Enforced production packages must declare package-level @NullMarked, or hold an"
            + " exclusion entry stating why:\n" + bullets(faults.sorted()))
    }
}

// ---------------------------------------------------------------------------
// Guard G54: first-party plugin dependencies stay behind the SDK boundary.
// ---------------------------------------------------------------------------

guard("G54", "checkPluginSdkBoundary") {
    val allowed = mapOf(
        "auditor|implementation|:core" to "lockfile and audit report model",
        "publisher|implementation|:core" to "manifest, lockfile, and build-layout model",
        "publisher|implementation|:client-io" to "repository upload transports",
        "publisher|testImplementation|:core" to "session-boundary test fixtures",
        "image-builder|implementation|:jk-api" to "image and repository credential model",
        "image-builder|testImplementation|:host" to "repository-root test fixture",
        "minified|implementation|:dynamic-surface" to "shared reachability and keep-rule model",
        "grails|implementation|:spring-boot" to "Boot jar packaging composition")
    val edgePattern = Regex(
        """(?m)^\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|bundledCodec)\s*\(\s*(?:testFixtures\s*\(\s*)?project\s*\(\s*"(:[^"]+)"""")
    val builds = treeFiles.filter { rel(it).matches(Regex("""plugins/[^/]+/build\.gradle\.kts""")) }
    if (builds.size != 15) {
        error("Found ${builds.size} plugin builds; measured against 15, so the boundary scan drifted.")
    }
    val seenExceptions = mutableSetOf<String>()
    val violations = mutableListOf<String>()
    builds.sorted().forEach { file ->
        val plugin = file.parent.fileName.toString()
        edgePattern.findAll(text(file)).forEach { match ->
            val configuration = match.groupValues[1]
            val target = match.groupValues[2]
            val key = "$plugin|$configuration|$target"
            val baseline = target == ":plugin-sdk"
                || configuration == "bundledCodec" && target == ":host"
            when {
                baseline -> Unit
                key in allowed -> seenExceptions.add(key)
                else -> violations.add("$plugin $configuration -> $target")
            }
        }
    }
    violations.addAll((allowed.keys - seenExceptions).sorted().map { "stale allowlist entry: $it" })
    if (violations.isNotEmpty()) {
        error("First-party plugin dependencies must stay behind plugin-sdk or a documented"
            + " current exception:\n" + bullets(violations.sorted()))
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
