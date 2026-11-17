package dev.akamitori.glsl

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class GlslTokenType(debugName: String) : IElementType(debugName, GlslLanguage)

object GlslTokens {
    @JvmField val LINE_COMMENT = GlslTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = GlslTokenType("BLOCK_COMMENT")
    @JvmField val PP_DIRECTIVE = GlslTokenType("PP_DIRECTIVE")
    @JvmField val STRING = GlslTokenType("STRING")
    @JvmField val NUMBER = GlslTokenType("NUMBER")
    @JvmField val KEYWORD = GlslTokenType("KEYWORD")
    @JvmField val TYPE = GlslTokenType("TYPE")
    @JvmField val BUILTIN_VAR = GlslTokenType("BUILTIN_VAR")
    @JvmField val BUILTIN_FUNC = GlslTokenType("BUILTIN_FUNC")
    @JvmField val IDENT = GlslTokenType("IDENT")
    @JvmField val OPERATOR = GlslTokenType("OPERATOR")
    @JvmField val LPAREN = GlslTokenType("(")
    @JvmField val RPAREN = GlslTokenType(")")
    @JvmField val LBRACE = GlslTokenType("{")
    @JvmField val RBRACE = GlslTokenType("}")
    @JvmField val LBRACKET = GlslTokenType("[")
    @JvmField val RBRACKET = GlslTokenType("]")
    @JvmField val SEMICOLON = GlslTokenType(";")
    @JvmField val COMMA = GlslTokenType(",")
    @JvmField val DOT = GlslTokenType(".")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
}

/**
 * Hand-written, stateless GLSL lexer used only for instant highlighting,
 * brace matching and commenting. All real parsing and semantics come from glsl_analyzer.
 * Every token boundary is a valid restart point (state is always 0), so incremental
 * re-lexing in the editor is trivially correct.
 */
class GlslLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var tok_start = 0
    private var tok_end = 0
    private var tok_type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer
        end = endOffset
        tok_start = startOffset
        tok_end = startOffset
        advance()
    }

    override fun getState() = 0
    override fun getTokenType() = tok_type
    override fun getTokenStart() = tok_start
    override fun getTokenEnd() = tok_end
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        tok_start = tok_end
        if (tok_start >= end) {
            tok_type = null
            return
        }
        var i = tok_start
        val c = buf[i]
        val n = peek(i + 1)

        tok_type = when {
            c.isWhitespace() -> {
                while (i < end && buf[i].isWhitespace()) i++
                TokenType.WHITE_SPACE
            }
            c == '/' && n == '/' -> {
                while (i < end && buf[i] != '\n') i++
                GlslTokens.LINE_COMMENT
            }
            c == '/' && n == '*' -> {
                i += 2
                while (i < end && !(buf[i] == '*' && peek(i + 1) == '/')) i++
                i = minOf(i + 2, end)
                GlslTokens.BLOCK_COMMENT
            }
            c == '#' && n == '#' -> {
                i += 2
                GlslTokens.OPERATOR
            }
            c == '#' && is_line_start(i) -> {
                i++
                while (i < end && (buf[i] == ' ' || buf[i] == '\t')) i++
                while (i < end && is_ident_part(buf[i])) i++
                GlslTokens.PP_DIRECTIVE
            }
            c == '"' -> {
                i++
                while (i < end && buf[i] != '"' && buf[i] != '\n') i++
                if (i < end && buf[i] == '"') i++
                GlslTokens.STRING
            }
            c == '<' && line_directive(i) == "include" -> {
                i++
                while (i < end && buf[i] != '>' && buf[i] != '\n') i++
                if (i < end && buf[i] == '>') i++
                GlslTokens.STRING
            }
            c.isDigit() || (c == '.' && n.isDigit()) -> {
                i = scan_number(i)
                GlslTokens.NUMBER
            }
            is_ident_start(c) -> {
                while (i < end && is_ident_part(buf[i])) i++
                classify(buf.subSequence(tok_start, i).toString())
            }
            else -> {
                i++
                when (c) {
                    '(' -> GlslTokens.LPAREN
                    ')' -> GlslTokens.RPAREN
                    '{' -> GlslTokens.LBRACE
                    '}' -> GlslTokens.RBRACE
                    '[' -> GlslTokens.LBRACKET
                    ']' -> GlslTokens.RBRACKET
                    ';' -> GlslTokens.SEMICOLON
                    ',' -> GlslTokens.COMMA
                    '.' -> GlslTokens.DOT
                    '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?', ':', '\\' -> GlslTokens.OPERATOR
                    else -> TokenType.BAD_CHARACTER
                }
            }
        }
        tok_end = i
    }

    private fun peek(i: Int): Char = if (i < end) buf[i] else '\u0000'

    private fun is_ident_start(c: Char) = c == '_' || c.isLetter()
    private fun is_ident_part(c: Char) = c == '_' || c.isLetterOrDigit()

    /** True if only spaces/tabs precede offset i on its line. */
    private fun is_line_start(i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && (buf[j] == ' ' || buf[j] == '\t')) j--
        return j < 0 || buf[j] == '\n'
    }

    /** Name of the preprocessor directive on the line containing offset i, or null. */
    private fun line_directive(i: Int): String? {
        var j = i - 1
        while (j >= 0 && buf[j] != '\n') j--
        j++
        while (j < i && (buf[j] == ' ' || buf[j] == '\t')) j++
        if (j >= i || buf[j] != '#') return null
        j++
        while (j < i && (buf[j] == ' ' || buf[j] == '\t')) j++
        val s = j
        while (j < i && is_ident_part(buf[j])) j++
        return buf.subSequence(s, j).toString()
    }

    private fun scan_number(start: Int): Int {
        var i = start
        if (buf[i] == '0' && (peek(i + 1) == 'x' || peek(i + 1) == 'X')) {
            i += 2
            while (i < end && (buf[i].isDigit() || buf[i].lowercaseChar() in 'a'..'f')) i++
        } else {
            while (i < end && buf[i].isDigit()) i++
            if (i < end && buf[i] == '.') {
                i++
                while (i < end && buf[i].isDigit()) i++
            }
            if (i < end && (buf[i] == 'e' || buf[i] == 'E')) {
                var j = i + 1
                if (j < end && (buf[j] == '+' || buf[j] == '-')) j++
                if (j < end && buf[j].isDigit()) {
                    i = j
                    while (i < end && buf[i].isDigit()) i++
                }
            }
        }
        // suffixes: u U f F lf LF
        if (i + 1 < end && (buf[i] == 'l' || buf[i] == 'L') && (buf[i + 1] == 'f' || buf[i + 1] == 'F')) {
            i += 2
        } else if (i < end && buf[i] in "uUfF") {
            i++
        }
        return i
    }

    private fun classify(word: String): IElementType = when {
        word in KEYWORDS -> GlslTokens.KEYWORD
        word in TYPES || OPAQUE_TYPE.matches(word) -> GlslTokens.TYPE
        word.startsWith("gl_") -> GlslTokens.BUILTIN_VAR
        word in BUILTIN_FUNCS -> GlslTokens.BUILTIN_FUNC
        else -> GlslTokens.IDENT
    }

    companion object {
        private val KEYWORDS = setOf(
            "const", "uniform", "buffer", "shared", "attribute", "varying",
            "coherent", "volatile", "restrict", "readonly", "writeonly",
            "layout", "centroid", "flat", "smooth", "noperspective", "patch", "sample",
            "invariant", "precise", "in", "out", "inout",
            "break", "continue", "do", "for", "while", "switch", "case", "default",
            "if", "else", "subroutine", "discard", "return", "struct",
            "true", "false", "lowp", "mediump", "highp", "precision",
            "demote", "terminateInvocation", "rayPayloadEXT", "rayPayloadInEXT",
            "hitAttributeEXT", "callableDataEXT", "callableDataInEXT", "shaderRecordEXT",
            "taskPayloadSharedEXT", "perprimitiveEXT",
        )

        private val TYPES: Set<String> = buildSet {
            addAll(listOf("void", "bool", "int", "uint", "float", "double", "atomic_uint",
                "accelerationStructureEXT", "rayQueryEXT"))
            for (n in 2..4) {
                for (p in listOf("", "b", "i", "u", "d")) add("${p}vec$n")
                for (p in listOf("", "d")) {
                    add("${p}mat$n")
                    for (m in 2..4) add("${p}mat${n}x$m")
                }
            }
            for (p in listOf("", "i", "u")) {
                add("${p}subpassInput")
                add("${p}subpassInputMS")
            }
            add("sampler")
            add("samplerShadow")
        }

        // isampler2DArray, uimage3D, texture2DMS, samplerCubeShadow, ...
        private val OPAQUE_TYPE = Regex("^[iu]?(sampler|image|texture)[0-9A-Z]\\w*$")

        private val BUILTIN_FUNCS = setOf(
            // trig / exponential
            "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan",
            "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
            "pow", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt",
            // common
            "abs", "sign", "floor", "trunc", "round", "roundEven", "ceil", "fract", "mod", "modf",
            "min", "max", "clamp", "mix", "step", "smoothstep", "isnan", "isinf", "fma",
            "floatBitsToInt", "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat", "frexp", "ldexp",
            // packing
            "packUnorm2x16", "packSnorm2x16", "packUnorm4x8", "packSnorm4x8", "unpackUnorm2x16",
            "unpackSnorm2x16", "unpackUnorm4x8", "unpackSnorm4x8", "packHalf2x16", "unpackHalf2x16",
            "packDouble2x32", "unpackDouble2x32",
            // geometric / matrix
            "length", "distance", "dot", "cross", "normalize", "faceforward", "reflect", "refract",
            "matrixCompMult", "outerProduct", "transpose", "determinant", "inverse",
            // vector relational
            "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual",
            "any", "all", "not",
            // integer
            "uaddCarry", "usubBorrow", "umulExtended", "imulExtended", "bitfieldExtract",
            "bitfieldInsert", "bitfieldReverse", "bitCount", "findLSB", "findMSB",
            // texture
            "textureSize", "textureQueryLod", "textureQueryLevels", "textureSamples",
            "texture", "textureProj", "textureLod", "textureOffset", "texelFetch", "texelFetchOffset",
            "textureProjOffset", "textureLodOffset", "textureProjLod", "textureProjLodOffset",
            "textureGrad", "textureGradOffset", "textureProjGrad", "textureProjGradOffset",
            "textureGather", "textureGatherOffset", "textureGatherOffsets",
            // atomics / images
            "atomicCounterIncrement", "atomicCounterDecrement", "atomicCounter",
            "atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor",
            "atomicExchange", "atomicCompSwap",
            "imageSize", "imageSamples", "imageLoad", "imageStore", "imageAtomicAdd", "imageAtomicMin",
            "imageAtomicMax", "imageAtomicAnd", "imageAtomicOr", "imageAtomicXor",
            "imageAtomicExchange", "imageAtomicCompSwap",
            // geometry / fragment / compute
            "EmitStreamVertex", "EndStreamPrimitive", "EmitVertex", "EndPrimitive",
            "dFdx", "dFdy", "dFdxFine", "dFdyFine", "dFdxCoarse", "dFdyCoarse",
            "fwidth", "fwidthFine", "fwidthCoarse",
            "interpolateAtCentroid", "interpolateAtSample", "interpolateAtOffset",
            "barrier", "memoryBarrier", "memoryBarrierAtomicCounter", "memoryBarrierBuffer",
            "memoryBarrierShared", "memoryBarrierImage", "groupMemoryBarrier",
            "subpassLoad",
            "anyInvocation", "allInvocations", "allInvocationsEqual",
            // ray tracing
            "traceRayEXT", "reportIntersectionEXT", "executeCallableEXT",
            "ignoreIntersectionEXT", "terminateRayEXT",
            // mesh shading
            "SetMeshOutputsEXT", "EmitMeshTasksEXT",
            // subgroups (GL_KHR_shader_subgroup_*)
            "subgroupBarrier", "subgroupMemoryBarrier", "subgroupMemoryBarrierBuffer",
            "subgroupMemoryBarrierShared", "subgroupMemoryBarrierImage", "subgroupElect",
            "subgroupAll", "subgroupAny", "subgroupAllEqual",
            "subgroupBroadcast", "subgroupBroadcastFirst", "subgroupBallot", "subgroupInverseBallot",
            "subgroupBallotBitExtract", "subgroupBallotBitCount", "subgroupBallotInclusiveBitCount",
            "subgroupBallotExclusiveBitCount", "subgroupBallotFindLSB", "subgroupBallotFindMSB",
            "subgroupShuffle", "subgroupShuffleXor", "subgroupShuffleUp", "subgroupShuffleDown",
            "subgroupAdd", "subgroupMul", "subgroupMin", "subgroupMax", "subgroupAnd", "subgroupOr", "subgroupXor",
            "subgroupInclusiveAdd", "subgroupInclusiveMul", "subgroupInclusiveMin", "subgroupInclusiveMax",
            "subgroupInclusiveAnd", "subgroupInclusiveOr", "subgroupInclusiveXor",
            "subgroupExclusiveAdd", "subgroupExclusiveMul", "subgroupExclusiveMin", "subgroupExclusiveMax",
            "subgroupExclusiveAnd", "subgroupExclusiveOr", "subgroupExclusiveXor",
            "subgroupClusteredAdd", "subgroupClusteredMul", "subgroupClusteredMin", "subgroupClusteredMax",
            "subgroupClusteredAnd", "subgroupClusteredOr", "subgroupClusteredXor",
            "subgroupQuadBroadcast", "subgroupQuadSwapHorizontal", "subgroupQuadSwapVertical",
            "subgroupQuadSwapDiagonal",
            // misc extensions
            "nonuniformEXT", "debugPrintfEXT", "atomicLoad", "atomicStore",
            "readInvocationARB", "readFirstInvocationARB", "ballotARB",
            // legacy (pre-1.30 / ES 2.0) texture functions
            "texture1D", "texture2D", "texture3D", "textureCube", "texture2DLod", "texture2DProj",
            "textureCubeLod", "shadow2D", "shadow2DProj", "ftransform",
        )
    }
}
