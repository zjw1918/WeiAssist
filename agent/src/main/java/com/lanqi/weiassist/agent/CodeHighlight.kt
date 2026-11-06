package com.lanqi.weiassist.agent

/**
 * Syntax colouring for the code agents write: one forgiving scanner, tuned per language by a [Spec] (its keywords,
 * comments, strings). Not a parser: it colours keywords, types, strings, comments, numbers, calls, keys and the
 * like, which is what makes code readable at a glance. Unknown languages get no colours. Pure Kotlin, unit-tested
 * on the JVM; the app maps each [Kind] to a colour (ui/CodeView.kt).
 */
object CodeHighlight {
    enum class Kind { Keyword, Type, Str, Comment, Number, Function, Key, Meta, Added, Removed }

    /** [start] inclusive, [end] exclusive, in the code's characters. */
    data class Token(val start: Int, val end: Int, val kind: Kind)

    private class Spec(
        keywords: String,
        types: String = "",
        val line: List<String> = listOf("//"),
        val block: Pair<String, String>? = "/*" to "*/",
        val quotes: String = "\"'",
        /** Quotes whose strings may span lines (Go's and JS's backticks). */
        val multiline: String = "",
        val triple: Boolean = false,
        /** A capitalised name is a type (Go, Kotlin, Java, Rust…). */
        val capsTypes: Boolean = true,
        /** `@Name` is an annotation or decorator. */
        val at: Boolean = false,
        /** A `#` starts a comment only at a line's start or after a space (`${#a}`, `a#b` aren't comments). */
        val hashAfterSpace: Boolean = false,
        val shell: Boolean = false,
        /** A string followed by `:` is a key (JSON). */
        val stringKeys: Boolean = false,
        /** A bare name followed by [keySep] at a line's start is a key (YAML `a:`, TOML `a =`). */
        val keySep: Char? = null,
        /** A `#` at a line's start is a preprocessor line (C). */
        val preprocessor: Boolean = false,
        val ignoreCase: Boolean = false,
    ) {
        val keywords = words(keywords, ignoreCase)
        val types = words(types, ignoreCase)
    }

    private fun words(s: String, ignoreCase: Boolean) = s.split(' ').filter { it.isNotEmpty() }.map { if (ignoreCase) it.lowercase() else it }.toSet()

    private const val C_TYPES = "void char short int long float double signed unsigned bool size_t auto const static struct enum union typedef"

