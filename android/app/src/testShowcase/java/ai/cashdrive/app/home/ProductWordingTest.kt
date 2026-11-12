package ai.cashdrive.app.home

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User decision 2026-11-09 (design §2-3, plan C2): the product reads as a live service, so no string the
 * app can show says "mock", "demonstration", "example" or "partnership pending". Scans every string
 * literal of the Kotlin sources that ship in the showcase build (src/main, src/home) and every attribute
 * value of their XML and the showcase manifest. Comments are not on screen and are skipped. The demo
 * flavor's debug tools use neutral labels too, so the scan has no exceptions.
 */
class ProductWordingTest {
    private val banned = listOf("모의", "시연", "예시", "제휴 예정", "실차 미검증")

    @Test fun noMockOrDemoWordingInShippedStrings() {
        val roots = listOf("src/main", "src/home", "src/showcase").map(::File)
        assertTrue(roots.all { it.isDirectory }, "run from the app module, not ${File(".").absolutePath}")
        val hits = mutableListOf<String>()
        var scanned = 0
        roots.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val texts = when (file.extension) {
                    "kt", "java" -> stringLiterals(file.readText())
                    "xml" -> attributeValues(file.readText())
                    else -> return@forEach
                }
                scanned++
                texts.forEach { text -> banned.filter { text.contains(it) }.forEach { hits += "${file.path}: '$it' in \"$text\"" } }
            }
        }
        assertTrue(scanned > 20, "only $scanned files scanned")
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }

    @Test fun theScannerReadsLiteralsAndTemplatesButSkipsComments() {
        val sample = listOf(
            "// \"주석\" in a line comment",
            "/* \"블록\" in a block comment */",
            "val a = \"보이는 글자\" // trailing \"꼬리\"",
            "val b = \"앞 \${if (on) \"켜짐\" else \"꺼짐\"} 뒤\"",
            "val c = '\"'",
            "val d = \"\"\"원시 \"문자열\" 끝\"\"\"",
            "val e = \"https://example.com\"",
        ).joinToString("\n")
        assertEquals(listOf("보이는 글자", "앞 ", "켜짐", "꺼짐", " 뒤", "원시 \"문자열\" 끝", "https://example.com"), stringLiterals(sample))
        assertEquals(listOf("킬로모아", "true"), attributeValues("<!-- android:label=\"주석\" --><activity android:label=\"킬로모아\" android:exported=\"true\" />"))
    }

    private companion object {
        /** Every string literal (template parts separately, nested templates included) outside comments and char literals. */
        fun stringLiterals(source: String): List<String> = LiteralScanner(source).run()

        /** Every attribute value outside XML comments. */
        fun attributeValues(xml: String): List<String> =
            Regex("=\"([^\"]*)\"").findAll(xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")).map { it.groupValues[1] }.toList()
    }

    /** A small Kotlin lexer: comments, char literals, plain and raw strings with `${...}` templates. */
    private class LiteralScanner(private val s: String) {
        private var i = 0
        private val out = mutableListOf<String>()

        fun run(): List<String> {
            code(untilBrace = false)
            return out
        }

        private fun code(untilBrace: Boolean) {
            var depth = 0
            while (i < s.length) {
                when {
                    s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    s.startsWith("\"\"\"", i) -> string(raw = true)
                    s[i] == '"' -> string(raw = false)
                    s[i] == '\'' -> char()
                    s[i] == '{' -> { depth++; i++ }
                    s[i] == '}' -> {
                        i++
                        if (untilBrace && depth == 0) return
                        depth--
                    }
                    else -> i++
                }
            }
        }

        private fun string(raw: Boolean) {
            val quote = if (raw) "\"\"\"" else "\""
            i += quote.length
            val text = StringBuilder()
            while (i < s.length) {
                when {
                    s.startsWith(quote, i) -> { i += quote.length; break }
                    !raw && s[i] == '\\' && i + 1 < s.length -> { text.append(s, i, i + 2); i += 2 }
                    s.startsWith("\${", i) -> {
                        out += text.toString(); text.clear()
                        i += 2
                        code(untilBrace = true)
                    }
                    else -> { text.append(s[i]); i++ }
                }
            }
            out += text.toString()
        }

        private fun char() {
            i++
            while (i < s.length && s[i] != '\'') i += if (s[i] == '\\') 2 else 1
            i++
        }
    }
}
