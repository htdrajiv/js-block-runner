package rn.jsblockrunner

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

data class RunDialogResult(
    val enabledMocks: Map<String, String?>,
    val argExprs: List<String>,
    val debugMode: Boolean = false
)

class RunDialog(
    private val project: Project,
    private val functionParamNames: List<String>,
    private val mockKeys: List<String>,
    private val onRun: ((RunDialogResult) -> Unit)? = null
) : DialogWrapper(project, true) {

    private data class MockRow(val enabled: JCheckBox, val field: JBTextField, val templateCombo: ComboBox<String>)

    private val argFields = mutableListOf<Pair<JBTextField, ComboBox<String>>>()
    private val mockRows = linkedMapOf<String, MockRow>()
    private var runCount = 0
    private lateinit var debugCheckbox: JCheckBox

    companion object {
        val MOCK_TEMPLATES = listOf(
            "" to "Custom...",
            "undefined" to "undefined",
            "null" to "null",
            "true" to "true",
            "false" to "false",
            "42" to "Number (42)",
            "\"test\"" to "String (\"test\")",
            "[]" to "Empty Array",
            "({})" to "Empty Object",
            "({id: 1, name: \"test\"})" to "Sample Object",
            "[{id: 1}, {id: 2}]" to "Array of Objects",
            "() => undefined" to "Function → undefined",
            "() => ({})" to "Function → {}",
            "() => []" to "Function → []",
            "(...args) => ({args})" to "Function → {args}",
            "async () => ({})" to "Async → {}",
            "async (id) => ({id, name: `Item \${id}`})" to "Async → {id, name}",
            "async () => { throw new Error('Mock error') }" to "Async → throws Error",
            "Promise.resolve({})" to "Promise.resolve({})",
            "Promise.reject(new Error('fail'))" to "Promise.reject(Error)"
        )

        val ARG_TEMPLATES = listOf(
            "" to "Custom...",
            "undefined" to "undefined",
            "null" to "null",
            "true" to "true",
            "false" to "false",
            "1" to "Number (1)",
            "\"test\"" to "String (\"test\")",
            "[]" to "Empty Array",
            "({})" to "Empty Object",
            "({id: 1})" to "Object {id: 1}",
            "({limit: 10, offset: 0})" to "Pagination Object",
            "[1, 2, 3]" to "Number Array",
            "[\"a\", \"b\"]" to "String Array",
            "new Date().toISOString()" to "ISO Date String",
            "() => {}" to "Callback (noop)"
        )
    }

    init {
        title = "Run JS Block with Mocks"
        isModal = false  // Make dialog non-modal so console updates are visible
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // Help text
        val helpPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        helpPanel.add(JLabel("<html><b>Tips:</b> Plain text (like emails) will be auto-quoted. " +
                "Objects need parentheses: <code>({key: value})</code>. " +
                "Functions: <code>() => result</code></html>"))
        panel.add(helpPanel)
        panel.add(Box.createVerticalStrut(10))

        // Args section
        if (functionParamNames.isNotEmpty()) {
            val argsHeader = JLabel("<html><b>Function Arguments</b></html>")
            argsHeader.border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
            panel.add(argsHeader)

            functionParamNames.forEach { paramName ->
                val row = JPanel(BorderLayout(8, 0))
                
                val label = JLabel(paramName)
                label.preferredSize = Dimension(150, 24)

                val templateCombo = ComboBox(ARG_TEMPLATES.map { it.second }.toTypedArray())
                templateCombo.preferredSize = Dimension(180, 24)

                val field = JBTextField()
                field.emptyText.text = "JS expression (blank => undefined)"

                templateCombo.addActionListener {
                    val idx = templateCombo.selectedIndex
                    if (idx > 0) {
                        field.text = ARG_TEMPLATES[idx].first
                    }
                }

                argFields.add(field to templateCombo)

                val leftPanel = JPanel(BorderLayout(4, 0))
                leftPanel.add(label, BorderLayout.WEST)
                leftPanel.add(templateCombo, BorderLayout.EAST)

                row.add(leftPanel, BorderLayout.WEST)
                row.add(field, BorderLayout.CENTER)
                row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
                panel.add(row)
            }

            panel.add(Box.createVerticalStrut(16))
        }

        // Mocks section
        val mocksHeader = JLabel("<html><b>External Dependencies</b> <i>(functions & constants - check to enable)</i></html>")
        mocksHeader.border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        panel.add(mocksHeader)

        if (mockKeys.isEmpty()) {
            panel.add(JLabel("<html><i>No external dependencies detected in this code block.</i></html>"))
        } else {
            // "Enable All" / "Disable All" buttons
            val bulkPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            val enableAllBtn = JButton("Enable All")
            val disableAllBtn = JButton("Disable All")
            
            enableAllBtn.addActionListener {
                mockRows.values.forEach { row ->
                    row.enabled.isSelected = true
                    row.field.isEnabled = true
                    row.templateCombo.isEnabled = true
                }
            }
            disableAllBtn.addActionListener {
                mockRows.values.forEach { row ->
                    row.enabled.isSelected = false
                    row.field.isEnabled = false
                    row.templateCombo.isEnabled = false
                }
            }
            
            bulkPanel.add(enableAllBtn)
            bulkPanel.add(Box.createHorizontalStrut(8))
            bulkPanel.add(disableAllBtn)
            panel.add(bulkPanel)
            panel.add(Box.createVerticalStrut(8))

            mockKeys.forEach { key ->
                val row = JPanel(BorderLayout(8, 0))

                val enabled = JCheckBox()
                enabled.isSelected = false

                val label = JLabel(key)
                label.preferredSize = Dimension(200, 24)
                label.toolTipText = key

                val templateCombo = ComboBox(MOCK_TEMPLATES.map { it.second }.toTypedArray())
                templateCombo.preferredSize = Dimension(180, 24)
                templateCombo.isEnabled = false

                val field = JBTextField()
                field.emptyText.text = "Return value or mock function"
                field.isEnabled = false

                // Smart default based on key name
                val smartDefault = suggestMockTemplate(key)
                if (smartDefault != null) {
                    field.text = smartDefault
                }

                templateCombo.addActionListener {
                    val idx = templateCombo.selectedIndex
                    if (idx > 0) {
                        field.text = MOCK_TEMPLATES[idx].first
                    }
                }

                enabled.addActionListener {
                    field.isEnabled = enabled.isSelected
                    templateCombo.isEnabled = enabled.isSelected
                }

                val leftPanel = JPanel(BorderLayout(4, 0))
                leftPanel.add(enabled, BorderLayout.WEST)
                leftPanel.add(label, BorderLayout.CENTER)

                val rightPanel = JPanel(BorderLayout(4, 0))
                rightPanel.add(templateCombo, BorderLayout.WEST)
                rightPanel.add(field, BorderLayout.CENTER)

                row.add(leftPanel, BorderLayout.WEST)
                row.add(rightPanel, BorderLayout.CENTER)
                row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)

                mockRows[key] = MockRow(enabled, field, templateCombo)
                panel.add(row)
            }
        }

        val scroll = JBScrollPane(panel)
        scroll.preferredSize = Dimension(950, 550)

        root.add(scroll, BorderLayout.CENTER)

        return root
    }

    override fun createSouthPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Left side: Run count and debug checkbox
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        
        val runCountLabel = JLabel("Runs: 0")
        leftPanel.add(runCountLabel)
        
        leftPanel.add(Box.createHorizontalStrut(20))
        
        // Debug checkbox
        debugCheckbox = JCheckBox("🐛 Debug (chrome://inspect)")
        debugCheckbox.toolTipText = "Run with Node.js inspector. Open chrome://inspect to debug."
        leftPanel.add(debugCheckbox)
        
        panel.add(leftPanel, BorderLayout.WEST)
        
        // Buttons on the right
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        
        // Run button (doesn't close dialog)
        val runButton = JButton("▶ Run")
        runButton.toolTipText = "Run the code without closing this dialog (keeps mock values)"
        runButton.addActionListener {
            runCount++
            runCountLabel.text = "Runs: $runCount"
            onRun?.invoke(getResult())
        }
        
        // Run & Close button (default OK behavior)
        val runAndCloseButton = JButton("Run & Close")
        runAndCloseButton.toolTipText = "Run the code and close this dialog"
        runAndCloseButton.addActionListener {
            onRun?.invoke(getResult())
            close(OK_EXIT_CODE)
        }
        
        // Close button
        val closeButton = JButton("Close")
        closeButton.addActionListener {
            close(CANCEL_EXIT_CODE)
        }
        
        buttonPanel.add(runButton)
        buttonPanel.add(Box.createHorizontalStrut(8))
        buttonPanel.add(runAndCloseButton)
        buttonPanel.add(Box.createHorizontalStrut(8))
        buttonPanel.add(closeButton)
        
        panel.add(buttonPanel, BorderLayout.EAST)
        panel.border = BorderFactory.createEmptyBorder(8, 0, 8, 10)
        
        return panel
    }

    // Override to prevent default OK/Cancel buttons
    override fun createActions(): Array<Action> {
        return emptyArray()
    }

    private fun suggestMockTemplate(key: String): String? {
        val lower = key.lowercase()
        
        if (key.matches(Regex("""^[A-Z][A-Z0-9_]+$"""))) {
            return when {
                lower.contains("prefix") -> "({SP: \"sp:\", WORKSPACE: \"w:\", ACCOUNT: \"a:\"})"
                lower.contains("scope") -> "({SP: \"sp:\", WORKSPACE: \"w:\", ACCOUNT: \"a:\"})"
                lower.contains("type") -> "({DEFAULT: \"default\", CUSTOM: \"custom\"})"
                lower.contains("status") -> "({ACTIVE: \"active\", INACTIVE: \"inactive\", PENDING: \"pending\"})"
                lower.contains("role") -> "({ADMIN: \"admin\", USER: \"user\", GUEST: \"guest\"})"
                lower.contains("config") -> "({enabled: true, timeout: 5000})"
                lower.contains("option") -> "({DEFAULT: \"default\"})"
                lower.contains("error") -> "({NOT_FOUND: \"NOT_FOUND\", UNAUTHORIZED: \"UNAUTHORIZED\"})"
                lower.contains("event") -> "({CLICK: \"click\", CHANGE: \"change\"})"
                lower.contains("key") -> "({API_KEY: \"test-key\", SECRET: \"test-secret\"})"
                lower.contains("url") || lower.contains("endpoint") -> "({BASE: \"http://localhost\", API: \"/api\"})"
                lower.contains("auth") -> "\"local-auth-workspace\""
                else -> "({KEY: \"value\"})"
            }
        }
        
        if (key.matches(Regex("""^[A-Z][a-zA-Z0-9]+$""")) && !key.contains(".")) {
            return "({VALUE: \"value\"})"
        }
        
        return when {
            lower.contains("fetch") || lower.contains("get") || lower.contains("load") -> 
                "async () => ({})"
            lower.contains("save") || lower.contains("create") || lower.contains("post") || lower.contains("update") -> 
                "async (data) => ({...data, id: 1})"
            lower.contains("delete") || lower.contains("remove") -> 
                "async () => ({success: true})"
            lower.contains("find") -> 
                "async () => []"
            lower.contains("count") -> 
                "async () => 0"
            lower.contains("exists") || lower.contains("has") || lower.contains("is") -> 
                "() => true"
            lower.contains("validate") || lower.contains("check") -> 
                "() => ({valid: true})"
            lower.contains("parse") || lower.contains("transform") -> 
                "(data) => data"
            lower.contains("log") || lower.contains("print") || lower.contains("debug") -> 
                "() => {}"
            else -> null
        }
    }

    fun getResult(): RunDialogResult {
        val args = functionParamNames.indices.map { idx ->
            val t = argFields[idx].first.text.trim()
            if (t.isBlank()) "undefined" else t
        }

        val enabledMocks = buildMap<String, String?> {
            for ((key, row) in mockRows) {
                if (!row.enabled.isSelected) continue
                val expr = row.field.text.trim()
                put(key, if (expr.isBlank()) null else expr)
            }
        }

        return RunDialogResult(
            enabledMocks = enabledMocks, 
            argExprs = args,
            debugMode = debugCheckbox.isSelected
        )
    }
}