    private val GO = Spec(
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false iota",
        "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any",
        quotes = "\"'`", multiline = "`",
    )
    private val KOTLIN = Spec(
        "package import class interface object fun val var if else when for while do return break continue throw try catch finally in is as typealias " +
            "this super null true false private public protected internal override open abstract sealed data inner enum companion const lateinit " +
            "suspend inline operator infix vararg by get set init constructor where reified crossinline noinline actual expect annotation",
        quotes = "\"'", triple = true, at = true,
    )
    private val JAVA = Spec(
        "abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import " +
            "instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws " +
            "transient try volatile while var record yield null true false",
        "boolean byte char double float int long short void", at = true,
    )
    private val JS = Spec(
        "async await break case catch class const continue debugger default delete do else export extends finally for from function get if import in " +
            "instanceof let new of return set static super switch this throw try typeof var void while with yield null undefined true false as " +
            "type interface enum implements private public protected readonly declare namespace keyof satisfies",
        "string number boolean any unknown never object symbol bigint",
        quotes = "\"'`", multiline = "`", capsTypes = true, at = true,
    )
    private val PYTHON = Spec(
        "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or " +
            "pass raise return try while with yield None True False self match case",
        "int float str bool list dict set tuple bytes object type",
        line = listOf("#"), block = null, triple = true, capsTypes = true, at = true, hashAfterSpace = true,
    )
    private val SHELL = Spec(
        "if then else elif fi for while until do done case esac in function return local export readonly declare unset shift break continue " +
            "source alias set trap exit",
        line = listOf("#"), block = null, capsTypes = false, hashAfterSpace = true, shell = true,
    )
    private val JSON = Spec("true false null", line = emptyList(), block = null, quotes = "\"", capsTypes = false, stringKeys = true)
    private val YAML = Spec("true false null yes no on off", line = listOf("#"), block = null, capsTypes = false, hashAfterSpace = true, keySep = ':')
    private val TOML = Spec("true false", line = listOf("#"), block = null, capsTypes = false, hashAfterSpace = true, keySep = '=')
    private val C = Spec(
        "break case continue default do else for goto if return sizeof switch while extern register volatile inline NULL true false " +
            "class namespace template typename public private protected virtual override new delete this nullptr using try catch throw operator",
        C_TYPES + " int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t string vector",
        capsTypes = false, preprocessor = true,
    )
    private val RUST = Spec(
        "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self " +
            "Self static struct super trait true type unsafe use where while",
        "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str",
        quotes = "\"",
    )
    private val SWIFT = Spec(
        "class struct enum protocol extension func var let if else guard switch case default for while repeat return break continue import " +
            "init deinit self Self super nil true false in where as is try catch throw throws async await private public internal fileprivate open " +
            "static override mutating weak lazy some any",
        quotes = "\"", triple = true, at = true,
    )
    private val DART = Spec(
        "abstract as async await break case catch class const continue default do else enum extends false final finally for if implements import " +
            "in is late library new null part required return static super switch this throw true try var void while with yield",
        quotes = "\"'", triple = true, at = true,
    )
    private val CSHARP = Spec(
        "abstract as base break case catch class const continue default delegate do else enum event explicit extern false finally fixed for " +
            "foreach goto if implicit in interface internal is lock namespace new null operator out override params private protected public " +
            "readonly ref return sealed sizeof static struct switch this throw true try typeof unsafe using virtual void volatile while var async await record",
        "bool byte char decimal double float int long object sbyte short string uint ulong ushort",
    )
    private val RUBY = Spec(
        "alias and begin break case class def defined? do else elsif end ensure false for if in module next nil not or redo rescue retry return " +
            "self super then true undef unless until when while yield require attr_accessor",
        line = listOf("#"), block = null, hashAfterSpace = true,
    )
    private val SQL = Spec(
        "select from where and or not insert into values update set delete create table index view drop alter add primary key foreign references " +
            "join left right inner outer on group by order having limit offset as distinct union all null is in like between exists case when then " +
            "else end default unique if begin commit rollback",
        "int integer bigint smallint text varchar char boolean real double float date timestamp blob serial",
        line = listOf("--"), quotes = "'\"", capsTypes = false, ignoreCase = true,
    )
    private val CSS = Spec("important", line = emptyList(), capsTypes = false, keySep = ':')
    private val MARKUP = Spec("", line = emptyList(), block = "<!--" to "-->", capsTypes = false)

    /** The spec for a fence's language tag or a file's extension; null when it is one we don't colour. */
    private fun spec(lang: String): Spec? = when (lang.lowercase()) {
        "go", "golang" -> GO
        "kotlin", "kt", "kts", "gradle" -> KOTLIN
        "java", "groovy", "scala" -> JAVA
        "js", "javascript", "jsx", "mjs", "cjs", "ts", "typescript", "tsx", "mts", "cts", "vue", "svelte" -> JS
        "python", "py", "python3", "pyi" -> PYTHON
        "sh", "bash", "shell", "zsh", "console", "shellsession", "terminal", "fish", "ksh", "dockerfile", "makefile", "make" -> SHELL
        "json", "jsonc", "json5", "jsonl" -> JSON
        "yaml", "yml" -> YAML
        "toml", "ini", "cfg", "conf", "properties", "env" -> TOML
        "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "hh", "objc", "m", "mm", "cuda", "cu", "proto", "zig" -> C
        "rust", "rs" -> RUST
        "swift" -> SWIFT
        "dart" -> DART
        "cs", "csharp", "c#" -> CSHARP
        "ruby", "rb" -> RUBY
        "sql", "sqlite", "mysql", "postgres", "postgresql", "psql" -> SQL
        "css", "scss", "less" -> CSS
        "html", "htm", "xml", "svg", "xhtml", "plist" -> MARKUP
        else -> null
    }

