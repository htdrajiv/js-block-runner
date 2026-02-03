package rn.jsblockrunner

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

object RunnerGenerator {

    fun writeTempRunner(
        @Suppress("UNUSED_PARAMETER") project: Project,
        userCode: String,
        enabledMocks: Map<String, String?>,
        functionName: String?,
        functionArgs: List<String>
    ): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "js-block-runner")
        dir.mkdirs()
        val file = File(dir, "runner-${Instant.now().toEpochMilli()}.cjs")
        file.writeText(buildRunner(userCode, enabledMocks, functionName, functionArgs), StandardCharsets.UTF_8)
        return file
    }

    private fun buildRunner(
        userCode: String,
        enabledMocks: Map<String, String?>,
        functionName: String?,
        functionArgs: List<String>
    ): String {

        val mockDefs = enabledMocks.entries.joinToString("\n") { (k, v) ->
            val safeVar = safeVarName(k)
            val expr = v ?: "undefined"
            "const $safeVar = $expr;"
        }

        val injector = enabledMocks.keys.joinToString("\n") { key ->
            injectForKey(key)
        }

        val hasThisMocks = enabledMocks.keys.any { it.startsWith("this.") }
        
        // Check if the code uses 'this.' references (even if not explicitly mocked)
        val codeUsesThis = userCode.contains(Regex("""\bthis\."""))
        
        // Check if the code uses 'await' (needs async context)
        val codeUsesAwait = userCode.contains(Regex("""\bawait\s"""))

        val strippedTs = stripTypeScript(stripImportsAndExports(userCode))
        val strippedCode = convertClassMethodToFunction(strippedTs.trim())
        
        // Check if this is a plain code block (not a function definition)
        val isPlainCodeBlock = functionName == null && functionArgs.isEmpty() && !isFunction(strippedCode)
        
        // Determine if this is a code block (not a function) that needs 'this' binding
        val isCodeBlockWithThis = functionArgs.isEmpty() && (codeUsesThis || hasThisMocks)
        
        // Determine if this is a code block (not a function) that needs async context
        val isCodeBlockWithAwait = functionArgs.isEmpty() && codeUsesAwait && functionName == null
        
        val callTarget = buildCallTarget(strippedCode, functionName, functionArgs, hasThisMocks, codeUsesThis)

        val detectedImports = extractImportInfo(userCode)
        val importWarning = if (detectedImports.isNotEmpty()) {
            """
            console.log('\n⚠️  Note: The following imports were stripped (mock them if needed):');
            ${detectedImports.joinToString("\n") { "console.log('   - $it');" }}
            console.log('');
            """.trimIndent()
        } else ""
        
        // For code blocks with 'this.' references, await, or plain code blocks, the user code goes inside the IIFE
        // For functions, the user code is placed at the top level
        val userCodeSection = if (isCodeBlockWithThis || isCodeBlockWithAwait || isPlainCodeBlock) {
            "// Code block executed in async context below"
        } else {
            strippedCode
        }
        
        // Determine the execution wrapper based on code requirements
        val executionCode = when {
            // Plain code block with both 'this.' and 'await'
            isPlainCodeBlock && isCodeBlockWithThis && isCodeBlockWithAwait -> {
                """
// Initialize __mockThis for 'this.' references
globalThis.__mockThis = globalThis.__mockThis ?? {};

// Execute the code block with 'this' bound to __mockThis (async)
return (async function() {
$strippedCode
}).call(globalThis.__mockThis);
""".trimIndent()
            }
            // Plain code block with only 'this.'
            isPlainCodeBlock && isCodeBlockWithThis -> {
                """
// Initialize __mockThis for 'this.' references
globalThis.__mockThis = globalThis.__mockThis ?? {};

// Execute the code block with 'this' bound to __mockThis
return (function() {
$strippedCode
}).call(globalThis.__mockThis);
""".trimIndent()
            }
            // Plain code block with 'await' (no 'this.')
            isPlainCodeBlock && isCodeBlockWithAwait -> {
                """
// Execute the code block in async context
return (async function() {
$strippedCode
})();
""".trimIndent()
            }
            // Plain code block (if, for, while, etc.) - wrap in async IIFE
            isPlainCodeBlock -> {
                """
// Execute plain code block
return (async function() {
$strippedCode
})();
""".trimIndent()
            }
            // Function with 'this.' and 'await'
            isCodeBlockWithThis && isCodeBlockWithAwait -> {
                """
// Initialize __mockThis for 'this.' references
globalThis.__mockThis = globalThis.__mockThis ?? {};

// Execute the code block with 'this' bound to __mockThis (async)
return (async function() {
$strippedCode
}).call(globalThis.__mockThis);
""".trimIndent()
            }
            // Function with only 'this.'
            isCodeBlockWithThis -> {
                """
// Initialize __mockThis for 'this.' references
globalThis.__mockThis = globalThis.__mockThis ?? {};

// Execute the code block with 'this' bound to __mockThis
return (function() {
$strippedCode
}).call(globalThis.__mockThis);
""".trimIndent()
            }
            // Function with only 'await' (no 'this.')
            isCodeBlockWithAwait -> {
                """
// Execute the code block in async context
return (async function() {
$strippedCode
})();
""".trimIndent()
            }
            // Regular function call or fallback for code blocks
            else -> {
                if (callTarget.isBlank()) {
                    // Fallback: wrap code in async IIFE if callTarget is empty
                    """
// Execute code block (fallback)
return (async function() {
$strippedCode
})();
""".trimIndent()
                } else {
                    callTarget
                }
            }
        }

        return """
// Auto-generated by JS Block Runner
'use strict';

// ═══════════════════════════════════════════════════════════════
// Mock Definitions
// ═══════════════════════════════════════════════════════════════
$mockDefs

// ═══════════════════════════════════════════════════════════════
// Helper Functions
// ═══════════════════════════════════════════════════════════════

function setNested(root, pathArr, value) {
  let cur = root;
  for (let i = 0; i < pathArr.length - 1; i++) {
    const p = pathArr[i];
    cur[p] = cur[p] ?? {};
    cur = cur[p];
  }
  cur[pathArr[pathArr.length - 1]] = value;
}

function createSpyFunction(name, impl) {
  const calls = [];
  const fn = async function(...args) {
    calls.push({ args, timestamp: Date.now() });
    try {
      const result = await (typeof impl === 'function' ? impl(...args) : impl);
      calls[calls.length - 1].result = result;
      return result;
    } catch (err) {
      calls[calls.length - 1].error = err;
      throw err;
    }
  };
  fn.calls = calls;
  fn.mockName = name;
  return fn;
}

function createSpyConstructor(name, impl) {
  const calls = [];
  // Use a regular function (not arrow) so it can be used with 'new'
  function SpyConstructor(...args) {
    calls.push({ args, timestamp: Date.now() });
    try {
      // If impl is a class/constructor, use new; otherwise call as function
      if (impl.prototype && impl.prototype.constructor === impl) {
        const instance = new impl(...args);
        calls[calls.length - 1].result = instance;
        return instance;
      } else if (typeof impl === 'function') {
        const result = impl(...args);
        calls[calls.length - 1].result = result;
        // If called with 'new' and result is not an object, return 'this'
        if (new.target && (result === undefined || typeof result !== 'object')) {
          Object.assign(this, { args });
          return this;
        }
        return result;
      } else {
        // impl is a value, assign it to this
        if (typeof impl === 'object' && impl !== null) {
          Object.assign(this, impl);
        }
        calls[calls.length - 1].result = this;
        return this;
      }
    } catch (err) {
      calls[calls.length - 1].error = err;
      throw err;
    }
  }
  SpyConstructor.calls = calls;
  SpyConstructor.mockName = name;
  return SpyConstructor;
}

// ════════════���══════════════════════════════════════════════════
// Mock Injection
// ═══════════════════════════════════════════════════════════════
$injector

// ═══════════════════════════════════════════════════════════════
// User Code
// ═══════════════════════════════════════════════════════════════
$importWarning

$userCodeSection

// ═══════════════════════════════════════════════════════════════
// Execution
// ═══════════════════════════════════════════════════════════════
const __startTime = Date.now();

Promise.resolve()
  .then(() => {
    $executionCode
  })
  .then((res) => {
    const elapsed = Date.now() - __startTime;
    console.log('\n═══════════════════════════════════════════════════════════════');
    console.log('✅ Execution completed in ' + elapsed + 'ms');
    console.log('═══════════════════════════════════════════════════════════════');
    
    if (typeof res !== 'undefined') {
      console.log('\n📤 RESULT:');
      try {
        console.log(JSON.stringify(res, null, 2));
      } catch (e) {
        console.log(res);
      }
    }
    
    // Print mock call summaries
    const mockSummaries = [];
    for (const key of Object.keys(globalThis)) {
      const val = globalThis[key];
      if (val && val.calls && val.mockName) {
        mockSummaries.push({ name: val.mockName, calls: val.calls });
      }
      if (val && typeof val === 'object') {
        for (const subKey of Object.keys(val)) {
          const subVal = val[subKey];
          if (subVal && subVal.calls && subVal.mockName) {
            mockSummaries.push({ name: subVal.mockName, calls: subVal.calls });
          }
        }
      }
    }
    
    if (mockSummaries.length > 0) {
      console.log('\n📊 Mock Call Summary:');
      for (const m of mockSummaries) {
        console.log('   ' + m.name + ': called ' + m.calls.length + ' time(s)');
        for (let i = 0; i < m.calls.length; i++) {
          const c = m.calls[i];
          console.log('      [' + (i+1) + '] args: ' + JSON.stringify(c.args));
          if (c.error) {
            console.log('          threw: ' + c.error.message);
          } else if (c.result !== undefined) {
            try {
              console.log('          returned: ' + JSON.stringify(c.result));
            } catch(e) {
              console.log('          returned: [non-serializable]');
            }
          }
        }
      }
    }
  })
  .catch((err) => {
    const elapsed = Date.now() - __startTime;
    console.error('\n═══════════════════════════════════════════════════════════════');
    console.error('❌ Execution failed after ' + elapsed + 'ms');
    console.error('══════════════════════════════��════════════════════════════════');
    console.error('\n🔥 ERROR:', err && err.stack ? err.stack : err);
    process.exitCode = 1;
  });
""".trimIndent()
    }

    private fun buildCallTarget(code: String, functionName: String?, functionArgs: List<String>, hasThisMocks: Boolean = false, codeUsesThis: Boolean = false): String {
        // If this is a code block (not a function) that uses 'this.', the wrapping is handled in buildRunner
        if (functionArgs.isEmpty()) {
            return ""
        }

        val argsExpr = functionArgs.joinToString(", ") { 
            val trimmed = it.trim()
            if (trimmed.isBlank()) "undefined" else autoQuoteIfNeeded(trimmed)
        }

        if (!functionName.isNullOrBlank()) {
            return if (hasThisMocks || codeUsesThis) {
                """
globalThis.__mockThis = globalThis.__mockThis ?? {};
return $functionName.call(globalThis.__mockThis, $argsExpr);
""".trimIndent()
            } else {
                "return $functionName($argsExpr);"
            }
        }

        return if (hasThisMocks || codeUsesThis) {
            """
globalThis.__mockThis = globalThis.__mockThis ?? {};
const __target = ($code);
return __target.call(globalThis.__mockThis, $argsExpr);
""".trimIndent()
        } else {
            """
const __target = ($code);
return __target($argsExpr);
""".trimIndent()
        }
    }

    private fun autoQuoteIfNeeded(value: String): String {
        if (isLikelyValidJsExpression(value)) {
            return value
        }
        
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun isLikelyValidJsExpression(value: String): Boolean {
        val trimmed = value.trim()
        
        if (trimmed.isEmpty()) return false
        if (trimmed in setOf("undefined", "null", "true", "false", "NaN", "Infinity")) return true
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("`") && trimmed.endsWith("`"))) return true
        if (trimmed.matches(Regex("""^-?\d+(\.\d+)?([eE][+-]?\d+)?$"""))) return true
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) return true
        if (trimmed.startsWith("({") && trimmed.endsWith("})")) return true
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return true
        if (trimmed.startsWith("(") && (trimmed.contains("=>") || trimmed.contains("function"))) return true
        if (trimmed.startsWith("function")) return true
        if (trimmed.matches(Regex("""^\w+\s*=>\s*.*"""))) return true
        if (trimmed.startsWith("async")) return true
        if (trimmed.matches(Regex("""^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*(\(.*\))?$"""))) return true
        if (trimmed.startsWith("new ")) return true
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) return true
        if (trimmed.matches(Regex("""^[A-Za-z_$][\w$]*$"""))) return true
        if (trimmed.contains("@") && !trimmed.contains("\"") && !trimmed.contains("'")) return false
        if (trimmed.contains(" ") && !trimmed.contains("=>") && !trimmed.contains("function") && 
            !trimmed.startsWith("new ") && !trimmed.startsWith("async ") &&
            !trimmed.contains("?") && !trimmed.contains(":")) return false
        
        return true
    }

    private fun safeVarName(key: String): String {
        val s = key.replace('.', '_').replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (s.matches(Regex("^[0-9].*"))) "_$s" else "MOCKDEF_$s"
    }

    private fun injectForKey(key: String): String {
        val safeVar = safeVarName(key)

        if (key.startsWith("this.")) {
            val pathArray = key.split(".").drop(1)
            val pathJsArray = pathArray.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
            
            return """
(function(){
  globalThis.__mockThis = globalThis.__mockThis ?? {};
  if (typeof $safeVar === 'function') {
    setNested(globalThis.__mockThis, $pathJsArray, createSpyFunction("$key", $safeVar));
  } else {
    setNested(globalThis.__mockThis, $pathJsArray, $safeVar);
  }
})();
""".trimIndent()
        }

        // Check if this looks like a constructor (PascalCase)
        val isLikelyConstructor = key.first().isUpperCase()
        
        return if (!key.contains(".")) {
            if (isLikelyConstructor) {
                // Use createSpyConstructor for PascalCase names (likely constructors)
                """
(function(){
  if (typeof $safeVar === 'function') {
    globalThis["$key"] = createSpyConstructor("$key", $safeVar);
  } else {
    globalThis["$key"] = $safeVar;
  }
})();
""".trimIndent()
            } else {
                """
(function(){
  if (typeof $safeVar === 'function') {
    globalThis["$key"] = createSpyFunction("$key", $safeVar);
  } else {
    globalThis["$key"] = $safeVar;
  }
})();
""".trimIndent()
            }
        } else {
            val parts = key.split(".")
            val top = parts.first()
            val pathArray = parts.drop(1)
            val pathJsArray = pathArray.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")

            """
(function(){
  globalThis["$top"] = globalThis["$top"] ?? {};
  if (typeof $safeVar === 'function') {
    setNested(globalThis["$top"], $pathJsArray, createSpyFunction("$key", $safeVar));
  } else {
    setNested(globalThis["$top"], $pathJsArray, $safeVar);
  }
})();
""".trimIndent()
        }
    }

    private fun stripTypeScript(code: String): String {
        var result = code
        
        result = result.replace(Regex("""import\s+type\s+\{[^}]*}\s+from\s+['"][^'"]+['"];?\s*"""), "")
        result = result.replace(Regex("""import\s+\{\s*type\s+[^}]*}\s+from\s+['"][^'"]+['"];?\s*"""), "")
        result = result.replace(Regex("""(?m)^[ \t]*(?:export\s+)?interface\s+\w+(?:\s+extends\s+[^{]+)?\s*\{[^}]*\}\s*"""), "")
        result = result.replace(Regex("""(?m)^[ \t]*(?:export\s+)?type\s+\w+\s*=\s*[^;]+;\s*"""), "")
        result = result.replace(Regex("""\)\s*:\s*(?:Promise\s*<[^>]+>|[A-Za-z_][\w.<>,\s\[\]|&]*)\s*(?=\{|=>)"""), ") ")
        result = stripParameterTypes(result)
        result = result.replace(Regex("""((?:const|let|var)\s+\w+)\s*:\s*[A-Za-z_][\w.<>,\s\[\]|&]*\s*="""), "$1 =")
        result = result.replace(Regex("""<([A-Za-z_][\w.<>,\s\[\]|&]*)>(?=\s*[\w({])"""), "")
        result = result.replace(Regex("""\s+as\s+[A-Za-z_][\w.<>,\s\[\]|&]*(?=\s*[;,)\]}])"""), "")
        result = result.replace(Regex("""(\w)\!(?=\s*[.;,)\]])"""), "$1")
        result = result.replace(Regex("""\breadonly\s+"""), "")
        result = result.replace(Regex("""(?m)^(\s*)(?:public|private|protected)\s+"""), "$1")
        result = result.replace(Regex("""\babstract\s+"""), "")
        result = result.replace(Regex("""(?m)^[ \t]*declare\s+[^;]+;\s*"""), "")
        result = result.replace(Regex("""(?m)^[ \t]*(?:export\s+)?(?:const\s+)?enum\s+\w+\s*\{[^}]*\}\s*"""), "")
        result = result.replace(Regex("""[ \t]+\n"""), "\n")
        result = result.replace(Regex("""\n{3,}"""), "\n\n")
        
        return result
    }

    private fun stripParameterTypes(code: String): String {
        val result = StringBuilder()
        var i = 0
        
        // Keywords that have parentheses but are NOT function parameter lists
        val controlFlowKeywords = setOf("if", "for", "while", "switch", "catch", "with", "new", "return", "throw")
        
        while (i < code.length) {
            if (code[i] == '(' && i > 0) {
                // Check what's before the parenthesis
                val beforeParen = code.substring(0, i).trimEnd()
                
                // Extract the last word before the paren
                val lastWordMatch = Regex("""\b(\w+)\s*$""").find(beforeParen)
                val lastWord = lastWordMatch?.groupValues?.get(1) ?: ""
                
                // Skip stripping for control flow statements and other non-function contexts
                val isControlFlow = lastWord in controlFlowKeywords
                
                // Only strip types for function definitions (after 'function' keyword)
                // Be conservative - only strip after explicit 'function' keyword
                val isFunctionDef = beforeParen.endsWith("function")
                
                if (isFunctionDef) {
                    result.append('(')
                    i++
                    
                    val paramContent = StringBuilder()
                    var depth = 1
                    while (i < code.length && depth > 0) {
                        when (code[i]) {
                            '(' -> depth++
                            ')' -> depth--
                        }
                        if (depth > 0) {
                            paramContent.append(code[i])
                        }
                        i++
                    }
                    
                    val strippedParams = stripTypesFromParams(paramContent.toString())
                    result.append(strippedParams)
                    result.append(')')
                } else {
                    result.append(code[i])
                    i++
                }
            } else {
                result.append(code[i])
                i++
            }
        }
        
        return result.toString()
    }

    private fun stripTypesFromParams(params: String): String {
        if (params.isBlank()) return params
        
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var depth = 0
        
        for (char in params) {
            when (char) {
                '<', '{', '[', '(' -> {
                    depth++
                    current.append(char)
                }
                '>', '}', ']', ')' -> {
                    depth--
                    current.append(char)
                }
                ',' -> {
                    if (depth == 0) {
                        result.add(stripTypeFromSingleParam(current.toString()))
                        current = StringBuilder()
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }
        
        if (current.isNotBlank()) {
            result.add(stripTypeFromSingleParam(current.toString()))
        }
        
        return result.joinToString(", ")
    }

    private fun stripTypeFromSingleParam(param: String): String {
        val trimmed = param.trim()
        if (trimmed.isEmpty()) return trimmed
        
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            var depth = 0
            var endIdx = 0
            for (i in trimmed.indices) {
                when (trimmed[i]) {
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            endIdx = i + 1
                            break
                        }
                    }
                }
            }
            
            val destructPart = trimmed.substring(0, endIdx)
            val rest = trimmed.substring(endIdx).trim()
            
            return if (rest.startsWith(":")) {
                val afterColon = rest.substring(1).trim()
                val eqIdx = findDefaultValueEquals(afterColon)
                if (eqIdx >= 0) {
                    "$destructPart = ${afterColon.substring(eqIdx + 1).trim()}"
                } else {
                    destructPart
                }
            } else if (rest.startsWith("=")) {
                "$destructPart ${rest}"
            } else {
                trimmed
            }
        }
        
        val isRest = trimmed.startsWith("...")
        val withoutRest = if (isRest) trimmed.substring(3).trim() else trimmed
        val prefix = if (isRest) "..." else ""
        
        val colonIdx = findTopLevelColon(withoutRest)
        
        if (colonIdx < 0) {
            val eqIdx = findDefaultValueEquals(withoutRest)
            if (eqIdx >= 0) {
                val paramName = withoutRest.substring(0, eqIdx).trim()
                val defaultValue = withoutRest.substring(eqIdx + 1).trim()
                return "$prefix$paramName = $defaultValue"
            }
            return trimmed
        }
        
        val paramName = withoutRest.substring(0, colonIdx).trim()
        val afterColon = withoutRest.substring(colonIdx + 1).trim()
        
        val eqIdx = findDefaultValueEquals(afterColon)
        
        return if (eqIdx >= 0) {
            val defaultValue = afterColon.substring(eqIdx + 1).trim()
            "$prefix${paramName.removeSuffix("?")} = $defaultValue"
        } else {
            "$prefix${paramName.removeSuffix("?")}"
        }
    }

    private fun findTopLevelColon(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '<', '{', '[', '(' -> depth++
                '>', '}', ']', ')' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun findDefaultValueEquals(s: String): Int {
        var depth = 0
        var i = 0
        
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '<', '{', '(' -> depth++
                '>', '}', ')' -> depth--
                '[' -> {
                    if (i > 0) {
                        val prev = s[i - 1]
                        if (prev.isLetterOrDigit() || prev == '>' || prev == ']') {
                            if (i + 1 < s.length && s[i + 1] == ']') {
                                i += 2
                                continue
                            }
                        }
                    }
                    depth++
                }
                ']' -> {
                    if (depth > 0) depth--
                }
                '=' -> {
                    val next = if (i + 1 < s.length) s[i + 1] else ' '
                    val nextNext = if (i + 2 < s.length) s[i + 2] else ' '
                    
                    when {
                        next == '>' -> {
                            i += 2
                            continue
                        }
                        next == '=' -> {
                            if (nextNext == '=') {
                                i += 3
                            } else {
                                i += 2
                            }
                            continue
                        }
                        depth == 0 -> {
                            return i
                        }
                    }
                }
                '!' -> {
                    if (i + 1 < s.length && s[i + 1] == '=') {
                        if (i + 2 < s.length && s[i + 2] == '=') {
                            i += 3
                        } else {
                            i += 2
                        }
                        continue
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun convertClassMethodToFunction(code: String): String {
        val trimmed = code.trim()
        
        val classMethodPattern = Regex("""^(async\s+)?(\w+)\s*\(""")
        val match = classMethodPattern.find(trimmed)
        
        if (match != null) {
            val asyncPart = match.groupValues[1]
            
            val startsWithKeyword = trimmed.startsWith("function ") ||
                    trimmed.startsWith("async function ") ||
                    trimmed.startsWith("const ") ||
                    trimmed.startsWith("let ") ||
                    trimmed.startsWith("var ") ||
                    trimmed.startsWith("class ") ||
                    trimmed.startsWith("if ") ||
                    trimmed.startsWith("if(") ||
                    trimmed.startsWith("for ") ||
                    trimmed.startsWith("for(") ||
                    trimmed.startsWith("while ") ||
                    trimmed.startsWith("while(") ||
                    trimmed.startsWith("switch ") ||
                    trimmed.startsWith("switch(") ||
                    trimmed.startsWith("return ") ||
                    trimmed.startsWith("throw ") ||
                    trimmed.startsWith("try ") ||
                    trimmed.startsWith("try{")
            
            if (!startsWithKeyword) {
                return if (asyncPart.isNotBlank()) {
                    "async function ${trimmed.substring(asyncPart.length)}"
                } else {
                    "function $trimmed"
                }
            }
        }
        
        return code
    }

    private fun stripImportsAndExports(code: String): String {
        return code
            .lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("import ") || 
                trimmed.startsWith("const ") && trimmed.contains(" = require(") ||
                trimmed.startsWith("let ") && trimmed.contains(" = require(") ||
                trimmed.startsWith("var ") && trimmed.contains(" = require(")
            }
            .map { line ->
                if (line.trimStart().startsWith("export ")) line.replaceFirst("export ", "")
                else line
            }
            .joinToString("\n")
    }

    private fun extractImportInfo(code: String): List<String> {
        val imports = mutableListOf<String>()
        
        val importRegex = Regex("""import\s+.*?\s+from\s+['"](.+?)['"]""")
        importRegex.findAll(code).forEach { match ->
            imports.add(match.groupValues[1])
        }
        
        val requireRegex = Regex("""require\s*\(\s*['"](.+?)['"]\s*\)""")
        requireRegex.findAll(code).forEach { match ->
            imports.add(match.groupValues[1])
        }
        
        return imports.distinct()
    }

    private fun isFunction(code: String): Boolean {
        val trimmed = code.trim()
        // Check if code is a function definition
        return trimmed.startsWith("function ") ||
               trimmed.startsWith("async function ") ||
               trimmed.matches(Regex("""^const\s+\w+\s*=\s*(?:async\s*)?\(.*\)\s*=>.*""", RegexOption.DOT_MATCHES_ALL)) ||
               trimmed.matches(Regex("""^const\s+\w+\s*=\s*(?:async\s*)?function.*""", RegexOption.DOT_MATCHES_ALL)) ||
               trimmed.matches(Regex("""^let\s+\w+\s*=\s*(?:async\s*)?\(.*\)\s*=>.*""", RegexOption.DOT_MATCHES_ALL)) ||
               trimmed.matches(Regex("""^let\s+\w+\s*=\s*(?:async\s*)?function.*""", RegexOption.DOT_MATCHES_ALL)) ||
               trimmed.matches(Regex("""^var\s+\w+\s*=\s*(?:async\s*)?\(.*\)\s*=>.*""", RegexOption.DOT_MATCHES_ALL)) ||
               trimmed.matches(Regex("""^var\s+\w+\s*=\s*(?:async\s*)?function.*""", RegexOption.DOT_MATCHES_ALL))
    }
}
