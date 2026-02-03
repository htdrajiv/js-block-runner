package rn.jsblockrunner

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class MockValuesDialog(
    project: Project,
    private val keys: List<String>
) : DialogWrapper(project) {

    private val fields = linkedMapOf<String, JBTextField>()

    init {
        title = "Mock External Calls"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val hint = JLabel("Enter JSON or JS expression to return for each call. Example: {\"ok\": true} or 'hello' or 123 or (...args)=>({args})")
        hint.border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
        panel.add(hint)

        keys.forEach { key ->
            val row = JPanel(BorderLayout(8, 0))
            val label = JLabel(key)
            label.preferredSize = Dimension(260, 24)

            val field = JBTextField()
            field.emptyText.text = "JS value or function (e.g. {\"id\":1} or (...args)=>({args}) )"
            fields[key] = field

            row.add(label, BorderLayout.WEST)
            row.add(field, BorderLayout.CENTER)
            row.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
            panel.add(row)
        }

        val scroll = JBScrollPane(panel)
        scroll.preferredSize = Dimension(820, 420)
        return scroll
    }

    fun getMockJsonByKey(): Map<String, String> {
        return fields.mapValues { it.value.text.trim() }.filterValues { it.isNotBlank() }
    }
}
