package com.monkops.cobraendpointstructure

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.JTextField
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class CobraEndpointStructureToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = CobraEndpointToolWindowView(project)
        val content = ContentFactory.getInstance().createContent(view.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private class CobraEndpointToolWindowView(private val project: Project) {

    private enum class Scope(val label: String) {
        CURRENT_FILE("Current file"),
        FILES("Files…"),
        DIRECTORY("Directory…");

        override fun toString(): String = label
    }

    private val root = DefaultMutableTreeNode("Echo Endpoints")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    private val scopeCombo = JComboBox(Scope.entries.toTypedArray()).apply {
        selectedItem = Scope.CURRENT_FILE
    }

    private val selectionField = JTextField().apply {
        isEditable = false
        columns = 40
        text = "No selection"
        toolTipText = "Shows the current selection"
    }

    private var selectedFiles: List<VirtualFile> = emptyList()
    private var selectedDirectory: VirtualFile? = null

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(buildToolbarPanel(), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        // Default: current open file (if any)
        resetSelectionToCurrentFile()
        refreshFromSelection()
    }

    private fun buildToolbarPanel(): JPanel {
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Row 1: scope + browse + actions
        val row1 = JToolBar().apply {
            isFloatable = false

            add(JLabel("Scope: "))
            add(scopeCombo)

            add(Box.createHorizontalStrut(8))

            add(JButton("Browse…").apply {
                addActionListener { browseForScope() }
            })

            add(Box.createHorizontalStrut(8))

            add(JButton("Use current file").apply {
                addActionListener {
                    scopeCombo.selectedItem = Scope.CURRENT_FILE
                    resetSelectionToCurrentFile()
                    refreshFromSelection()
                }
            })

            add(Box.createHorizontalGlue())

            add(JButton("Refresh").apply {
                addActionListener { refreshFromSelection() }
            })
        }

        // Row 2: selection display
        val row2 = JToolBar().apply {
            isFloatable = false
            add(JLabel("Selection: "))
            add(selectionField)
        }

        top.add(row1)
        top.add(row2)
        return top
    }

    private fun browseForScope() {
        when (scopeCombo.selectedItem as Scope) {
            Scope.CURRENT_FILE -> {
                resetSelectionToCurrentFile()
            }

            Scope.FILES -> {
                val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                    .withTitle("Select Go file(s)")
                    .withDescription("Select one or more Go files to scan for Echo endpoints.")

                val chosen = FileChooser.chooseFiles(descriptor, project, null)
                selectedFiles = chosen.toList()
                selectedDirectory = null

                selectionField.text =
                    if (selectedFiles.isEmpty()) "No files selected"
                    else selectedFiles.joinToString(separator = "; ") { it.presentableUrl }
            }

            Scope.DIRECTORY -> {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select a directory")
                    .withDescription("Select a folder. (Later we’ll scan all Go files inside it.)")

                val chosen = FileChooser.chooseFile(descriptor, project, null)
                selectedDirectory = chosen
                selectedFiles = emptyList()

                selectionField.text = chosen?.presentableUrl ?: "No directory selected"
            }
        }

        refreshFromSelection()
    }

    private fun resetSelectionToCurrentFile() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val vf = editor?.document?.let { doc ->
            FileDocumentManager.getInstance().getFile(doc)
        }

        selectedFiles = listOfNotNull(vf)
        selectedDirectory = null

        selectionField.text = vf?.presentableUrl ?: "No active file"
    }

    private fun refreshFromSelection() {
        root.removeAllChildren()

        // For now: ONLY parse the current file (your current MVP goal)
        val vf = selectedFiles.firstOrNull()
        if (vf == null) {
            root.add(DefaultMutableTreeNode("No active file"))
            model.reload()
            tree.expandRow(0)
            return
        }

        val text = com.intellij.openapi.vfs.VfsUtilCore.loadText(vf)
        val routes = EchoRouteParser.parse(text)

        if (routes.isEmpty()) {
            root.add(DefaultMutableTreeNode("No Echo routes found in: ${vf.name}"))
            model.reload()
            tree.expandRow(0)
            return
        }

        // group routes by groupPrefix and sort for stable tree
        val grouped = routes
            .groupBy { it.groupPrefix }
            .toSortedMap(compareBy { it })

        for ((groupPrefix, groupRoutes) in grouped) {
            val groupNode = DefaultMutableTreeNode(groupPrefix)
            val sortedRoutes = groupRoutes.sortedWith(
                compareBy<EchoRoute> { it.method }.thenBy { it.path }
            )

            for (r in sortedRoutes) {
                groupNode.add(DefaultMutableTreeNode("${r.method} ${r.path}"))
            }
            root.add(groupNode)
        }

        model.reload()
        tree.expandRow(0)
    }

}
