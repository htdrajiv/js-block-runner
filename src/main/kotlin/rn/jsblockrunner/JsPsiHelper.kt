package rn.jsblockrunner

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

data class EnclosingFunctionInfo(
    val text: String,
    val name: String?,
    val paramNames: List<String>
)

object JsPsiHelper {

    val DEFAULT_ALLOWLIST = setOf(
        // Console
        "console.log", "console.error", "console.warn", "console.info", "console.debug", "console.trace",
        // Math
        "Math.max", "Math.min", "Math.abs", "Math.floor", "Math.ceil", "Math.round", 
        "Math.random", "Math.pow", "Math.sqrt", "Math.sin", "Math.cos", "Math.tan",
        // JSON
        "JSON.parse", "JSON.stringify",
        // Number/String conversion
        "parseInt", "parseFloat", "Number", "String", "Boolean",
        // Date
        "Date", "Date.now", "Date.parse",
        // Array methods (these are typically called on instances, but just in case)
        "Array", "Array.isArray", "Array.from", "Array.of",
        // Object methods
        "Object", "Object.keys", "Object.values", "Object.entries", "Object.assign", 
        "Object.freeze", "Object.seal", "Object.create",
        // Promise
        "Promise", "Promise.resolve", "Promise.reject", "Promise.all", "Promise.race", "Promise.allSettled",
        // Error constructors (used with 'new')
        "Error", "TypeError", "SyntaxError", "ReferenceError", "RangeError", "URIError", "EvalError",
        // Other built-in constructors
        "Map", "Set", "WeakMap", "WeakSet", "RegExp",
        "Int8Array", "Uint8Array", "Int16Array", "Uint16Array",
        "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
        "ArrayBuffer", "SharedArrayBuffer", "DataView",
        "URL", "URLSearchParams",
        // Global functions
        "setTimeout", "setInterval", "clearTimeout", "clearInterval",
        "encodeURIComponent", "decodeURIComponent", "encodeURI", "decodeURI",
        "isNaN", "isFinite", "eval", "atob", "btoa",
        // Reflect and Proxy
        "Reflect", "Proxy",
        // Symbol
        "Symbol", "Symbol.for", "Symbol.keyFor"
    )

    fun findEnclosingFunctionInfo(psiFile: PsiFile, caretOffset: Int): EnclosingFunctionInfo? {
        val el = psiFile.findElementAt(caretOffset) ?: return null
        
        // Find all enclosing functions and pick the outermost named one,
        // or the outermost one if none are named
        var func = PsiTreeUtil.getParentOfType(el, JSFunction::class.java)
            ?: return null
        
        // Walk up to find the outermost function (or the outermost named function)
        var outermostNamedFunc: JSFunction? = if (func.name != null) func else null
        var outermostFunc: JSFunction = func
        
        var parent = PsiTreeUtil.getParentOfType(func, JSFunction::class.java)
        while (parent != null) {
            outermostFunc = parent
            if (parent.name != null) {
                outermostNamedFunc = parent
            }
            parent = PsiTreeUtil.getParentOfType(parent, JSFunction::class.java)
        }
        
        // Prefer the outermost named function, otherwise use the outermost function
        val targetFunc = outermostNamedFunc ?: outermostFunc
        
        val name = targetFunc.name
        
        // Try to get parameters from PSI
        var params = targetFunc.parameterVariables.mapNotNull { it.name }
        
        // If PSI didn't give us params, try regex fallback on the function text
        if (params.isEmpty()) {
            params = extractParamsFromText(targetFunc.text)
        }

        return EnclosingFunctionInfo(
            text = targetFunc.text,
            name = name,
            paramNames = params
        )
    }

