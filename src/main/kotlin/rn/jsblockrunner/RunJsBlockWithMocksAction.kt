package rn.jsblockrunner

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.util.PsiTreeUtil
import java.io.File
import java.nio.charset.StandardCharsets

class RunJsBlockWithMocksAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (editor == null || psiFile == null) {
            Messages.showErrorDialog(project, "Open a JavaScript/TypeScript file in the editor first.", "JS Block Runner")
            return
        }

        val selectionModel = editor.selectionModel
        val hasSelection = selectionModel.hasSelection()
        val caretOffset = editor.caretModel.offset

        // Determine the code to run and function info
        val funcInfo: EnclosingFunctionInfo?
        val snippetText: String
        
        if (hasSelection) {
            val selectedText = selectionModel.selectedText?.trim().orEmpty()
            snippetText = selectedText
            funcInfo = JsPsiHelper.extractFunctionInfoFromText(selectedText)
        } else {
            funcInfo = JsPsiHelper.findEnclosingFunctionInfo(psiFile, caretOffset)
            snippetText = funcInfo?.text?.trim().orEmpty().ifBlank { psiFile.text }
        }

        if (snippetText.isBlank()) {
            Messages.showInfoMessage(project, "No code found to run.", "JS Block Runner")
            return
        }

        // Detect external calls (functions)
        val paramNames = funcInfo?.paramNames?.toSet() ?: emptySet()
        val localDefinitions = JsPsiHelper.extractLocalDefinitions(snippetText)
        val allLocalVars = paramNames + localDefinitions
        
        val externalCalls = if (!hasSelection && funcInfo != null) {
            val el = psiFile.findElementAt(caretOffset)
            val enclosingElement = PsiTreeUtil.getParentOfType(el, JSFunction::class.java)
            if (enclosingElement != null) {
                JsPsiHelper.detectExternalCallKeysForFunction(enclosingElement)
            } else {
                JsPsiHelper.detectExternalCallKeysByRegex(snippetText, paramNames)
            }
        } else {
            JsPsiHelper.detectExternalCallKeysByRegex(snippetText, paramNames)
        }
            .filterNot { it in JsPsiHelper.DEFAULT_ALLOWLIST }
            // Filter out method calls on local variables (params, const, let, var)
            .filterNot { key -> 
                val baseObject = key.split(".").firstOrNull() ?: ""
                baseObject in allLocalVars
            }
        
        // Detect 'this.' references that need to be mocked
        val thisReferences = JsPsiHelper.detectThisReferences(snippetText)
        
        // Detect external constants/variables (UPPER_CASE and PascalCase)
        val externalConstants = JsPsiHelper.detectExternalConstantsByRegex(snippetText)
        val filteredConstants = externalConstants
            .filterNot { it in allLocalVars }
            .filterNot { const -> 
                externalCalls.any { call -> call.startsWith("$const.") || call == const }
            }
        
        // Detect external variables used as arguments (camelCase like authUrl, requestHeaders)
        val externalVariables = JsPsiHelper.detectExternalVariablesByRegex(snippetText, allLocalVars)
            .filterNot { it in externalCalls } // Don't duplicate function calls
        
        val allExternalRefs = (externalCalls + filteredConstants + thisReferences + externalVariables).sorted()

        // Find Node.js early to fail fast
        val nodePath = NodeLocator.findNode(project)
        if (nodePath == null) {
            Messages.showErrorDialog(project, "Node executable not found on PATH.", "Node Not Found")
            return
        }

        // Create the run callback
        val runCallback: (RunDialogResult) -> Unit = { dialogResult ->
            // Generate runner file
            val runnerFile = RunnerGenerator.writeTempRunner(
                project = project,
                userCode = snippetText,
                enabledMocks = dialogResult.enabledMocks,
                functionName = funcInfo?.name,
                functionArgs = if (funcInfo != null && funcInfo.paramNames.isNotEmpty()) dialogResult.argExprs else emptyList()
            )

            if (dialogResult.debugMode) {
                runNodeInDebugMode(project, nodePath, runnerFile)
            } else {
                runNodeInConsole(project, nodePath, runnerFile)
            }
        }

        // Show dialog with run callback
        val dialog = RunDialog(
            project = project,
            functionParamNames = funcInfo?.paramNames ?: emptyList(),
            mockKeys = allExternalRefs,
            onRun = runCallback
        )
        
        // Show the dialog (it handles its own buttons now)
        dialog.show()
    }

    private fun runNodeInConsole(project: Project, nodePath: String, runnerFile: File) {
        val cmd = GeneralCommandLine(nodePath, runnerFile.absolutePath)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(project.basePath ?: runnerFile.parentFile.absolutePath)

        val processHandler = OSProcessHandler(cmd)
        val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(processHandler)

        val descriptor = RunContentDescriptor(console, processHandler, console.component, "JS Block Runner")
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        console.print("Running: ${cmd.commandLineString}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        processHandler.startNotify()
    }

    private fun runNodeInDebugMode(project: Project, nodePath: String, runnerFile: File) {
        // Use --inspect-brk to pause at start so debugger can attach
        val cmd = GeneralCommandLine(nodePath, "--inspect-brk", runnerFile.absolutePath)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(project.basePath ?: runnerFile.parentFile.absolutePath)

        val processHandler = OSProcessHandler(cmd)
        val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(processHandler)

        val descriptor = RunContentDescriptor(console, processHandler, console.component, "JS Block Runner (Debug)")
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        // Print debug instructions
        console.print("=".repeat(63) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("DEBUG MODE\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("=".repeat(63) + "\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        
        console.print("Option 1: IntelliJ IDEA (Recommended)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("  1. Run > Attach to Node.js/Chrome\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("  2. Set Host: localhost, Port: 9229\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("  3. Click Attach\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("  4. Press Resume (F9) to run to your 'debugger;' statement\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        
        console.print("Option 2: Chrome DevTools\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("  1. Open Chrome: chrome://inspect\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("  2. Click 'inspect' under Remote Target\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("  3. Press F8 (Resume) to run to your 'debugger;' statement\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        
        console.print("Tip: Add 'debugger;' in your code where you want to pause\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("=".repeat(63) + "\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("Running: ${cmd.commandLineString}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        
        processHandler.startNotify()
    }
}
