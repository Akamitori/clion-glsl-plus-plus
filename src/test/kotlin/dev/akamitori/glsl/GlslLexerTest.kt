package dev.akamitori.glsl

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Test

class GlslLexerTest {
    /** (type, text) for every token, whitespace included. */
    private fun lex_all(text: String): List<Pair<IElementType, String>> {
        val out = ArrayList<Pair<IElementType, String>>()
        val lexer = GlslLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            out.add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return out
    }

    private fun lex(text: String) = lex_all(text).filter { it.first != TokenType.WHITE_SPACE }

    private fun type_of(text: String): IElementType = lex(text).single().first

    @Test fun `keywords types builtins and identifiers`() {
        assertEquals(GlslTokens.KEYWORD, type_of("uniform"))
        assertEquals(GlslTokens.KEYWORD, type_of("layout"))
        assertEquals(GlslTokens.TYPE, type_of("vec3"))
        assertEquals(GlslTokens.TYPE, type_of("dmat4x3"))
        assertEquals(GlslTokens.TYPE, type_of("isampler2DArray"))  // opaque-type regex
        assertEquals(GlslTokens.TYPE, type_of("uimage3D"))
        assertEquals(GlslTokens.BUILTIN_VAR, type_of("gl_FragCoord"))
        assertEquals(GlslTokens.BUILTIN_FUNC, type_of("normalize"))
        assertEquals(GlslTokens.BUILTIN_FUNC, type_of("subgroupAdd"))
        assertEquals(GlslTokens.IDENT, type_of("lambert"))
        assertEquals(GlslTokens.IDENT, type_of("sampler_count"))  // not an opaque type
    }

    @Test fun `numbers with suffixes`() {
        for (n in listOf("1", "1.0", ".5", "1.", "1e-3", "1.5e+2f", "0xFFu", "2.0lf", "3U", "0x1aF")) {
            assertEquals(n, listOf(GlslTokens.NUMBER to n), lex(n))
        }
    }

    @Test fun `preprocessor and include path`() {
        assertEquals(
            listOf(GlslTokens.PP_DIRECTIVE to "#version", GlslTokens.NUMBER to "460", GlslTokens.IDENT to "core"),
            lex("#version 460 core"),
        )
        assertEquals(listOf(GlslTokens.PP_DIRECTIVE to "#include", GlslTokens.STRING to "<common/a.glsl>"), lex("#include <common/a.glsl>"))
        assertEquals(listOf(GlslTokens.PP_DIRECTIVE to "#include", GlslTokens.STRING to "\"b.glsl\""), lex("#include \"b.glsl\""))
        assertEquals(GlslTokens.PP_DIRECTIVE, lex("  #  define X 1").first().first)
        // `<` outside #include is just an operator.
        assertEquals(GlslTokens.OPERATOR, lex("a < b")[1].first)
        // Token pasting is an operator, not a directive.
        assertEquals(GlslTokens.OPERATOR to "##", lex("a ## b")[1])
    }

    @Test fun comments() {
        assertEquals(listOf(GlslTokens.LINE_COMMENT to "// hi"), lex("// hi"))
        assertEquals(listOf(GlslTokens.BLOCK_COMMENT to "/* a\n b */"), lex("/* a\n b */"))
        assertEquals(listOf(GlslTokens.BLOCK_COMMENT to "/* open"), lex("/* open"))  // unterminated: to EOF
    }

    @Test fun punctuation() {
        assertEquals(
            listOf(GlslTokens.LPAREN, GlslTokens.RPAREN, GlslTokens.LBRACE, GlslTokens.RBRACE,
                GlslTokens.LBRACKET, GlslTokens.RBRACKET, GlslTokens.SEMICOLON, GlslTokens.COMMA, GlslTokens.DOT),
            lex("(){}[];,.").map { it.first },
        )
        assertEquals(TokenType.BAD_CHARACTER, type_of("@"))
    }

    @Test fun `tokens cover the whole text`() {
        val text = "#version 460\n/* c */ void main() { gl_FragColor = vec4(1.0); } // end\n@"
        assertEquals(text, lex_all(text).joinToString("") { it.second })
    }

    /** The editor re-lexes from any token boundary with state 0, so that must give the same tokens. */
    @Test fun `restartable at every token boundary`() {
        val text = "#include <a.glsl>\nfloat f(vec3 n) { return dot(n, n) * 1.5e-1f; } /* x */ // y\n"
        val full = lex_all(text)
        var offset = 0
        for (i in full.indices) {
            val lexer = GlslLexer()
            lexer.start(text, offset, text.length, 0)
            val rest = ArrayList<Pair<IElementType, String>>()
            while (lexer.tokenType != null) {
                rest.add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
            assertEquals("restart at $offset", full.subList(i, full.size), rest)
            offset += full[i].second.length
        }
    }
}