    /**
     * Extract function info from code text using regex (for selections or fallback)
     */
    fun extractFunctionInfoFromText(codeText: String): EnclosingFunctionInfo? {
        val trimmed = codeText.trim()
        
        // First, try to find the function name using simpler patterns
        val namePatterns = listOf(
            // async function name( or function name(
            Regex("""^(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\("""),
            // const name = async ( or const name = (
            Regex("""^(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?\("""),
            // const name = async function( or const name = function(
            Regex("""^(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?function\s*\("""),
            // name: async ( or name: ( (object method shorthand)
            Regex("""^(\w+)\s*:\s*(?:async\s+)?\("""),
            // name: async function( or name: function(
            Regex("""^(\w+)\s*:\s*(?:async\s+)?function\s*\("""),
            // async name( or name( (class method)
            Regex("""^(?:async\s+)?(\w+)\s*\(""")
        )
        
        var functionName: String? = null
        for (pattern in namePatterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                functionName = match.groupValues[1].ifBlank { null }
                break
            }
        }
        
        // Check for anonymous functions
        val isAnonymous = functionName == null && (
            trimmed.startsWith("(") ||
            trimmed.startsWith("async (") ||
            trimmed.startsWith("async(") ||
            trimmed.startsWith("function(") ||
            trimmed.startsWith("function (") ||
            trimmed.startsWith("async function(") ||
            trimmed.startsWith("async function (")
        )
        
        // If we found a function pattern, extract parameters using balanced parentheses
        if (functionName != null || isAnonymous) {
            val params = extractParamsFromText(trimmed)
            return EnclosingFunctionInfo(
                text = codeText,
                name = functionName,
                paramNames = params
            )
        }
        
