package dev.akamitori.glsl

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object GlslColors {
    @JvmField val LINE_COMMENT = createTextAttributesKey("GLSL_LINE_COMMENT", D.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = createTextAttributesKey("GLSL_BLOCK_COMMENT", D.BLOCK_COMMENT)
    @JvmField val PP_DIRECTIVE = createTextAttributesKey("GLSL_PP_DIRECTIVE", D.METADATA)
    @JvmField val STRING = createTextAttributesKey("GLSL_STRING", D.STRING)
    @JvmField val NUMBER = createTextAttributesKey("GLSL_NUMBER", D.NUMBER)
    @JvmField val KEYWORD = createTextAttributesKey("GLSL_KEYWORD", D.KEYWORD)
    @JvmField val TYPE = createTextAttributesKey("GLSL_TYPE", D.KEYWORD)
    @JvmField val BUILTIN_VAR = createTextAttributesKey("GLSL_BUILTIN_VAR", D.PREDEFINED_SYMBOL)
    @JvmField val BUILTIN_FUNC = createTextAttributesKey("GLSL_BUILTIN_FUNC", D.STATIC_METHOD)
    @JvmField val IDENT = createTextAttributesKey("GLSL_IDENT", D.IDENTIFIER)
    @JvmField val OPERATOR = createTextAttributesKey("GLSL_OPERATOR", D.OPERATION_SIGN)
    @JvmField val PARENS = createTextAttributesKey("GLSL_PARENS", D.PARENTHESES)
    @JvmField val BRACES = createTextAttributesKey("GLSL_BRACES", D.BRACES)
    @JvmField val BRACKETS = createTextAttributesKey("GLSL_BRACKETS", D.BRACKETS)
    @JvmField val SEMICOLON = createTextAttributesKey("GLSL_SEMICOLON", D.SEMICOLON)
    @JvmField val COMMA = createTextAttributesKey("GLSL_COMMA", D.COMMA)
    @JvmField val DOT = createTextAttributesKey("GLSL_DOT", D.DOT)
    @JvmField val BAD_CHARACTER = createTextAttributesKey("GLSL_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // Semantic colours from GlslSemanticAnnotator.
    @JvmField val FUNCTION_DECLARATION = createTextAttributesKey("GLSL_FUNCTION_DECLARATION", D.FUNCTION_DECLARATION)
    // Opinionated: calls share the declaration colour so user functions stand out from plain identifiers
    // (most schemes leave FUNCTION_CALL uncoloured).
    @JvmField val FUNCTION_CALL = createTextAttributesKey("GLSL_FUNCTION_CALL", D.FUNCTION_DECLARATION)
    @JvmField val PARAMETER = createTextAttributesKey("GLSL_PARAMETER", D.PARAMETER)
    @JvmField val GLOBAL_VAR = createTextAttributesKey("GLSL_GLOBAL_VAR", D.GLOBAL_VARIABLE)
    @JvmField val STRUCT_NAME = createTextAttributesKey("GLSL_STRUCT_NAME", D.CLASS_NAME)
    @JvmField val FIELD = createTextAttributesKey("GLSL_FIELD", D.INSTANCE_FIELD)
    @JvmField val SWIZZLE = createTextAttributesKey("GLSL_SWIZZLE", D.INSTANCE_FIELD)
    @JvmField val MACRO = createTextAttributesKey("GLSL_MACRO", D.CONSTANT)
    @JvmField val LAYOUT_QUALIFIER = createTextAttributesKey("GLSL_LAYOUT_QUALIFIER", D.METADATA)
}

class GlslSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = GlslLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(TOKEN_KEYS[tokenType])

    companion object {
        private val TOKEN_KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            GlslTokens.LINE_COMMENT to GlslColors.LINE_COMMENT,
            GlslTokens.BLOCK_COMMENT to GlslColors.BLOCK_COMMENT,
            GlslTokens.PP_DIRECTIVE to GlslColors.PP_DIRECTIVE,
            GlslTokens.STRING to GlslColors.STRING,
            GlslTokens.NUMBER to GlslColors.NUMBER,
            GlslTokens.KEYWORD to GlslColors.KEYWORD,
            GlslTokens.TYPE to GlslColors.TYPE,
            GlslTokens.BUILTIN_VAR to GlslColors.BUILTIN_VAR,
            GlslTokens.BUILTIN_FUNC to GlslColors.BUILTIN_FUNC,
            GlslTokens.IDENT to GlslColors.IDENT,
            GlslTokens.OPERATOR to GlslColors.OPERATOR,
            GlslTokens.LPAREN to GlslColors.PARENS,
            GlslTokens.RPAREN to GlslColors.PARENS,
            GlslTokens.LBRACE to GlslColors.BRACES,
            GlslTokens.RBRACE to GlslColors.BRACES,
            GlslTokens.LBRACKET to GlslColors.BRACKETS,
            GlslTokens.RBRACKET to GlslColors.BRACKETS,
            GlslTokens.SEMICOLON to GlslColors.SEMICOLON,
            GlslTokens.COMMA to GlslColors.COMMA,
            GlslTokens.DOT to GlslColors.DOT,
            TokenType.BAD_CHARACTER to GlslColors.BAD_CHARACTER,
        )
    }
}

class GlslSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        GlslSyntaxHighlighter()
}

class GlslColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "GLSL"
    override fun getIcon() = GlslIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = GlslSyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "fn" to GlslColors.FUNCTION_DECLARATION,
        "call" to GlslColors.FUNCTION_CALL,
        "param" to GlslColors.PARAMETER,
        "global" to GlslColors.GLOBAL_VAR,
        "struct" to GlslColors.STRUCT_NAME,
        "field" to GlslColors.FIELD,
        "swz" to GlslColors.SWIZZLE,
        "macro" to GlslColors.MACRO,
        "layout" to GlslColors.LAYOUT_QUALIFIER,
    )
    override fun getAttributeDescriptors() = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDemoText() = """
        #version 460 core
        #include <common/lighting.glsl>
        #define <macro>MAX_LIGHTS</macro> 8u

        /* Simple Blinn-Phong fragment shader */
        layout(<layout>location</layout> = 0) in vec3 <global>v_normal</global>;
        layout(<layout>location</layout> = 1) in vec2 <global>v_uv</global>;
        layout(<layout>location</layout> = 0) out vec4 <global>frag_color</global>;

        layout(<layout>binding</layout> = 0) uniform sampler2D <global>u_albedo</global>;
        uniform vec3 <global>u_light_dir</global>[<macro>MAX_LIGHTS</macro>];

        struct <struct>Material</struct> {
            float shininess;
            dvec2 scale;
        };

        float <fn>lambert</fn>(vec3 <param>n</param>, vec3 <param>l</param>, <struct>Material</struct> <param>m</param>) {
            return pow(max(dot(<param>n</param>, <param>l</param>), 0.0), <param>m</param>.<field>shininess</field>);
        }

        void <fn>main</fn>() {
            vec3 n = normalize(<global>v_normal</global>);
            <struct>Material</struct> mat = <struct>Material</struct>(16.0, dvec2(1.0));
            float lit = 0.0;
            for (uint i = 0u; i < <macro>MAX_LIGHTS</macro>; ++i) {
                lit += <call>lambert</call>(n, <global>u_light_dir</global>[i], mat) * 1.5e-1f;
            }
            if (gl_FrontFacing == false) discard;
            vec4 albedo = texture(<global>u_albedo</global>, <global>v_uv</global>);
            <global>frag_color</global> = vec4(albedo.<swz>rgb</swz> * lit, albedo.<swz>a</swz>); // final color
            @
        }
    """.trimIndent()

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Comments//Line comment", GlslColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", GlslColors.BLOCK_COMMENT),
            AttributesDescriptor("Preprocessor directive", GlslColors.PP_DIRECTIVE),
            AttributesDescriptor("String / include path", GlslColors.STRING),
            AttributesDescriptor("Number", GlslColors.NUMBER),
            AttributesDescriptor("Keyword", GlslColors.KEYWORD),
            AttributesDescriptor("Type", GlslColors.TYPE),
            AttributesDescriptor("Built-in variable", GlslColors.BUILTIN_VAR),
            AttributesDescriptor("Built-in function", GlslColors.BUILTIN_FUNC),
            AttributesDescriptor("Identifier", GlslColors.IDENT),
            AttributesDescriptor("Operator", GlslColors.OPERATOR),
            AttributesDescriptor("Braces and Operators//Parentheses", GlslColors.PARENS),
            AttributesDescriptor("Braces and Operators//Braces", GlslColors.BRACES),
            AttributesDescriptor("Braces and Operators//Brackets", GlslColors.BRACKETS),
            AttributesDescriptor("Braces and Operators//Semicolon", GlslColors.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Comma", GlslColors.COMMA),
            AttributesDescriptor("Braces and Operators//Dot", GlslColors.DOT),
            AttributesDescriptor("Bad character", GlslColors.BAD_CHARACTER),
            AttributesDescriptor("Functions//Declaration", GlslColors.FUNCTION_DECLARATION),
            AttributesDescriptor("Functions//Call", GlslColors.FUNCTION_CALL),
            AttributesDescriptor("Functions//Parameter", GlslColors.PARAMETER),
            AttributesDescriptor("Variables//Global (uniform, in, out, buffer)", GlslColors.GLOBAL_VAR),
            AttributesDescriptor("Variables//Struct field", GlslColors.FIELD),
            AttributesDescriptor("Variables//Swizzle", GlslColors.SWIZZLE),
            AttributesDescriptor("Struct / block name", GlslColors.STRUCT_NAME),
            AttributesDescriptor("Macro", GlslColors.MACRO),
            AttributesDescriptor("Layout qualifier", GlslColors.LAYOUT_QUALIFIER),
        )
    }
}
