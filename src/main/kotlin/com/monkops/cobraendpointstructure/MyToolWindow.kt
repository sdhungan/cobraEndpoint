package com.monkops.cobraendpointstructure

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class CobraEndpointStructureToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = CobraEndpointToolWindowView(project)
        val content = ContentFactory.getInstance().createContent(view.component, null, false)
        toolWindow.contentManager.addContent(content)

        // IMPORTANT: dispose listeners when toolwindow content is disposed
        Disposer.register(content, view)
    }
}

private class CobraEndpointToolWindowView(private val project: Project) : Disposable {

    // ---------------------------
    // Models used by the tree
    // ---------------------------

    private data class NavTarget(val file: VirtualFile, val offset: Int)

    private data class TreeItem(
        val label: String,
        val nav: NavTarget? = null,
        val method: String? = null, // GET/POST/DELETE... (only for leaf nodes)
        val isGroup: Boolean = false
    ) {
        override fun toString(): String = label
    }

    // ---------------------------
    // UI + state
    // ---------------------------

    private enum class Scope(val label: String) {
        CURRENT_FILE("Current file"),
        FILES("Files…"),
        DIRECTORY("Directory…");

        override fun toString(): String = label
    }

    private val root = DefaultMutableTreeNode(TreeItem("Echo Endpoints", isGroup = true))
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 22
        // No custom background/borders here — keep IDE defaults
    }

    // Expanded-by-default, but keep what user collapsed
    private val collapsedGroupKeys = linkedSetOf<String>() // keys are group prefixes like "/templDomain"

    private val scopeCombo = JComboBox(Scope.entries.toTypedArray()).apply {
        selectedItem = Scope.CURRENT_FILE
        toolTipText = "Choose what to scan"
    }

    private val selectionField = JTextField().apply {
        isEditable = false
        columns = 40
        text = "No selection"
        toolTipText = "Current selection"
        // No custom border/background — keep IDE defaults
    }

    private var selectedFiles: List<VirtualFile> = emptyList()
    private var selectedDirectory: VirtualFile? = null

    // auto-refresh (debounced)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var activeFile: VirtualFile? = null
    private var activeDocListener: com.intellij.openapi.editor.event.DocumentListener? = null

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        // Keep default IDE background: don’t force colors; keep panels non-opaque
        isOpaque = false

        add(buildTopPanel(), BorderLayout.NORTH)
        add(buildCenterPanel(), BorderLayout.CENTER)

        installTreeRenderer()
        installTreeExpansionTracking()
        installTreeNavigation()

        // Default selection and initial fill
        resetSelectionToCurrentFile()
        refreshFromSelection()

        // Auto-update
        installEditorSelectionListener()
        attachToCurrentFileDocument()
    }

    // ---------------------------
    // Layout (no custom coloring)
    // ---------------------------

    private fun buildTopPanel(): JPanel {
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buildToolbarRow())
            add(Box.createVerticalStrut(6))
            add(buildSelectionRow())
        }
    }

    private fun buildToolbarRow(): JToolBar {
        return JToolBar().apply {
            isOpaque = false
            isFloatable = false

            add(JLabel("Scope: "))

            add(scopeCombo)
            add(Box.createHorizontalStrut(8))

            add(makeButton("Browse…", AllIcons.General.OpenDisk, "Choose files or a directory") {
                browseForScope()
                attachToCurrentFileDocument()
                scheduleRefresh()
            })

            add(Box.createHorizontalStrut(6))

            add(makeButton("Use current file", AllIcons.Actions.Back, "Use the currently active editor file") {
                scopeCombo.selectedItem = Scope.CURRENT_FILE
                resetSelectionToCurrentFile()
                attachToCurrentFileDocument()
                scheduleRefresh()
            })

            add(Box.createHorizontalGlue())

            add(makeButton("Refresh", AllIcons.Actions.Refresh, "Refresh now") {
                attachToCurrentFileDocument()
                refreshFromSelection()
            })
        }
    }

    private fun buildSelectionRow(): JPanel {
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(JLabel("Selection: "), BorderLayout.WEST)
            add(selectionField, BorderLayout.CENTER)
        }
    }

    private fun buildCenterPanel(): JPanel {
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false

            add(JLabel("Echo Endpoints").apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
            }, BorderLayout.WEST)

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false

                add(iconButton(AllIcons.Actions.Expandall, "Expand all") {
                    collapsedGroupKeys.clear()
                    TreeUtil.expandAll(tree)
                })

                add(iconButton(AllIcons.Actions.Collapseall, "Collapse all") {
                    collapsedGroupKeys.clear()
                    rememberAllGroupsAsCollapsed()
                    TreeUtil.collapseAll(tree, 0)
                })
            }

            add(right, BorderLayout.EAST)
        }

        center.add(header, BorderLayout.NORTH)
        center.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        return center
    }

    private fun makeButton(text: String, icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JButton {
        return JButton(text, icon).apply {
            toolTipText = tooltip
            horizontalAlignment = SwingConstants.LEFT

            // Make it look like native toolbar action
            setBorderPainted(false)
            setContentAreaFilled(false)
            setFocusPainted(false)
            isOpaque = false

            addActionListener { onClick() }
        }
    }

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JButton {
        return JButton(icon).apply {
            isOpaque = false
            toolTipText = tooltip

            setBorderPainted(false)
            setContentAreaFilled(false)
            setFocusPainted(false)

            horizontalAlignment = SwingConstants.CENTER
            addActionListener { onClick() }
        }
    }


    // ---------------------------
    // Tree renderer (icons only; no colors)
    // ---------------------------

    private fun installTreeRenderer() {
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree?,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): java.awt.Component {
                val c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

                val node = value as? DefaultMutableTreeNode
                val item = node?.userObject as? TreeItem

                icon = when {
                    item?.isGroup == true -> AllIcons.General.Groups
                    item?.method != null -> methodIcon(item.method)
                    else -> AllIcons.Nodes.Unknown
                }


                return c
            }
        }
    }

    // Use a “node” style icon for groups (not a file/folder)
    // This is a good “group/container” symbol in JetBrains icon set.
    private fun groupIcon(): javax.swing.Icon = AllIcons.Nodes.Class

    private fun methodIcon(method: String): javax.swing.Icon {
        return when (method.uppercase()) {
            "GET" -> AllIcons.Actions.Find
            "POST" -> AllIcons.Actions.Upload
            "PUT" -> AllIcons.Actions.Edit
            "PATCH" -> AllIcons.Actions.EditSource
            "DELETE" -> AllIcons.Actions.GC
            "OPTIONS" -> AllIcons.Actions.Properties
            "HEAD" -> AllIcons.Actions.Preview
            else -> AllIcons.Nodes.Unknown
        }
    }

    // Update renderer to use groupIcon()
    private fun fixGroupIconInRenderer() {
        // Not needed; kept for clarity.
    }

    // ---------------------------
    // User collapse intent tracking
    // ---------------------------

    private fun installTreeExpansionTracking() {
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                val key = groupKeyFromPath(event.path) ?: return
                collapsedGroupKeys.remove(key)
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                val key = groupKeyFromPath(event.path) ?: return
                collapsedGroupKeys.add(key)
            }
        })
    }

    private fun groupKeyFromPath(path: TreePath?): String? {
        if (path == null) return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val item = node.userObject as? TreeItem ?: return null
        if (!item.isGroup) return null
        val parent = node.parent as? DefaultMutableTreeNode ?: return null
        if (parent != root) return null
        return item.label
    }

    private fun rememberAllGroupsAsCollapsed() {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val item = child.userObject as? TreeItem ?: continue
            if (item.isGroup) collapsedGroupKeys.add(item.label)
        }
    }

    private fun applyUserCollapses() {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val item = child.userObject as? TreeItem ?: continue
            if (!item.isGroup) continue
            if (!collapsedGroupKeys.contains(item.label)) continue
            tree.collapsePath(TreePath(child.path))
        }
    }

    // ---------------------------
    // Navigation: double-click / Enter
    // ---------------------------

    private fun installTreeNavigation() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    navigateFromSelectedNode()
                }
            }
        })

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    navigateFromSelectedNode()
                    e.consume()
                }
            }
        })
    }

    private fun navigateFromSelectedNode() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val item = node.userObject as? TreeItem ?: return
        val target = item.nav ?: return

        val doc = FileDocumentManager.getInstance().getDocument(target.file)
        val safeOffset = if (doc != null) target.offset.coerceIn(0, doc.textLength) else target.offset
        OpenFileDescriptor(project, target.file, safeOffset).navigate(true)
    }

    // ---------------------------
    // Scope selection
    // ---------------------------

    private fun browseForScope() {
        when (scopeCombo.selectedItem as Scope) {
            Scope.CURRENT_FILE -> resetSelectionToCurrentFile()

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
                    .withDescription("Select a folder (directory scanning comes later).")

                val chosen = FileChooser.chooseFile(descriptor, project, null)
                selectedDirectory = chosen
                selectedFiles = emptyList()
                selectionField.text = chosen?.presentableUrl ?: "No directory selected"
            }
        }
    }

    private fun resetSelectionToCurrentFile() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val vf = editor?.document?.let { doc -> FileDocumentManager.getInstance().getFile(doc) }

        selectedFiles = listOfNotNull(vf)
        selectedDirectory = null
        selectionField.text = vf?.presentableUrl ?: "No active file"
    }

    // ---------------------------
    // Auto-refresh on selection + edits
    // ---------------------------

    private fun installEditorSelectionListener() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val scope = scopeCombo.selectedItem as Scope
                    if (scope != Scope.CURRENT_FILE) return

                    resetSelectionToCurrentFile()
                    attachToCurrentFileDocument()
                    scheduleRefresh()
                }
            }
        )
    }

    private fun attachToCurrentFileDocument() {
        val prevDoc = activeFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        val prevListener = activeDocListener
        if (prevDoc != null && prevListener != null) {
            prevDoc.removeDocumentListener(prevListener)
        }
        activeDocListener = null
        activeFile = null

        val scope = scopeCombo.selectedItem as Scope
        if (scope != Scope.CURRENT_FILE) return

        val vf = selectedFiles.firstOrNull() ?: return
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return

        activeFile = vf
        val listener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                scheduleRefresh()
            }
        }
        activeDocListener = listener
        doc.addDocumentListener(listener, this)
    }

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshFromSelection() }, 200)
    }

    // ---------------------------
    // Build tree from parsed file + keep UX rules
    // ---------------------------

    private fun refreshFromSelection() {
        val selection = tree.selectionPath

        root.removeAllChildren()

        val vf = selectedFiles.firstOrNull()
        if (vf == null) {
            root.add(DefaultMutableTreeNode(TreeItem("No active file")))
            model.reload()
            TreeUtil.expandAll(tree)
            return
        }

        // Unsaved edits: read from Document first
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        val text = doc?.text ?: VfsUtilCore.loadText(vf)

        val parsed = EchoMvpParser.parse(text)

        if (parsed.routes.isEmpty()) {
            root.add(DefaultMutableTreeNode(TreeItem("No Echo routes found in: ${vf.name}")))
            model.reload()
            TreeUtil.expandAll(tree)
            return
        }

        val grouped = parsed.routes
            .groupBy { it.groupPrefix }
            .toSortedMap(compareBy { it })

        for ((groupPrefix, groupRoutes) in grouped) {
            val groupOffset = parsed.groupsByPrefix[groupPrefix]?.offset
                ?: groupRoutes.minOfOrNull { it.offset }
                ?: 0

            val groupNode = DefaultMutableTreeNode(
                TreeItem(
                    label = groupPrefix,
                    nav = NavTarget(vf, groupOffset),
                    isGroup = true
                )
            )

            val sorted = groupRoutes.sortedWith(
                compareBy<EchoMvpParser.Route> { it.method }.thenBy { it.path }
            )

            for (r in sorted) {
                groupNode.add(
                    DefaultMutableTreeNode(
                        TreeItem(
                            label = "${r.method} ${r.path}",
                            nav = NavTarget(vf, r.offset),
                            method = r.method,
                            isGroup = false
                        )
                    )
                )
            }

            root.add(groupNode)
        }

        model.reload()

        // Default behavior: expand everything...
        TreeUtil.expandAll(tree)
        // ...then apply only what user collapsed
        applyUserCollapses()

        if (selection != null) tree.selectionPath = selection
    }

    // ---------------------------
    // Disposal
    // ---------------------------

    override fun dispose() {
        val prevDoc = activeFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        val prevListener = activeDocListener
        if (prevDoc != null && prevListener != null) {
            prevDoc.removeDocumentListener(prevListener)
        }
        activeDocListener = null
        activeFile = null
    }

    // =========================================================================
    // MVP parser (offset-based) — kept here so you only maintain one file.
    //
    // Supports:
    //   templDomain := e.Group("/templDomain")
    //   v1 := templDomain.Group("/v1")
    //   templDomain.GET("/info", ...)
    // =========================================================================

    private object EchoMvpParser {

        data class Group(val varName: String, val prefix: String, val offset: Int)
        data class Route(val groupVar: String, val groupPrefix: String, val method: String, val path: String, val offset: Int)
        data class Result(val groupsByPrefix: Map<String, Group>, val routes: List<Route>)

        private val groupAssignRegex =
            Regex("""\b(\w+)\s*:=\s*(\w+)\.Group\(\s*"([^"]+)"\s*\)""")

        private val routeRegex =
            Regex("""\b(\w+)\.(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\(\s*"([^"]+)"""") // stops after the first string

        fun parse(text: String): Result {
            val groupPrefixByVar = mutableMapOf<String, String>()
            val groupByPrefix = linkedMapOf<String, Group>()

            repeat(3) {
                for (m in groupAssignRegex.findAll(text)) {
                    val childVar = m.groupValues[1]
                    val parentVar = m.groupValues[2]
                    val childPart = m.groupValues[3]
                    val offset = m.range.first

                    val parentPrefix = groupPrefixByVar[parentVar] ?: ""
                    val full = normalizePath("$parentPrefix/$childPart")

                    groupPrefixByVar[childVar] = full
                    groupByPrefix.putIfAbsent(full, Group(childVar, full, offset))
                }
            }

            val routes = mutableListOf<Route>()
            for (m in routeRegex.findAll(text)) {
                val groupVar = m.groupValues[1]
                val method = m.groupValues[2]
                val path = m.groupValues[3]
                val offset = m.range.first

                val prefix = groupPrefixByVar[groupVar] ?: "/"
                routes += Route(
                    groupVar = groupVar,
                    groupPrefix = if (prefix.isBlank()) "/" else prefix,
                    method = method,
                    path = normalizePath(path),
                    offset = offset
                )
            }

            return Result(groupsByPrefix = groupByPrefix, routes = routes)
        }

        private fun normalizePath(p: String): String {
            var s = p.trim()
            if (!s.startsWith("/")) s = "/$s"
            s = s.replace(Regex("""/+"""), "/")
            if (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
            return s
        }
    }
}