        return null
    }

    private fun extractParamsFromText(funcText: String): List<String> {
        // Find the parameter list between first ( and matching )
        val openParen = funcText.indexOf('(')
        if (openParen == -1) return emptyList()
        
        var depth = 0
        var closeParen = -1
        for (i in openParen until funcText.length) {
            when (funcText[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        closeParen = i
                        break
                    }
                }
            }
        }
        
        if (closeParen == -1) return emptyList()
        
        val paramString = funcText.substring(openParen + 1, closeParen)
        return parseParamList(paramString)
    }

    private fun parseParamList(paramString: String): List<String> {
        if (paramString.isBlank()) return emptyList()
        
        val params = mutableListOf<String>()
        var current = StringBuilder()
        var depth = 0  // Track nested braces/brackets/parens for destructuring and default values
        
        for (char in paramString) {
            when (char) {
                '{', '[', '(', '<' -> {
                    depth++
                    current.append(char)
                }
                '}', ']', ')', '>' -> {
                    depth--
                    current.append(char)
                }
                ',' -> {
                    if (depth == 0) {
                        val param = extractParamName(current.toString())
                        if (param.isNotBlank()) params.add(param)
                        current = StringBuilder()
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }
        
        // Don't forget the last parameter
        val lastParam = extractParamName(current.toString())
        if (lastParam.isNotBlank()) params.add(lastParam)
        
        return params
    }

    private fun extractParamName(param: String): String {
        val trimmed = param.trim()
        
        // Handle destructuring: { a, b } or [a, b] - use the whole thing as the "name"
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // For destructuring, return a descriptive name
            return if (trimmed.startsWith("{")) {
                // Extract keys from object destructuring for display
                val inner = trimmed.removeSurrounding("{", "}").trim()
                if (inner.length < 30) "{$inner}" else "{...}"
            } else {
                val inner = trimmed.removeSurrounding("[", "]").trim()
                if (inner.length < 30) "[$inner]" else "[...]"
            }
        }
        
        // Handle default values: param = defaultValue
        val withoutDefault = trimmed.split("=")[0].trim()
        
        // Handle TypeScript type annotations: param: Type
        val withoutType = withoutDefault.split(":")[0].trim()
        
        // Handle rest parameters: ...args
        return withoutType.removePrefix("...")
    }

    fun detectExternalCallKeysForFunction(enclosingFunc: PsiElement): Set<String> {
        val result = mutableSetOf<String>()
        val calls = PsiTreeUtil.findChildrenOfType(enclosingFunc, JSCallExpression::class.java)
        
        for (call in calls) {
            val methodExpr = call.methodExpression
            if (methodExpr is JSReferenceExpression) {
                val key = methodExpr.text
                
                // Skip if it's a method call on a local variable (e.g., arr.map, str.split)
                val qualifier = methodExpr.qualifier
                if (qualifier is JSReferenceExpression) {
                    val qualifierResolved = qualifier.reference?.resolve()
                    if (qualifierResolved != null && PsiTreeUtil.isAncestor(enclosingFunc, qualifierResolved, true)) {
                        continue
                    }
                }
                
                val resolved = methodExpr.reference?.resolve()
                if (resolved == null) {
                    result.add(key)
                } else {
                    if (!PsiTreeUtil.isAncestor(enclosingFunc, resolved, true)) {
                        result.add(key)
                    }
                }
            }
        }
        return result
    }

    // Common global functions that should be offered for mocking (not in DEFAULT_ALLOWLIST)
    private val MOCKABLE_GLOBALS = setOf(
        "fetch", "require", "alert", "confirm", "prompt", "open", "close",
        "print", "scroll", "scrollTo", "scrollBy", "focus", "blur",
        "getComputedStyle", "matchMedia", "requestAnimationFrame", "cancelAnimationFrame",
        "queueMicrotask", "structuredClone", "reportError"
    )

    fun detectExternalCallKeysByRegex(snippetText: String, localVariables: Set<String> = emptySet()): Set<String> {
        val result = mutableSetOf<String>()
        
        val regex = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(""")
        val keywords = setOf(
            "if", "for", "while", "switch", "catch", "function", "return", 
            "typeof", "new", "await", "async", "class", "const", "let", "var",
            "try", "throw", "finally", "else", "do", "break", "continue",
            "import", "export", "from", "default", "yield", "delete", "void",
            "instanceof", "in", "of", "with", "debugger", "super", "this",
            "extends", "implements", "interface", "package", "private", "protected",
            "public", "static", "get", "set"
        )
        
        // Combine provided local variables with those defined in the snippet
        val allLocalVars = localVariables + extractLocalDefinitions(snippetText)
        
        for (m in regex.findAll(snippetText)) {
            val key = m.groupValues[1]
            
            // Skip if the base object is a local variable (e.g., email.split where email is a param)
            val baseObject = key.split(".").first()
            if (baseObject in allLocalVars) {
                continue
            }
            
            // Skip keywords
            if (key in keywords) {
                continue
            }
            
            // Include if it's a known mockable global (like fetch)
            if (key in MOCKABLE_GLOBALS) {
                result.add(key)
                continue
            }
            
            // Include if it contains a dot (qualified name like obj.method)
            if (key.contains(".")) {
                result.add(key)
                continue
            }
            
            // Include if it's not all lowercase (likely a custom function like myFunc, getData, etc.)
            if (!key.matches(Regex("^[a-z]+$"))) {
                result.add(key)
            }
        }
        
        return result
    }

    fun detectAwaitExpressions(snippetText: String): Set<String> {
        val result = mutableSetOf<String>()
        val regex = Regex("""await\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(""")
        
        for (m in regex.findAll(snippetText)) {
            result.add(m.groupValues[1])
        }
        
        return result
    }

    /**
     * Detect 'this.' references in the code that need to be mocked.
     * Returns keys like "this.logger.error", "this.service.getData", etc.
     */
    fun detectThisReferences(snippetText: String): Set<String> {
        val result = mutableSetOf<String>()
        
        // Match this.property or this.property.method patterns (including method calls)
        // e.g., this.logger.error(message) -> this.logger.error
        // e.g., this.service.getData() -> this.service.getData
        // e.g., this.name -> this.name
        val thisCallRegex = Regex("""this\.([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(""")
        for (m in thisCallRegex.findAll(snippetText)) {
            result.add("this.${m.groupValues[1]}")
        }
        
        // Also match this.property access (not just calls)
        // e.g., this.config, this.options.timeout
        val thisAccessRegex = Regex("""this\.([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)(?!\s*\()""")
        for (m in thisAccessRegex.findAll(snippetText)) {
            val key = "this.${m.groupValues[1]}"
            // Only add if not already covered by a more specific call pattern
            if (!result.any { it.startsWith(key) }) {
                result.add(key)
            }
        }
        
        return result
    }

    /**
     * Detect external variables used in the code (not just function calls).
     * This catches variables like authUrl, requestHeaders, etc. that are used as arguments
     * or in expressions but not defined in the current scope.
     */
    fun detectExternalVariablesByRegex(snippetText: String, localVariables: Set<String> = emptySet()): Set<String> {
        val result = mutableSetOf<String>()
        
        // Combine provided local variables with those defined in the snippet
        val allLocalVars = localVariables + extractLocalDefinitions(snippetText)
        
        // Keywords and built-ins to skip
        val skipWords = setOf(
            // JS keywords
            "if", "for", "while", "switch", "catch", "function", "return", 
            "typeof", "new", "await", "async", "class", "const", "let", "var",
            "try", "throw", "finally", "else", "do", "break", "continue",
            "import", "export", "from", "default", "yield", "delete", "void",
            "instanceof", "in", "of", "with", "debugger", "super", "this",
            "extends", "implements", "interface", "package", "private", "protected",
            "public", "static", "get", "set", "true", "false", "null", "undefined",
            // Common built-ins (lowercase)
            "console", "fetch", "require", "alert", "confirm", "prompt"
        )
        
        // Find all identifiers (both camelCase and simple lowercase)
        val identifierRegex = Regex("""\b([a-zA-Z_$][a-zA-Z0-9_$]*)\b""")
        
        for (m in identifierRegex.findAll(snippetText)) {
            val name = m.groupValues[1]
            
            // Skip if it's a local variable or keyword
            if (name in allLocalVars || name in skipWords) {
                continue
            }
            
            // Skip very short names (likely loop vars like i, j, k, or built-in like 'as')
            if (name.length < 3) {
                continue
            }
            
            // Skip PascalCase names (likely types/classes, handled by detectExternalConstantsByRegex)
            if (name[0].isUpperCase()) {
                continue
            }
            
            val matchStart = m.range.first
            val matchEnd = m.range.last + 1
            
            // Skip if it looks like a property access (preceded by . or ?.)
            if (matchStart > 0) {
                val charBefore = snippetText[matchStart - 1]
                if (charBefore == '.') {
                    continue
                }
                // Check for optional chaining ?.
                if (matchStart > 1 && charBefore == '?' && snippetText[matchStart - 2] != ' ') {
                    // This is actually part of ternary, not optional chaining - don't skip
                    // But if it's like "obj?.prop", the ? is preceded by identifier
                }
            }
            
            // Skip if it's followed by ( - it's a function call, handled by detectExternalCallKeysByRegex
            if (matchEnd < snippetText.length && snippetText[matchEnd] == '(') {
                continue
            }
            
            // Skip if it looks like a property key in object literal: { key: value }
            // Check if followed by : but not ::
            if (matchEnd < snippetText.length) {
                val afterMatch = snippetText.substring(matchEnd, minOf(snippetText.length, matchEnd + 2))
                if (afterMatch.startsWith(":") && !afterMatch.startsWith("::")) {
                    // Check if we're in an object literal context (not a ternary or type annotation)
                    val beforeContext = if (matchStart > 20) snippetText.substring(matchStart - 20, matchStart) else snippetText.substring(0, matchStart)
                    // If there's a { or , before without a ? (ternary), it's likely an object key
                    if ((beforeContext.contains("{") || beforeContext.contains(",")) && 
                        !beforeContext.contains("?") && !beforeContext.contains("<")) {
                        continue
                    }
                }
            }
            
            result.add(name)
        }
        
        return result
    }

    /**
     * Detect external constants/variables referenced in the code that are not defined locally.
     * This helps identify imports that need to be mocked.
     */
    fun detectExternalConstantsByRegex(snippetText: String): Set<String> {
        val result = mutableSetOf<String>()
        
        // Find all UPPER_CASE identifiers (common convention for constants)
        val upperCaseConstRegex = Regex("""\b([A-Z][A-Z0-9_]{2,})\b""")
        for (m in upperCaseConstRegex.findAll(snippetText)) {
            val name = m.groupValues[1]
            // Skip common JS globals and keywords
            if (name !in GLOBAL_CONSTANTS) {
                result.add(name)
            }
        }
        
        // Find PascalCase identifiers that look like imported constants/enums
        // (but not after 'new' keyword, and not function calls)
        val pascalCaseRegex = Regex("""(?<!new\s)(?<!\.\s*)(?<!function\s)\b([A-Z][a-zA-Z0-9]+)\b(?!\s*\()""")
        for (m in pascalCaseRegex.findAll(snippetText)) {
            val name = m.groupValues[1]
            // Skip if it looks like a type annotation (after : or <)
            val matchStart = m.range.first
            val beforeMatch = if (matchStart > 0) snippetText.substring(maxOf(0, matchStart - 10), matchStart) else ""
            if (!beforeMatch.contains(":") && !beforeMatch.contains("<") && !beforeMatch.endsWith("new ")) {
                // Skip common built-in types/classes
                if (name !in BUILTIN_CLASSES) {
                    result.add(name)
                }
            }
        }
        
        return result
    }

    /**
     * Extract local variable/constant names defined in the code
     */
    fun extractLocalDefinitions(snippetText: String): Set<String> {
        val result = mutableSetOf<String>()
        
        // Match const/let/var declarations
        val declRegex = Regex("""(?:const|let|var)\s+(\w+)""")
        for (m in declRegex.findAll(snippetText)) {
            result.add(m.groupValues[1])
        }
        
        // Match function declarations
        val funcRegex = Regex("""function\s+(\w+)""")
        for (m in funcRegex.findAll(snippetText)) {
            result.add(m.groupValues[1])
        }
        
        // Match destructuring (simplified)
        val destructRegex = Regex("""(?:const|let|var)\s+\{([^}]+)\}""")
        for (m in destructRegex.findAll(snippetText)) {
            val inner = m.groupValues[1]
            // Extract identifiers from destructuring
            val identifiers = inner.split(",").map { 
                it.trim().split(":").last().trim().split("=").first().trim()
            }
            result.addAll(identifiers.filter { it.matches(Regex("""\w+""")) })
        }
        
        return result
    }

    private val GLOBAL_CONSTANTS = setOf(
        "NAN", "NULL", "TRUE", "FALSE", "UNDEFINED",
        "JSON", "MATH", "DATE", "ARRAY", "OBJECT", "STRING", "NUMBER", "BOOLEAN",
        "MAP", "SET", "PROMISE", "PROXY", "REFLECT", "SYMBOL",
        "INT8ARRAY", "UINT8ARRAY", "INT16ARRAY", "UINT16ARRAY",
        "INT32ARRAY", "UINT32ARRAY", "FLOAT32ARRAY", "FLOAT64ARRAY",
        "ARRAYBUFFER", "SHAREDARRAYBUFFER", "DATAVIEW",
        "ERROR", "TYPEERROR", "SYNTAXERROR", "REFERENCEERROR", "RANGEERROR",
        "INFINITY", "NAN"
    )

    private val BUILTIN_CLASSES = setOf(
        "Array", "Object", "String", "Number", "Boolean", "Function",
        "Date", "RegExp", "Error", "TypeError", "SyntaxError", "ReferenceError", "RangeError",
        "Map", "Set", "WeakMap", "WeakSet", "Promise", "Proxy", "Reflect", "Symbol",
        "Int8Array", "Uint8Array", "Int16Array", "Uint16Array",
        "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
        "ArrayBuffer", "SharedArrayBuffer", "DataView", "Buffer",
        "JSON", "Math", "Intl", "Atomics", "WebAssembly",
        "URL", "URLSearchParams", "Headers", "Request", "Response",
        "FormData", "Blob", "File", "FileReader",
        "Event", "CustomEvent", "EventTarget",
        "Node", "Element", "Document", "Window",
        "Console", "Performance", "Navigator", "Location", "History"
    )
}
