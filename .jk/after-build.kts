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
    Regex(""""((?:\\.|[^"\\])*)"""").findAll(guardTextOf(p)).map { it.groupValues[1] }.toSet()
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

fun javaIn(sourceSet: String): List<Path> =
    moduleDirs.flatMap { filesUnder(it.resolve("src/$sourceSet/java"), ".java") }

val mainJava: List<Path> = javaIn("main")
val testJava: List<Path> = javaIn("test")
val fixtureJava: List<Path> = javaIn("testFixtures")

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

/** Every string literal in a Java source, with comments and char literals out of the way. */
fun javaStringLiterals(src: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("//", i) -> { val nl = src.indexOf('\n', i); i = if (nl < 0) src.length else nl + 1 }
            src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2); i = if (e < 0) src.length else e + 2 }
            src.startsWith("\"\"\"", i) -> {
                val e = src.indexOf("\"\"\"", i + 3)
                out.add(src.substring(i + 3, if (e < 0) src.length else e))
                i = if (e < 0) src.length else e + 3
            }
            src[i] == '"' || src[i] == '\'' -> {
                val quote = src[i]
                val body = StringBuilder()
                var j = i + 1
                while (j < src.length && src[j] != quote) {
                    if (src[j] == '\\') { body.append(src[j]); j++ }
                    if (j < src.length) { body.append(src[j]); j++ }
                }
                if (quote == '"') out.add(body.toString())
                i = j + 1
            }
            else -> i++
        }
    }
    return out
}

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

/** `value -> constant` for every `public static final <type> NAME = "value";` in [src]. */
fun namedConstants(src: String, type: String = "String"): Map<String, String> =
    Regex("""public static final $type (\w+) = "([^"]*)";""")
        .findAll(src)
        .associate { it.groupValues[2] to it.groupValues[1] }

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
// G3 — one XML parser, one hardening posture.
//
// A new `DocumentBuilderFactory` that forgets an XXE flag and then parses third-party XML — an
// AAR's res/values from any Maven artifact, a git dependency's pom.xml inside the resident
// engine. Arm 3 checks the six flags by name INSIDE the owner: across files a scan cannot tell
// which factory instance a `setFeature` call configures, which is why arm 2 exists.
// ---------------------------------------------------------------------------
val xmlParserOwner = "shared/host/src/main/java/cc/jumpkick/host/DomXml.java"

val xxeHardening = listOf(
    "setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)",
    "setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\",true)",
    "setFeature(\"http://xml.org/sax/features/external-general-entities\",false)",
    "setFeature(\"http://xml.org/sax/features/external-parameter-entities\",false)",
    "setFeature(\"http://apache.org/xml/features/nonvalidating/load-external-dtd\",false)",
    "setExpandEntityReferences(false)")

guard("G3", "checkSingleXmlParserOwner") {
    val ownerCode = guardText(owner(xmlParserOwner))
    val bannedFactories = listOf("SAXParserFactory", "XMLInputFactory", "XMLReaderFactory")
    val banned = mutableListOf<String>()
    val parsers = mutableListOf<String>()
    (mainJava + testJava).forEach { f ->
        val code = guardTextOf(f)
        val r = rel(f)
        bannedFactories.filter { code.contains(it) }.forEach { banned.add("$r: $it") }
        if (r != xmlParserOwner && code.contains("DocumentBuilderFactory")) parsers.add(r)
    }
    val faults = mutableListOf<String>()
    if (banned.isNotEmpty()) {
        faults.add("jk parses XML in one place, with one hardening posture. These name a JAXP parser"
            + " that has no owner and no XXE flags at all:\n" + bullets(banned.sorted()))
    }
    if (parsers.isNotEmpty()) {
        faults.add("A second DocumentBuilderFactory is a second XXE posture to get wrong:\n"
            + bullets(parsers.sorted())
            + "\n  Parse through cc.jumpkick.host.DomXml — parse(byte[] | String | Path |"
            + " InputStream) to read, newDocument() to build one. It hands out documents, never a"
            + " factory or a builder, so there is no unhardened parser to obtain.")
    }
    val missing = xxeHardening.filterNot { ownerCode.contains(it) }
    if (missing.isNotEmpty()) {
        faults.add("DomXml is the only parser jk builds, so its flags are the only XXE posture jk"
            + " has. These are gone:\n" + bullets(missing)
            + "\n  Restore them in DomXml.hardened, or change this list in the same commit and say"
            + " in the message what jk now accepts from a hostile document.")
    }
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
}

// ---------------------------------------------------------------------------
// G5 — a fork protocol's line prefix has exactly two ends.
//
// Every `##JK<X>:` marker is one end of a parent/child protocol: the plugin declares it and the
// engine reads it. A prefix named once is a dead protocol; named three times it is a lockstep
// waiting to break. Neither end fails loudly at runtime — the child's protocol lines simply
// look like ordinary stdout.
// ---------------------------------------------------------------------------