    /** The language of a file, by its name: "main.go" → "go", "Dockerfile" → "dockerfile"; "" when unknown. */
    fun langOfPath(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        if (name.equals("Dockerfile", true) || name.equals("Makefile", true)) return name.lowercase()
        if (name.startsWith(".env") || name == ".bashrc" || name == ".zshrc" || name == ".profile") return "sh"
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "md" || ext == "markdown" -> "markdown"
            ext == "diff" || ext == "patch" -> "diff"
            spec(ext) != null -> ext
            else -> ""
        }
    }

    /** Whether [lang] gets colours. */
    fun supports(lang: String) = isDiff(lang) || spec(lang) != null

    private fun isDiff(lang: String) = lang.equals("diff", true) || lang.equals("patch", true)

    fun tokens(code: String, lang: String): List<Token> {
        if (isDiff(lang)) return diff(code)
        val spec = spec(lang) ?: return emptyList()
        return Scanner(code, spec).run()
    }

    private fun diff(code: String): List<Token> {
        val out = mutableListOf<Token>()
        var start = 0
        for (line in code.split('\n')) {
            val kind = when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") || line.startsWith("diff ") || line.startsWith("index ") -> Kind.Meta
                line.startsWith("+") -> Kind.Added
                line.startsWith("-") -> Kind.Removed
                else -> null
            }
            if (kind != null && line.isNotEmpty()) out += Token(start, start + line.length, kind)
            start += line.length + 1
        }
        return out
    }

    private class Scanner(val s: String, val spec: Spec) {
        val out = mutableListOf<Token>()
        var i = 0
        /** The shell: the next word is a command (at a line's start, after a pipe, `;`, `&&`, `$(`…). */
        var command = true

        fun add(start: Int, end: Int, kind: Kind) { if (end > start) out += Token(start, end, kind) }

        fun lineStart(at: Int): Boolean {
            var j = at - 1
            while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) j--
            return j < 0 || s[j] == '\n'
        }

        fun run(): List<Token> {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\n' -> { command = true; i++ }
                    c == ' ' || c == '\t' -> i++
                    // A console prompt: "$ go run ." (the prompt quiet, the command after it a command).
                    spec.shell && c == '$' && lineStart(i) && i + 1 < s.length && s[i + 1] == ' ' -> { add(i, i + 1, Kind.Meta); i++; command = true }
                    spec.preprocessor && c == '#' && lineStart(i) -> { val e = endOfLine(i); add(i, e, Kind.Meta); i = e }
                    lineComment() -> { val e = endOfLine(i); add(i, e, Kind.Comment); i = e }
                    spec.block != null && s.startsWith(spec.block.first, i) -> {
                        val e = s.indexOf(spec.block.second, i + spec.block.first.length).let { if (it < 0) s.length else it + spec.block.second.length }
                        add(i, e, Kind.Comment); i = e
                    }
                    c in spec.quotes && !(c == '\'' && apostrophe()) -> string(c)
                    c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit() && !prevIsWord()) -> number()
                    spec.at && c == '@' && i + 1 < s.length && s[i + 1].isLetter() -> { val e = wordEnd(i + 1); add(i, e, Kind.Meta); i = e }
                    spec.shell && c == '$' -> variable()
                    spec.shell && c == '-' && !prevIsWord() && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '-') -> {
                        var e = i + 1
                        while (e < s.length && (s[e].isLetterOrDigit() || s[e] == '-' || s[e] == '_')) e++
                        add(i, e, Kind.Meta); i = e; command = false
                    }
                    c.isLetter() || c == '_' -> word()
                    else -> {
                        if (spec.shell) command = c == '|' || c == ';' || c == '&' || c == '(' || c == '`' || (command && c == '{')
                        i++
                    }
                }
            }
            return out
        }

        fun endOfLine(from: Int) = s.indexOf('\n', from).let { if (it < 0) s.length else it }

        fun prevIsWord() = i > 0 && (s[i - 1].isLetterOrDigit() || s[i - 1] == '_')

        fun lineComment(): Boolean = spec.line.any { mark ->
            s.startsWith(mark, i) && (!spec.hashAfterSpace || mark != "#" || i == 0 || s[i - 1].isWhitespace())
        }

        /** A `'` inside a word (don't, Rust's 'a) in languages where it can't open a string there. */
        fun apostrophe() = (spec.shell || spec === SQL || spec.keySep != null || spec === MARKUP || spec === CSS) && prevIsWord()

        fun string(q: Char) {
            val start = i
            if (spec.triple && s.startsWith("$q$q$q", i)) {
                val e = s.indexOf("$q$q$q", i + 3).let { if (it < 0) s.length else it + 3 }
                add(start, e, Kind.Str); i = e; command = false
                return
            }
            i++
            val raw = q == '`' && spec === GO
            val spans = q in spec.multiline || spec.shell
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && !raw && !(spec.shell && q == '\'')) { i += 2; continue }
                if (c == q) { i++; break }
                if (c == '\n' && !spans) break
                i++
            }
            i = i.coerceAtMost(s.length)
            val keyed = spec.stringKeys && s.indexOfFirst(i) { it != ' ' && it != '\t' } == ':'
            add(start, i, if (keyed) Kind.Key else Kind.Str)
            command = false
        }

        fun number() {
            val start = i
            if (s.startsWith("0x", i) || s.startsWith("0X", i) || s.startsWith("0b", i) || s.startsWith("0o", i)) {
                i += 2
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            } else {
                while (i < s.length && (s[i].isDigit() || s[i] == '_')) i++
                if (i + 1 < s.length && s[i] == '.' && s[i + 1].isDigit()) { i++; while (i < s.length && (s[i].isDigit() || s[i] == '_')) i++ }
                if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                while (i < s.length && s[i].isLetter()) i++ // 10L, 1.5f, 10ms
            }
            // Part of a name or path (v1, a3b35ab4, 8080/tcp is still a number): only a number on its own.
            if (start > 0 && (s[start - 1].isLetter() || s[start - 1] == '_')) return
            add(start, i, Kind.Number)
            command = false
        }

        fun variable() {
            val start = i
            i++
            if (i < s.length && s[i] == '{') {
                val e = s.indexOf('}', i).let { if (it < 0) s.length else it + 1 }
                i = e
            } else if (i < s.length && s[i] == '(') {
                // $(…): a command inside; its `$(` is quiet.
                add(start, i + 1, Kind.Meta); i++; command = true; return
            } else while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || (i == start + 1 && s[i] in "?@#*!$-"))) i++
            add(start, i, Kind.Type)
            command = false
        }

        fun wordEnd(from: Int): Int {
            var e = from
            while (e < s.length && (s[e].isLetterOrDigit() || s[e] == '_' || (spec.shell && (s[e] == '-' || s[e] == '.')))) e++
            return e
        }

        fun word() {
            val start = i
            i = wordEnd(i)
            if (spec === RUBY && i < s.length && (s[i] == '?' || s[i] == '!')) i++
            val w = s.substring(start, i)
            val key = if (spec.ignoreCase) w.lowercase() else w
            val next = s.indexOfFirst(i) { it != ' ' && it != '\t' }
            when {
                spec === MARKUP -> {
                    val before = s.lastIndexOf('<', start).takeIf { it >= 0 && s.substring(it + 1, start).trim('/', ' ').isEmpty() }
                    if (before != null) add(start, i, Kind.Keyword) else if (next == '=') add(start, i, Kind.Key)
                }
                spec.keySep != null && (lineStart(start) || listItem(start)) && next == spec.keySep -> {
                    // A YAML key may hold dots and dashes: "server.port:", "api-version:".
                    var e = i
                    while (e < s.length && s[e] != spec.keySep && s[e] != '\n' && s[e] != '#') e++
                    add(start, e.coerceAtMost(s.length).let { k -> var t = k; while (t > start && s[t - 1] == ' ') t--; t }, Kind.Key)
                    i = e
                }
                key in spec.keywords -> {
                    add(start, i, Kind.Keyword)
                    // The shell: after `then`, `do`, `if`… comes a command.
                    if (spec.shell) command = true
                }
                // An assignment ("PORT=9000 go run ."): the name, its value, and a command may still follow.
                spec.shell && i < s.length && s[i] == '=' -> {
                    add(start, i, Kind.Type)
                    val was = command
                    i++
                    if (i < s.length && (s[i] == '"' || s[i] == '\'')) string(s[i])
                    else while (i < s.length && !s[i].isWhitespace() && s[i] != ';') i++
                    command = was
                }
                spec.shell -> {
                    if (command) add(start, i, Kind.Function)
                    command = false
                }
                key in spec.types -> add(start, i, Kind.Type)
                next == '(' -> add(start, i, Kind.Function)
                spec.capsTypes && w[0].isUpperCase() && w.any { it.isLowerCase() } -> add(start, i, Kind.Type)
            }
        }

        /** YAML: a key right after a list item's dash ("- name: x"). */
        fun listItem(at: Int): Boolean {
            var j = at - 1
            while (j >= 0 && s[j] == ' ') j--
            return j >= 0 && s[j] == '-' && lineStart(j)
        }
    }

    private fun String.indexOfFirst(from: Int, p: (Char) -> Boolean): Char? {
        for (j in from until length) if (p(this[j])) return this[j]
        return null
    }
}