guard("G5", "checkWireProtocolPrefixPairs") {
    val quotedPrefix = Regex(""""(##JK[A-Z]+:)"""")
    val sites = LinkedHashMap<String, MutableList<String>>()
    val sources = mainJava + moduleDirs.map { it.resolve("jk-plugin.toml") }.filter { Files.isRegularFile(it) }
    sources.forEach { f ->
        val raw = text(f)
        if (!raw.contains("##JK")) return@forEach
        val code = if (f.fileName.toString().endsWith(".toml")) {
            raw.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        } else {
            guardText(raw)
        }
        quotedPrefix.findAll(code).forEach { m ->
            sites.getOrPut(m.groupValues[1]) { mutableListOf() }.add(rel(f))
        }
    }
    val wrong = sites.toSortedMap().filter { (_, a) -> a.size != 2 || a.distinct().size != 2 }
    if (wrong.isNotEmpty()) {
        error("A ##JK*: protocol prefix names exactly two ends — one writes it, one reads it. These"
            + " do not:\n"
            + wrong.entries.joinToString("\n") { (p, a) ->
                "  $p: ${a.size} site(s)\n" + a.joinToString("\n") { "      $it" }
            }
            + "\n  One site means a dead protocol; three means a copy that will drift. Reference the"
            + " declaring constant instead of re-typing the literal.")
    }
}

// ---------------------------------------------------------------------------
// G6 — one MessageDigest lookup in the tree, and it is `Hashing`'s.
//
// Fifteen files once called `MessageDigest.getInstance` directly and each re-decided the
// surrounding questions — three buffer sizes, four reactions to NoSuchAlgorithmException, one
// of them a silent fall back to `Integer.toHexString(s.hashCode())` standing in for a cache key.
// jk's own algorithm is read from the owner, so changing the digest moves the ban with it.
// ---------------------------------------------------------------------------
val hashingOwner = "shared/host/src/main/java/cc/jumpkick/host/Hashing.java"

guard("G6", "checkOneDigestSurface") {
    val ownAlgorithm = Regex("""newSha256\(\)\{returnnewDigest\("([^"]+)"\);}""")
        .find(guardText(owner(hashingOwner)))?.groupValues?.get(1)
        ?: error("cannot read jk's algorithm out of Hashing: newSha256() is expected to be"
            + " `return newDigest(\"<algorithm>\");`")
    val lookup = Regex("""MessageDigest\.getInstance\(""")
    val byName = Regex("""(?:newDigest|fileHex|hashHex)\(""" + Regex.escape("\"$ownAlgorithm\""))
    val hits = mutableListOf<String>()
    mainJava.filter { rel(it) != hashingOwner }.forEach { f ->
        val code = guardTextOf(f)
        val r = rel(f)
        countIn(code, lookup).let { if (it > 0) hits.add("$r: $it x MessageDigest.getInstance(..)") }
        countIn(code, byName).let { if (it > 0) hits.add("$r: $it x \"$ownAlgorithm\" passed to a Hashing algorithm door") }
    }
    if (hits.isNotEmpty()) {
        error("jk hashes in one place, cc.jumpkick.host.Hashing — a second digest site re-decides the"
            + " buffer size, the exception policy and the hex spelling, and one of them silently"
            + " substituted String.hashCode() for a cache key:\n" + bullets(hits.sorted())
            + "\n  Hashing.sha256Hex(bytes | String | Path) or Hashing.newSha256() for jk's own"
            + " hashing. newDigest/fileHex/hashHex take an algorithm name only when a foreign format"
            + " dictates it — a .sha1 sidecar, an SDK feed — never \"$ownAlgorithm\".")
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

// ---------------------------------------------------------------------------
// G8 / G43 — one archive instant, one class that stamps an entry, one byte sink.
//
// `ZipEntry.setTime` converts to DOS time through the JVM's default timezone, so identical
// inputs written under a different $TZ produce different bytes and the raw-archive fingerprints
// that key the action cache stop matching. `setTimeLocal` is the TZ-free spelling and the two
// differ by five characters. G43 is the same owner from the other side: ZipOutputStream inherits
// a 512-byte write buffer, so a 9 MB jar became ~18,000 write(2) calls where 64 KB gives ~143.
// ---------------------------------------------------------------------------
val deterministicZip = "shared/host/src/main/java/cc/jumpkick/host/DeterministicZip.java"

guard("G8", "checkSingleArchiveInstant") {
    val ownerText = owner(deterministicZip)
    val epoch = Regex("""public static final long EPOCH_SECONDS = ([0-9_]+)L;""")
        .find(ownerText)?.groupValues?.get(1)
        ?: error("DeterministicZip no longer declares EPOCH_SECONDS, so this guard has lost the owner it reads.")
    val numbers = setOf(epoch, epoch.replace("_", ""))
    val hits = mutableListOf<String>()
    mainJava.forEach { f ->
        val code = guardTextOf(f)
        val r = rel(f)
        val byTime = countIn(code, Regex("""\.setTime\("""))
        if (byTime > 0) hits.add("$r: $byTime x setTime(  ->  DeterministicZip.entry")
        if (r == deterministicZip) return@forEach
        val byLocal = countIn(code, Regex("""\.setTimeLocal\("""))
        if (byLocal > 0) hits.add("$r: $byLocal x setTimeLocal(  ->  DeterministicZip.entry")
        numbers.forEach { v ->
            val n = countLiteral(code, v)
            if (n > 0) hits.add("$r: $n x $v  ->  DeterministicZip.EPOCH_SECONDS")
        }
    }
    if (hits.isNotEmpty()) {
        error("An archive entry is stamped in one place, cc.jumpkick.host.DeterministicZip. These"
            + " stamp their own:\n" + bullets(hits.sorted())
            + "\n  setTime converts to DOS time through the default timezone, so an archive written"
            + " with it is a function of the build host's \$TZ, and a second copy of the epoch is a"
            + " second instant waiting to drift.")
    }
}

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
// G9 — bytes become hex in one place, `Hashing.hex`.
//
// Two shapes. A per-byte hex loop allocates a Formatter per byte (a 200-entry classpath cost
// ~6,400 of them per compile) and is easy to write without the `& 0xff` mask. `java.util.HexFormat`
// is not a loop and is a perfectly reasonable line of Java — which is exactly why it was written
// sixteen times before it was swept. Arm 2 is scanned over text that still carries imports,
// because `import static java.util.HexFormat.of` is invisible to any pattern that drops them.
//
// Uppercase hex for a non-digest encoding is a different function: written as
// `Character.toUpperCase(Character.forDigit(..))` it is exempt BY SHAPE, not by filename.
// ---------------------------------------------------------------------------

guard("G9", "checkNoHandRolledHex") {
    val hexLoop = Regex("""%02[xX]|(?<!Character\.toUpperCase\()Character\.forDigit\(""")
    val hexFormat = Regex("""\bHexFormat\b""")
    val hits = mutableListOf<String>()
    mainJava.forEach { f ->
        val r = rel(f)
        countIn(guardTextOf(f), hexLoop).let { if (it > 0) hits.add("%5d  %s  per-byte hex loop".format(it, r)) }
        if (r == hashingOwner) return@forEach
        val withImports = squashBetweenLiterals(codeOf(f))
        countIn(withImports, hexFormat).let { if (it > 0) hits.add("%5d  %s  java.util.HexFormat".format(it, r)) }
    }
    if (hits.isNotEmpty()) {
        error("Bytes become hex in one place, cc.jumpkick.host.Hashing.hex. These spell it"
            + " themselves:\n" + bullets(hits.sorted())
            + "\n  Call Hashing.hex(byte[]) — or sha256Hex, which does the digest too."
            + " String.format(\"%02x\", b) allocates a Formatter per byte."
            + "\n  HexFormat.of().formatHex(digest) looks fine and is not slow; it is banned because"
            + " it is a SECOND ANSWER to \"how does jk spell bytes\", and sixteen call sites had each"
            + " answered it separately before the sweep."
            + "\n  Going the other way (parseHex) has no owner yet: add Hashing.unhex(String) next to"
            + " hex(byte[]) and call that, rather than reopening the shape here."
            + "\n  Uppercase hex for a non-digest encoding is a different function: write it as"
            + " Character.toUpperCase(Character.forDigit(..)), which this guard exempts by shape.")
    }
}

// ---------------------------------------------------------------------------
// G10 — the size caps are a ratchet, not a suggestion.
//
// Unenforced caps regrow: a class finished its peel at 1,064 lines and was back over 1,200
// eleven days later. Three rules against the checked-in `size-baseline.txt`: a listed file may
// only shrink; an unlisted file must be at or under its language's hard cap; every listed file
// claims the exception band, so every entry carries the invariant comment. The caps are also a
// house rule written down in code-as-art.md, and both are read here so the doc and the guard
// cannot drift.
// ---------------------------------------------------------------------------
val fileSizeHardCaps = mapOf("java" to 800, "kt" to 800, "js" to 1200, "mjs" to 1200)

val cappedSources: List<Path> = moduleDirs.flatMap { m ->
    filesUnder(m.resolve("src/main/java"), ".java") +
        filesUnder(m.resolve("src/main/kotlin"), ".kt") +
        filesUnder(m.resolve("src/main/resources"), ".js") +
        filesUnder(m.resolve("src/main/resources"), ".mjs") +
        filesUnder(m.resolve("src/test/java"), ".java") +
        filesUnder(m.resolve("src/test/kotlin"), ".kt") +
        filesUnder(m.resolve("src/testFixtures/java"), ".java") +
        filesUnder(m.resolve("src/test/js"), ".js") +
        filesUnder(m.resolve("src/test/js"), ".mjs")
}

guard("G10", "checkFileSizeCaps") {
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
    (docCaps.keys + fileSizeHardCaps.keys).toSortedSet().forEach { ext ->
        val doc = if (ext in docCaps) docCaps[ext]?.toString() ?: "exempt" else "absent"
        val task = fileSizeHardCaps[ext]?.toString() ?: "exempt"
        if (doc != task) drift.add(".$ext: charter says $doc, gate enforces $task")
    }
    if (drift.isNotEmpty()) {
        error("The size caps in code-as-art.md and `fileSizeHardCaps` disagree. A cap the charter"
            + " states and the build does not enforce is worse than no cap:\n" + bullets(drift))
    }

    // An entry is `<lines>  <path>`; its invariant is the comment block directly above it, with
    // no blank line between.
    val listed = LinkedHashMap<String, Int>()
    val malformed = mutableListOf<String>()
    val undocumented = mutableListOf<String>()
    var documented = false
    Files.readAllLines(at("size-baseline.txt")).forEachIndexed { i, raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> documented = false
            line.startsWith("#") -> documented = true
            else -> {
                val parts = line.split(Regex("\\s+"))
                val lines = if (parts.size == 2) parts[0].toIntOrNull() else null
                if (lines == null) {
                    malformed.add("size-baseline.txt:${i + 1}: expected `<lines>  <path>`, got `$line`")
                } else {
                    listed[parts[1]] = lines
                    if (!documented) undocumented.add("size-baseline.txt:${i + 1}: ${parts[1]}")
                }
                documented = false
            }
        }
    }

    val grew = mutableListOf<String>()
    val overCap = mutableListOf<String>()
    val loose = mutableListOf<String>()
    val present = mutableSetOf<String>()
    cappedSources.forEach { f ->
        val ext = f.fileName.toString().substringAfterLast('.').lowercase(Locale.ROOT)
        val hard = fileSizeHardCaps[ext] ?: return@forEach
        val r = rel(f)
        val n = codeLines(f, ext)
        present.add(r)
        val listedAt = listed[r]
        when {
            listedAt == null && n > hard -> overCap.add("$r: $n code lines, hard cap $hard")
            listedAt != null && n > listedAt -> grew.add("$r: $n code lines, baseline $listedAt (+${n - listedAt})")
            listedAt != null && n < listedAt -> loose.add("%5d  %s   (was %d)".format(n, r, listedAt))
        }
    }
    listed.forEach { (r, a) -> if (r !in present) loose.add("(deleted) $r   (was $a)") }

    val faults = mutableListOf<String>()
    if (grew.isNotEmpty()) faults.add("A file in size-baseline.txt may only shrink. These grew:\n" + bullets(grew))
    if (overCap.isNotEmpty()) {
        faults.add("Over the hard cap for their language and not in size-baseline.txt:\n" + bullets(overCap)
            + "\n  Split the file, or add it to size-baseline.txt with the invariant that must not be split.")
    }
    if (undocumented.isNotEmpty()) {
        faults.add("Every size-baseline.txt entry is a file claiming the exception band, and the"
            + " exception band costs a stated invariant. Write the comment directly above the"
            + " entry:\n" + bullets(undocumented))
    }
    if (malformed.isNotEmpty()) faults.add("size-baseline.txt is malformed:\n" + bullets(malformed))
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
    if (loose.isNotEmpty()) notes.add("size-baseline.txt is loose (these shrank — tighten it in this commit):\n" + bullets(loose))
}

// ---------------------------------------------------------------------------
// G11 — a fully-qualified class name in the body of a file is a ratchet.
//
// The guard counts EVERY package-qualified reference, not only the kinds `jk format` can fix:
// narrowing it to type references would leave the most common residual shape — a qualified
// static call — permanently unguarded. A genuine collision keeps its FQCN and is listed under
// `## collisions` with the name it collides with.
// ---------------------------------------------------------------------------

// Two or more all-lowercase dot-separated segments followed by an UpperCamel identifier. Two
// segments is the floor because `cc.jumpkick.Foo` has exactly two, and requiring two is what
// keeps `builder.config.Value` — a field chain, not a package — out.
val fqcnPattern = Regex("""(?<![\w.$])(?:[a-z][a-z0-9_]*\.){2,}[A-Z][A-Za-z0-9_]*""")

fun countFqcns(p: Path): Int =
    blankedOf(p).lineSequence().sumOf { raw ->
        val s = raw.trimStart()
        if (s.startsWith("import ") || s.startsWith("package ")) 0 else fqcnPattern.findAll(raw).count()
    }

guard("G11", "checkNoFqcn") {
    val listed = LinkedHashMap<String, Int>()
    val malformed = mutableListOf<String>()
    val unexplained = mutableListOf<String>()
    var section: String? = null
    var lastWasComment = false
    Files.readAllLines(at("fqcn-baseline.txt")).forEachIndexed { i, raw ->
        val line = raw.trim()
        when {
            line.startsWith("##") -> { section = line.removePrefix("##").trim(); lastWasComment = true }
            line.startsWith("#") -> lastWasComment = true
            line.isEmpty() -> {}
            else -> {
                val parts = line.split(Regex("\\s+"))
                val count = if (parts.size == 2) parts[0].toIntOrNull() else null
                when {
                    count == null -> malformed.add("fqcn-baseline.txt:${i + 1}: expected `<count>  <path>`, got `$line`")
                    section == null -> unexplained.add("fqcn-baseline.txt:${i + 1}: ${parts[1]} — no `## <reason>` section above it")
                    section == "collisions" && !lastWasComment ->
                        unexplained.add("fqcn-baseline.txt:${i + 1}: ${parts[1]} — a collision must name what it collides with")
                    else -> listed[parts[1]] = count
                }
                lastWasComment = false
            }
        }
    }

    val grew = mutableListOf<String>()
    val unlisted = mutableListOf<String>()
    val loose = mutableListOf<String>()
    val present = mutableSetOf<String>()
    (mainJava + testJava).forEach { f ->
        val r = rel(f)
        val n = countFqcns(f)
        present.add(r)
        val a = listed[r]
        when {
            a == null && n > 0 -> unlisted.add("%5d  %s".format(n, r))
            a != null && n > a -> grew.add("$r: $n FQCNs, baseline $a (+${n - a})")
            a != null && n < a -> loose.add("%5d  %s   (was %d)".format(n, r, a))
        }
    }
    listed.forEach { (r, a) -> if (r !in present) loose.add("(deleted) $r   (was $a)") }

    val faults = mutableListOf<String>()
    if (unlisted.isNotEmpty()) {
        faults.add("A fully-qualified class name in a method body is banned — import the type. These"
            + " files are not in fqcn-baseline.txt:\n" + bullets(unlisted)
            + "\n  `jk format` shortens type references for you. A static member, an annotation or a"
            + " third-party type it cannot reach is a hand edit. A genuine collision goes under"
            + " `## collisions` with the name it collides with.")
    }
    if (grew.isNotEmpty()) faults.add("A file in fqcn-baseline.txt may only shrink. These grew:\n" + bullets(grew))
    if (unexplained.isNotEmpty()) faults.add("Every fqcn-baseline.txt entry states why the FQCN survives:\n" + bullets(unexplained))
    if (malformed.isNotEmpty()) faults.add("fqcn-baseline.txt is malformed:\n" + bullets(malformed))
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
    if (loose.isNotEmpty()) notes.add("fqcn-baseline.txt is loose (these shrank — tighten it in this commit):\n" + bullets(loose))
}

// ---------------------------------------------------------------------------
// G12 / G13 / G15 / G17 / G18 — a name with an owner is spelled once.
//
// All five are the same shape: read the vocabulary out of the owner, ban the literal everywhere
// else. A step name typed as a literal is a producer/consumer contract with no compiler behind
// it — a typo is not a build error, it is an edge that quietly does not exist. Scope is
// src/main/java: test sources keep their literals on purpose, because a golden that borrowed the
// constant would follow a rename of the value and still pass.
// ---------------------------------------------------------------------------

/** Ban every literal in [named] outside [exempt]; report `<path>: <n> x "<value>" -> <hint>`. */
fun bareNameHits(named: Map<String, String>, hint: (String) -> String, exempt: Set<String>): List<String> {
    val hits = mutableListOf<String>()
    mainJava.forEach { f ->
        val r = rel(f)
        if (r in exempt) return@forEach
        val code = guardTextOf(f)
        val present = quotedLiteralsOf(f)
        named.forEach { (value, constant) ->
            if (value !in present) return@forEach
            val n = countLiteral(code, "\"$value\"")
            if (n > 0) hits.add("$r: $n x \"$value\"  ->  ${hint(constant)}")
        }
    }
    return hits.sorted()
}

val taskNamesOwner = "shared/jk-api/src/main/java/cc/jumpkick/run/TaskNames.java"
val graalLauncherOwner = "shared/host/src/main/java/cc/jumpkick/host/GraalLauncher.java"

guard("G12", "checkNoBareTaskName") {
    // Hyphenated names only. The single-word values (`train`, `install`, `select`, …) are ordinary
    // English that appears in paths, help text and Maven scopes; banning them by text scan would be
    // false positives all the way down.
    val named = namedConstants(owner(taskNamesOwner)).filterKeys { it.contains('-') }
    if (named.isEmpty()) error("TaskNames declares no hyphenated constant, so this guard has lost the owner it reads.")
    owner(graalLauncherOwner) // declared so a move fails loudly rather than silently un-exempting
    val hits = bareNameHits(named, { "TaskNames.$it" }, setOf(taskNamesOwner, graalLauncherOwner))
    if (hits.isNotEmpty()) {
        error("A step name typed as a literal is a producer/consumer contract with no compiler behind"
            + " it — a typo becomes a missing dependency edge, not a build error. These name a step"
            + " by hand:\n" + bullets(hits)
            + "\n  Reference cc.jumpkick.run.TaskNames instead. A string that is NOT a step name —"
            + " GraalVM's native-image launcher file, say — must not borrow the constant either: it"
            + " has its own owner, cc.jumpkick.host.GraalLauncher.")
    }
}

guard("G13", "checkNoBareManifestName") {
    val ownerPath = "shared/core/src/main/java/cc/jumpkick/lock/ManifestPaths.java"
    val named = namedConstants(owner(ownerPath))
    if (named.isEmpty()) error("ManifestPaths declares no constant, so this guard has lost the owner it reads.")
    val hits = bareNameHits(named, { "ManifestPaths.$it" }, setOf(ownerPath))
    if (hits.isNotEmpty()) {
        error("A file jk owns is named once, in cc.jumpkick.lock.ManifestPaths. These re-type the"
            + " name:\n" + bullets(hits)
            + "\n  Reference the constant. A file that is NOT jk's — a third-party tool's own"
            + " config.toml, say — must not borrow it either: give that vocabulary its own owner.")
    }
}

/** Tier names another vocabulary also spells; each has its own owner. */
val tierNameHomonyms = setOf("sha256", "generated", "projects")

guard("G15", "checkNoBareTierName") {
    val ownerPath = "shared/host/src/main/java/cc/jumpkick/host/CacheTree.java"
    val named = Regex("""^\s{4}([A-Z][A-Z0-9_]*)\("([^"]+)"""", RegexOption.MULTILINE)
        .findAll(owner(ownerPath))
        .associate { it.groupValues[2] to it.groupValues[1] }
        .filterKeys { it !in tierNameHomonyms }
    if (named.size < 10) {
        error("CacheTree yielded only ${named.size} tier names, so this guard has lost the owner it"
            + " reads. Restore the enum's shape or retire the guard deliberately.")
    }
    val hits = bareNameHits(named, { "CacheTree.$it" }, setOf(ownerPath))
    if (hits.isNotEmpty()) {
        error("A cache tier is named once, in cc.jumpkick.host.CacheTree. These re-type the name:\n"
            + bullets(hits)
            + "\n  Use CacheTree.<TIER>.under(cacheRoot). A directory that is NOT a cache tier — a"
            + " module's own build/generated, say — must not borrow the constant either.")
    }
}

guard("G17", "checkNoBareWireType") {
    val ownerPath = "shared/wire/src/main/java/cc/jumpkick/engine/protocol/EngineProtocol.java"
    val named = namedConstants(owner(ownerPath)).filterKeys { it.contains('-') }
    if (named.isEmpty()) {
        error("EngineProtocol no longer declares any hyphenated token, so this guard has lost the"
            + " owner it reads.")
    }
    val hits = bareNameHits(named, { "EngineProtocol.$it" }, setOf(ownerPath))
    if (hits.isNotEmpty()) {
        error("A wire message type typed as a literal is a producer/consumer contract with no"
            + " compiler behind it — a typo becomes an event nobody handles, not a build error."
            + " These name one by hand:\n" + bullets(hits)
            + "\n  Reference EngineProtocol instead. A string that is NOT a socket-protocol type — a"
            + " dashboard-only SSE frame, a CLI transcript envelope — keeps its own literal.")
    }
}

/** Files where `target` means something other than jk's output directory. */
val targetHomonyms = setOf(
    // A CLI parameter named `target` (a coordinate, a file, a directory or a git URL).
    "clients/cli/src/main/java/cc/jumpkick/command/ToolInstallCommand.java",
    "clients/cli/src/main/java/cc/jumpkick/command/ToolRunCommand.java",
    // The `target` field of a BSP request, in someone else's protocol.
    "clients/cli/src/main/java/cc/jumpkick/cli/bsp/BspServer.java",
    // groovyc's `target` option (a bytecode level).
    "plugins/groovy-compiler/src/main/java/cc/jumpkick/groovy/compiler/GroovyCompiler.java",
    // Maven's `<target>` compiler configuration key, in someone else's file.
    "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java",
    // The `${target}` interpolation variable name. It expands TO the output directory, but the
    // token is a variable name in jk.toml, and renaming the directory must not rename it.
    "shared/core/src/main/java/cc/jumpkick/config/TestEnvValues.java")

guard("G18", "checkNoBareTargetDir") {
    val ownerPath = "shared/core/src/main/java/cc/jumpkick/layout/BuildLayout.java"
    val value = Regex("""public static final String TARGET = "([^"]+)";""")
        .find(owner(ownerPath))?.groupValues?.get(1)
        ?: error("BuildLayout no longer declares TARGET, so this guard has lost the owner it reads.")
    val hits = bareNameHits(mapOf(value to "TARGET"), { "BuildLayout.$it" }, targetHomonyms + ownerPath)
    if (hits.isNotEmpty()) {
        error("jk's build output directory is named once, in BuildLayout.TARGET. These re-type it:\n"
            + bullets(hits)
            + "\n  Reference the constant — or better, BuildLayout.moduleTargetDir(ws, mod), which"
            + " also knows about the workspace central out tree. A `target` that is NOT this"
            + " directory — a CLI parameter, javac's -target, a BSP field — must not borrow it"
            + " either: add it to targetHomonyms with a reason.")
    }
}

// ---------------------------------------------------------------------------
// G16 — Maven Central is addressed one way, and it is `RepositorySpec`'s.
//
// `repo1.maven.org` is a CNAME for the canonical host, so it fetches the same bytes — but the
// mirror and the per-host cooldown both key on the canonical host, and a request addressed to
// the alias matches neither. The name half matters too: `central` is the store directory, the
// lockfile `source` prefix and the repo-group entry at once, so a store written under one
// spelling and read under another is a cache that silently never hits.
// ---------------------------------------------------------------------------

/** Files that recognise a foreign build file's declared repository — every spelling a user may have typed. */
val foreignRepoReaders = setOf(
    "server/toolchain/src/main/java/cc/jumpkick/gradle/GradleImporter.java",
    "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java",
    "shared/toolchain-jdk/src/main/java/cc/jumpkick/gradle/GradleExporter.java")

/** Files whose `google` is a formatter style name (ktfmt / google-java-format), not a repository. */
val formatStyleVocabulary = setOf(
    "shared/core/src/main/java/cc/jumpkick/config/FormatStyles.java",
    "plugins/formatter/src/main/java/cc/jumpkick/format/CodeFormatter.java")

/** Repo-name literals not yet calling `RepositorySpec`. Delete the map when it empties. */
val repoNameRatchet = mapOf(
    "server/engine/src/main/java/cc/jumpkick/engine/plugin/PluginJar.java" to 1,
    "server/engine/src/main/java/cc/jumpkick/runtime/RepoGroupBuilder.java" to 1)

/** The host that resolves to Central but matches neither the mirror nor the cooldown. */
val centralAliasHost = "repo1.maven.org"

guard("G16", "checkSingleCentralAddress") {
    val ownerPath = "shared/jk-api/src/main/java/cc/jumpkick/model/RepositorySpec.java"
    val ownerText = owner(ownerPath)
    val named = namedConstants(ownerText).toMutableMap()
    val centralUrl = Regex("""MAVEN_CENTRAL\s*=\s*new RepositorySpec\([^;]*?URI\.create\("([^"]+)"\)""")
        .find(ownerText)?.groupValues?.get(1)
        ?: error("RepositorySpec no longer builds MAVEN_CENTRAL from a URI literal, so this guard has"
            + " lost the owner it reads.")
    named[centralUrl] = "MAVEN_CENTRAL.url()"

    val exempt = foreignRepoReaders + formatStyleVocabulary + ownerPath
    val aliasHits = mutableListOf<String>()
    val nameCounts = LinkedHashMap<String, Int>()
    val nameDetails = LinkedHashMap<String, MutableList<String>>()
    mainJava.forEach { f ->
        val r = rel(f)
        if (r in exempt) return@forEach
        val code = guardTextOf(f)
        val aliased = countLiteral(code, centralAliasHost)
        if (aliased > 0) aliasHits.add("$r: $aliased x $centralAliasHost  ->  RepositorySpec.MAVEN_CENTRAL.url()")
        named.forEach { (value, constant) ->
            // A value sitting immediately after `"cc",` is a package path segment
            // (Path.of("cc", "jumpkick", ...)), not a repository name.
            val n = countIn(code, Regex("""(?<!"cc",)""" + Regex.escape("\"$value\"")))
            if (n > 0) {
                nameCounts.merge(r, n, Int::plus)
                nameDetails.getOrPut(r) { mutableListOf() }.add("$r: $n x \"$value\"  ->  RepositorySpec.$constant")
            }
        }
    }
    val (grew, unlisted, loose) = ratchetVerdict(nameCounts, repoNameRatchet)
    val faults = mutableListOf<String>()
    if (aliasHits.isNotEmpty() || unlisted.isNotEmpty()) {
        val offending = unlisted.map { it.trim().substringAfter("  ") }
        val details = nameDetails.filterKeys { it in offending }.values.flatten()
        faults.add("Maven Central is addressed once, through RepositorySpec.MAVEN_CENTRAL, and a"
            + " repository name is spelled once, in RepositorySpec. These spell it themselves:\n"
            + bullets((aliasHits + details).sorted())
            + "\n  $centralAliasHost is a CNAME for the canonical host, so the mirror and the"
            + " cooldown match neither it nor the traffic sent to it — and reaching Central at all"
            + " outside cc.jumpkick.http.Http misses both regardless of the hostname. Use the"
            + " constant AND the shared transport.")
    }
    if (grew.isNotEmpty()) faults.add("A file on the repo-name ratchet may only shrink. These grew:\n" + bullets(grew))
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
    if (loose.isNotEmpty()) notes.add("repoNameRatchet is loose (these shrank — tighten it in this commit):\n" + bullets(loose))
}

// ---------------------------------------------------------------------------
// G20 — the host is read in one place, and paths join in one place.
//
// `isWindows` once had an owner and fourteen private copies, and the copies tested
// `os.name.contains("win")` — but "win" is a substring of **Darwin**, so every short copy called
// a Mac a Windows box. Arm 1 bans the PROPERTY READ, not the predicate derived from it: there is
// exactly one thing every copy must do first, and banning that bounds the count by the defect
// rather than by the pattern. Arm 2 has two owners because the vocabulary genuinely overlaps —
// `File.pathSeparator` is also PATH's separator, and PATH is an executable search path.
// ---------------------------------------------------------------------------

/** `PATH` sites not yet calling `SearchPath`. Delete the map when it empties. */
val pathSeparatorRatchet = mapOf(
    "server/engine/src/main/java/cc/jumpkick/runtime/SourceProjectBuilder.java" to 1)

guard("G20", "checkSingleHostSurface") {
    val osOwner = "shared/host/src/main/java/cc/jumpkick/host/Os.java"
    val cpOwner = "shared/host/src/main/java/cc/jumpkick/host/Classpaths.java"
    val spOwner = "shared/host/src/main/java/cc/jumpkick/host/SearchPath.java"
    val properties = Regex("""public static final String \w*_?PROPERTY = "([^"]+)";""")
        .findAll(owner(osOwner)).map { it.groupValues[1] }.toList()
    if (properties.isEmpty()) error("Os declares no *_PROPERTY constant, so this guard has lost the owner it reads.")
    val sepConstant = Regex("""public static final String SEPARATOR = ([\w.]+);""")
        .find(owner(cpOwner))?.groupValues?.get(1)
        ?: error("Classpaths no longer initialises SEPARATOR from a named constant, so this guard has"
            + " lost the spelling it reads.")
    owner(spOwner)
    val separators = listOf(
        Regex(Regex.escape(sepConstant) + """\b"""),
        Regex(Regex.escape(sepConstant) + """Char\b"""),
        // Not read from the owner, and it cannot drift from it: this is the JDK property
        // File.pathSeparator is itself initialised from.
        Regex("""System\.getProperty\("path\.separator""""))

    val unownedOsReads = mutableListOf<String>()
    val sepHits = LinkedHashMap<String, Int>()
    mainJava.forEach { f ->
        val r = rel(f)
        val code = guardTextOf(f)
        if (r != osOwner) {
            properties.forEach { prop ->
                val n = countIn(code, Regex("""System\.getProperty\(""" + Regex.escape("\"$prop\"")))
                if (n > 0) unownedOsReads.add("$r: $n x System.getProperty(\"$prop\")")
            }
        }
        if (r != cpOwner && r != spOwner) {
            val n = separators.sumOf { countIn(code, it) }
            if (n > 0) sepHits[r] = n
        }
    }
    val (grew, unlisted, loose) = ratchetVerdict(sepHits, pathSeparatorRatchet)
    val faults = mutableListOf<String>()
    if (unownedOsReads.isNotEmpty()) {
        faults.add("The host is read in one place, cc.jumpkick.host.Os. These read the property"
            + " themselves:\n" + bullets(unownedOsReads.sorted())
            + "\n  Ask Os.isWindows() / isDarwin() / isLinux(), or Os.name() when you need the raw"
            + " string for a message or a test seam. Do NOT re-derive the predicate: a copy that"
            + " tests contains(\"win\") calls Darwin a Windows box.")
    }
    if (unlisted.isNotEmpty()) {
        faults.add("The separator has two owners, one per vocabulary: Classpaths for -cp, SearchPath"
            + " for PATH. These name it themselves and are not on the ratchet:\n" + bullets(unlisted)
            + "\n  Call Classpaths.join(entries) / Classpaths.split(cp) for a classpath, or"
            + " SearchPath.prepend(binDir, existing) / SearchPath.entries(path) for an executable"
            + " search path — the two disagree about blank entries on purpose.")
    }
    if (grew.isNotEmpty()) faults.add("A file on the path-separator ratchet may only shrink. These grew:\n" + bullets(grew))
    if (faults.isNotEmpty()) error(faults.joinToString("\n\n"))
    if (loose.isNotEmpty()) notes.add("pathSeparatorRatchet is loose (these shrank — tighten it in this commit):\n" + bullets(loose))
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

// ---------------------------------------------------------------------------
// G24 — the AOT refusal marker is spelled in exactly one file.
//
// Two arms, because the two source sets fail differently. In main, a string literal may not
// contain the marker suffix at all. In test, a fixture may spell a WHOLE cache file name —
// pinning the on-disk spelling is what those tests are for — but not the bare suffix, which is
// the owner's rule re-derived by concatenation.
// ---------------------------------------------------------------------------

guard("G24", "checkSingleAotMarkerSpelling") {
    val ownerPath = "shared/host/src/main/java/cc/jumpkick/host/AotCacheFiles.java"
    val ownerText = owner(ownerPath)
    fun constant(name: String): String =
        Regex("""String\s+$name\s*=\s*"([^"]+)"""").find(ownerText)?.groupValues?.get(1)
            ?: error("AotCacheFiles no longer declares a String $name, so this guard has lost the owner it reads.")
    val marker = constant("MARKER")
    val cache = constant("CACHE")

    val hits = mutableListOf<String>()
    mainJava.filter { rel(it) != ownerPath }.forEach { f ->
        javaStringLiterals(text(f)).filter { it.contains(marker) }.forEach { hits.add("${rel(f)}: \"$it\"") }
    }
    testJava.forEach { f ->
        javaStringLiterals(text(f)).filter { it == marker || it == cache + marker }
            .forEach { hits.add("${rel(f)}: \"$it\"") }
    }
    if (hits.isNotEmpty()) {
        error("The AOT refusal marker is named once, in cc.jumpkick.host.AotCacheFiles. These re-type"
            + " it:\n" + bullets(hits)
            + "\n  Use AotCacheFiles.marker(cache) / isMarker(name) / cacheOf(name) / blocked(cache),"
            + " or AotCacheFiles.MARKER when only the suffix will do. A test fixture may spell a"
            + " whole cache file name, never the bare suffix.")
    }
}

// ---------------------------------------------------------------------------
// G25 / G26 — `plugins/` holds two architectures, and a module's family is declared once.
//
// An SPI plugin ships a jk-plugin.toml: the engine discovers it by descriptor and takes its wire
// prefix from [code].protocol-prefix, and NO engine source names the prefix. A forked worker
// ships no descriptor: the engine hardcodes its argv, so an engine source MUST name the prefix.
// Comparing siblings across that boundary and "fixing" the difference is what this prevents.
// G26 is the same family's other rule: a plugin does not roll its own fork.
// ---------------------------------------------------------------------------

/** [src] with comments blanked to spaces (newlines and offsets kept), string literals verbatim. */
fun familyGuardCode(src: String): String = blankNonCode(src, blankStrings = false)

/**
 * Plugin sources that legitimately construct their own `ProcessBuilder`, by module-relative path.
 * Every entry is a fork `ToolRun` cannot express, and says which.
 */
val pluginForkExemptions = mapOf(
    // Three container-runtime forks (`docker`/`podman` info, run, stop). The runtime is a PATH
    // *name* the user may configure, not a resolved path, and `ToolRun` absolutises its
    // executable head — so expressing these needs a PATH-search owner first.
    "plugins/image-builder/src/main/java/cc/jumpkick/plugin/image/AotCacheTrainer.java" to 3)

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

guard("G26", "checkPluginForkOwner") {
    val toolRun = owner("shared/plugin-sdk/src/main/java/cc/jumpkick/plugin/build/TaskExec.java")
    if (!Regex("""Process\s+start\s*\(\s*\)""").containsMatchIn(toolRun)) {
        error("TaskExec.ToolRun no longer declares `Process start()`, so this guard has lost the fork"
            + " owner it points plugins at.")
    }
    val hits = mutableListOf<String>()
    pluginModules.forEach { module ->
        val files = filesUnder(module.resolve("src/main/java"), ".java")
        if (files.isEmpty()) {
            hits.add("${module.fileName}: no Java source under src/main/java, so this guard verified nothing for it")
            return@forEach
        }
        files.forEach { f ->
            val r = rel(f)
            val code = familyGuardCode(text(f))
            val found = Regex("""new\s+ProcessBuilder\s*\(""").findAll(code).toList()
            val allowed = pluginForkExemptions[r] ?: 0
            if (found.size > allowed) {
                found.drop(allowed).forEach { m -> hits.add("$r:${lineAt(code, m.range.first)}") }
                if (allowed > 0) hits.add("  ($r is exempt for $allowed fork(s); it now has ${found.size})")
            }
        }
    }
    if (hits.isNotEmpty()) {
        error("A plugin forks a process without going through the SDK's one fork owner. Take a"
            + " TaskExec.ToolRun from your exec surface — exec.tool(name) for a JDK tool off the"
            + " build's javaHome, exec.tool(path) for a provisioned binary — then run() /"
            + " stream(sink), or start() when you need your own drain:\n" + bullets(hits)
            + "\n  A hand-rolled fork is where the wrong JDK and the missing .exe get in.")
    }
}

// ---------------------------------------------------------------------------
// G28 — a retired wire-key spelling is not typed as a field key.
//
// Retired names have no code owner by definition — there is deliberately no constant left to
// read them from — so the ban list lives here and the live owners are named in the message.
// `class` is banned in the SPA only: `.class` literals and the test-worker discovery protocol
// keep that token live in Java on purpose, so the ban's shape and the defect's shape only match
// in the dashboard, which cannot import Java and hand-types every key it reads.
// ---------------------------------------------------------------------------
val spaAssets: List<Path> = filesUnder(at("clients/web/src/main/resources/web"), ".js") +
    filesUnder(at("clients/web/src/main/resources/web"), ".html")

/** True when [body] references [name] as a field key: quoted (a JSON key) or dotted (a JS read). */
fun readsTheKey(body: String, name: String): Boolean {
    var i = body.indexOf(name)
    while (i >= 0) {
        if (i > 0) {
            val before = body[i - 1]
            val after = body.getOrNull(i + name.length)
            // The trailing boundary matters: `.className` and `.classList` are reads of longer
            // identifiers, not of the banned key.
            val wholeWord = after == null || !(after.isLetterOrDigit() || after == '_' || after == '$')
            if (wholeWord && (before == '"' || before == '\'' || before == '.')) return true
        }
        i = body.indexOf(name, i + 1)
    }
    return false
}

guard("G28", "checkNoRetiredWireSpelling") {
    if (spaAssets.size < 15) {
        error("scanned ${spaAssets.size} SPA assets; measured against 24. The walk has stopped seeing"
            + " clients/web/src/main/resources/web.")
    }
    val retiredEverywhere = listOf(
        "testTotal", "testSucceeded", "testFailed", "testSkipped",
        "testsTotal", "testsSucceeded", "testsFailed", "testsSkipped")
    val hits = mutableListOf<String>()
    mainJava.forEach { f ->
        retiredEverywhere.filter { readsTheKey(text(f), it) }.forEach { hits.add("${rel(f)}: \"$it\"") }
    }
    spaAssets.forEach { f ->
        (retiredEverywhere + "class").filter { readsTheKey(text(f), it) }.forEach { hits.add("${rel(f)}: \"$it\"") }
    }
    if (hits.isNotEmpty()) {
        error("Retired wire-key spellings are back in production source:\n" + bullets(hits)
            + "\n  Test counts have one owner: TestSummary.WIRE_KEY + countsJson/countsMap/readCounts"
            + " (the nested tests:{total,succeeded,failed,skipped} object). The failing test's class"
            + " is EngineProtocol.TEST_CLASS_FIELD (\"testClass\"); the SPA reads d.testClass only.")
    }
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
val knownCatalogLockDrift = setOf(
    "com.diffplug.spotless:spotless-lib",
    "com.google.cloud.tools:jib-core",
    "dev.sigstore:sigstore-java",
    "org.apache.groovy:groovy",
    "org.bouncycastle:bcpg-jdk18on",
    "org.eclipse.jgit:org.eclipse.jgit",
    "org.graalvm.sdk:nativeimage",
    "org.junit.jupiter:junit-jupiter",
    "org.junit.platform:junit-platform-engine",
    "org.junit.platform:junit-platform-launcher",
    "org.slf4j:slf4j-api",
    "org.slf4j:slf4j-nop")

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
    val ownerPath = "shared/host/src/testFixtures/java/cc/jumpkick/testing/RepoRoot.java"
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
        val jkTestKind = mutableSetOf<String>()
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
            val kindTests = inline != null && Regex("""kind\s*=\s*"tests"""").containsMatchIn(inline.groupValues[2])
            if (table.startsWith("test-")) {
                jkTest.add(name)
                if (kindTests) jkTestKind.add(name)
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
        (fixtures - jkTestKind).sorted().forEach {
            lines.add("Gradle takes $it's testFixtures; jk.toml needs `$it = { workspace = true, kind = \"tests\" }`"
                + " under a [test-*dependencies] table")
        }
        (jkTestKind - fixtures).sorted().forEach { lines.add("jk.toml takes $it with kind = \"tests\"; build.gradle.kts does not take its testFixtures") }
        if (lines.isNotEmpty()) {
            faults.add("${rel(module)} declares different dependencies to its two builds:\n" + bullets(lines))
        }
    }
    if (faults.isNotEmpty()) {
        error("This repo builds itself with Gradle and with jk, so an edge in only one of them is"
            + " green in one build and broken in the other:\n\n" + faults.joinToString("\n\n")
            + "\n\n  jk's `kind = \"tests\"` is Gradle's `testFixtures(...)`; jk's [test-dependencies]"
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

// ---------------------------------------------------------------------------
// G39 / G40 / G42 / G45 — the filesystem is asked the cheap question first, through one owner.
//
// G39: a walk already read each entry's attributes, and `Files::isRegularFile` re-resolves the
// path from scratch (10.3 us on NTFS against 1.0 on ext4). One JRE scan stat'ed every file in a
// ~20,000-file tree before a name test rejected almost all of them.
// G40: `Files.isExecutable` is 33.4 us on Windows against 0.52 on Linux — 64x — because the JDK
// implements EXECUTE access there as a security-descriptor read plus an AccessCheck. Windows has
// no executable bit; what decides whether a file runs is its extension.
// G42: twelve hand-rolled tree copies shared the same three defects, and the identity check is a
// correctness property, not a saving — re-copying bumps mtime, and an unchanged jar re-copied
// forced a full KSP round on every build.
// G45: a ratchet, deliberately. Two hundred sites remain and each needs its own read.
// ---------------------------------------------------------------------------

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

guard("G40", "checkRunnableOwner") {
    if (!owner(pathUtilOwner).contains("public static boolean isRunnable(")) {
        error("PathUtil no longer declares isRunnable(Path), so this guard has lost the owner it points callers at.")
    }
    val offenders = mutableListOf<String>()
    mainJava.filter { rel(it) != pathUtilOwner }.forEach { f ->
        text(f).lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
            if (!line.contains("Files.isExecutable(")) return@forEachIndexed
            // Exempt by shape: already skipped on the platform where the call is 64x, because the
            // caller wants the POSIX bit itself rather than "can this host run it".
            if (line.contains("!Os.isWindows()") || line.contains("!WINDOWS")) return@forEachIndexed
            offenders.add("${rel(f)}:${i + 1}  $line")
        }
    }
    if (offenders.isNotEmpty()) {
        error("Files.isExecutable outside its owner:\n" + bullets(offenders)
            + "\n  Use PathUtil.isRunnable(path): an access check off Windows, an extension test on"
            + " it. Keep Files.isExecutable only when you want the POSIX bit itself, and then guard"
            + " it with !Os.isWindows() as ActionCache.executableBit does.")
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

guard("G45", "checkBlindWalkRatchet") {
    if (!owner(pathUtilOwner).contains("public static void forEachRegularFile(")) {
        error("PathUtil no longer declares forEachRegularFile(...), so this guard has lost the owner"
            + " it points callers at.")
    }
    val allowed = Files.readAllLines(at("walk-baseline.txt"))
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { val p = it.trim().split(" "); p[0] to p[1].toInt() }
    if (allowed.isEmpty()) {
        error("walk-baseline.txt lists no modules, so this guard would pass over anything.")
    }
    val pattern = Regex("""Files\.(walk|walkFileTree|newDirectoryStream|list)\(""")
    val faults = mutableListOf<String>()
    moduleDirs.forEach { module ->
        val here = rel(module)
        val files = filesUnder(module.resolve("src/main/java"), ".java")
        if (files.isEmpty() && here !in allowed) return@forEach
        val found = files.sumOf { pattern.findAll(text(it)).count() }
        val budget = allowed[here] ?: 0
        if (found > budget) {
            faults.add("$here has $found blind tree walks, baseline $budget (+${found - budget})")
        } else if (found < budget) {
            faults.add("$here is down to $found blind tree walks from a baseline of $budget — lower"
                + " the line in walk-baseline.txt so the ratchet tightens. A baseline that lags the"
                + " tree is the same defect as a registry that lags the code.")
        }
    }
    if (faults.isNotEmpty()) {
        error("Blind tree walks:\n" + bullets(faults)
            + "\n  Use PathUtil.forEachRegularFile(root, (file, attrs) -> …): the walk already read"
            + " each entry's attributes, and re-resolving the path to ask again is the dominant cost"
            + " of walking a large tree."
            + "\n  If a walk genuinely cannot use it (it needs directories, a depth limit, or"
            + " ordering), raise this module's line in walk-baseline.txt in the same change and say"
            + " which site and why.")
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
