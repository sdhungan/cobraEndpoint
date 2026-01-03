package com.monkops.cobraendpointstructure

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class CobraEndpointStructureToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = CobraEndpointToolWindowView(project)
        val content = ContentFactory.getInstance().createContent(view.component, null, false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(content, view)
    }
}

private class CobraEndpointToolWindowView(private val project: Project) : Disposable {

    private data class NavTarget(val file: VirtualFile, val offset: Int)
    private enum class NodeKind { FILE, GROUP, ROUTE, INFO }

    private data class TreeItem(
        val label: String,
        val kind: NodeKind,
        val nav: NavTarget? = null,
        val method: String? = null
    ) {
        override fun toString(): String = label
    }

    private enum class SelectionMode { CURRENT_FILE, CUSTOM }

    private data class FileStamp(
        val docStamp: Long?,
        val vfsStamp: Long
    )

    private data class CachedFile(
        val stamp: FileStamp,
        val hasRoutes: Boolean,
        val parsed: EchoParseResult?
    )

    private var selectionMode: SelectionMode = SelectionMode.CURRENT_FILE
    private var selectedRoot: VirtualFile? = null

    private val fileCache = HashMap<VirtualFile, CachedFile>(128)
    private val fileNodeByVf = HashMap<VirtualFile, DefaultMutableTreeNode>(128)

    private val root = DefaultMutableTreeNode(TreeItem("Echo Endpoints", NodeKind.INFO))
    private val model = DefaultTreeModel(root)

    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0

        isOpaque = false
        background = UIUtil.getTreeBackground()
        foreground = UIUtil.getTreeForeground()

        TreeUIHelper.getInstance().installTreeSpeedSearch(this)
        TreeUtil.installActions(this)
    }

    private val selectionField = JBTextField().apply {
        isEditable = false
        text = "No selection"
        toolTipText = "Current selection"
        isOpaque = false
    }

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false

        add(buildTopPanel(), BorderLayout.NORTH)
        add(buildCenterPanel(), BorderLayout.CENTER)

        installTreeRenderer()
        installTreeNavigation()
        installAutoRefresh()

        useCurrentFile()
    }

    private fun buildTopPanel(): JPanel {
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buildToolbarRow())
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(buildSelectionRow())
        }
    }

    /**
     * Two-row toolbar: if the tool window becomes narrow, the right-side toolbar
     * (expand/collapse) moves to a second row to avoid cramped layouts.
     */
    private fun buildToolbarRow(): JComponent {
        val root = NonOpaquePanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 4)
        }

        val topRow = NonOpaquePanel(BorderLayout())
        val bottomRow = NonOpaquePanel(BorderLayout())

        val browseAction = object : DumbAwareAction(
            "Browse…",
            "Choose a Go file or a directory",
            AllIcons.General.OpenDisk
        ) {
            override fun actionPerformed(e: AnActionEvent) = browse()
        }.apply {
            templatePresentation.putClientProperty(
                com.intellij.openapi.actionSystem.ex.ActionUtil.SHOW_TEXT_IN_TOOLBAR,
                true
            )
        }

        val useCurrentFileAction = object : DumbAwareAction(
            "Use current file",
            "Reset to the currently active editor file",
            AllIcons.Actions.Annotate
        ) {
            override fun actionPerformed(e: AnActionEvent) = useCurrentFile()
        }.apply {
            templatePresentation.putClientProperty(
                com.intellij.openapi.actionSystem.ex.ActionUtil.SHOW_TEXT_IN_TOOLBAR,
                true
            )
        }

        val leftGroup = DefaultActionGroup().apply {
            add(browseAction)
            addSeparator()
            add(useCurrentFileAction)
        }

        val rightGroup = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Expand all", "Expand all", AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
            })
            add(object : DumbAwareAction("Collapse all", "Collapse all", AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 0)
            })
        }

        val actionManager = ActionManager.getInstance()

        val leftToolbar = actionManager
            .createActionToolbar("CobraEndpointsToolbarLeft", leftGroup, true)
            .apply {
                targetComponent = root
                setReservePlaceAutoPopupIcon(false)
                component.isOpaque = false
            }

        val rightToolbarTop = actionManager
            .createActionToolbar("CobraEndpointsToolbarRightTop", rightGroup, true)
            .apply {
                targetComponent = root
                setReservePlaceAutoPopupIcon(false)
                component.isOpaque = false
            }

        val rightToolbarBottom = actionManager
            .createActionToolbar("CobraEndpointsToolbarRightBottom", rightGroup, true)
            .apply {
                targetComponent = root
                setReservePlaceAutoPopupIcon(false)
                component.isOpaque = false
            }

        topRow.add(leftToolbar.component, BorderLayout.WEST)
        topRow.add(rightToolbarTop.component, BorderLayout.EAST)

        bottomRow.add(rightToolbarBottom.component, BorderLayout.EAST)
        bottomRow.isVisible = false

        root.add(topRow)
        root.add(bottomRow)

        fun updateWrapping() {
            val leftW = leftToolbar.component.preferredSize.width
            val rightW = rightToolbarTop.component.preferredSize.width
            val padding = JBUI.scale(16)

            val needTwoRows = root.width > 0 && root.width < (leftW + rightW + padding)
            rightToolbarTop.component.isVisible = !needTwoRows
            bottomRow.isVisible = needTwoRows

            root.revalidate()
            root.repaint()
        }

        root.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = updateWrapping()
            override fun componentShown(e: java.awt.event.ComponentEvent) = updateWrapping()
        })

        SwingUtilities.invokeLater { updateWrapping() }

        return root
    }

    private fun buildSelectionRow(): JPanel {
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JLabel("Selection: "), BorderLayout.WEST)
            add(selectionField, BorderLayout.CENTER)
        }
    }

    private fun buildCenterPanel(): JPanel {
        val center = JPanel(BorderLayout()).apply { isOpaque = false }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Echo Endpoints").apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
            }, BorderLayout.WEST)
        }

        val scroll = ScrollPaneFactory.createScrollPane(tree).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
        }

        center.add(header, BorderLayout.NORTH)
        center.add(scroll, BorderLayout.CENTER)
        return center
    }

    /**
     * Tree renderer that keeps all nodes at the same visual weight and uses
     * the tree's font (which follows IDE UI settings and scaling).
     */
    private fun installTreeRenderer() {
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: javax.swing.JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                font = tree.font

                val node = value as? DefaultMutableTreeNode
                val item = node?.userObject as? TreeItem

                icon = when (item?.kind) {
                    NodeKind.FILE -> AllIcons.FileTypes.Any_type
                    NodeKind.GROUP -> AllIcons.Graph.Layout
                    NodeKind.ROUTE -> methodIcon(item.method ?: "")
                    NodeKind.INFO, null -> AllIcons.Nodes.Unknown
                }

                when (item?.kind) {
                    NodeKind.ROUTE -> {
                        val method = item.method ?: item.label.substringBefore(' ', "")
                        val path = item.label.removePrefix(method).trim()

                        append(method, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append(path, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }

                    else -> {
                        append(item?.label ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }
    }

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

    /**
     * Enables navigation to the underlying PSI element by:
     * - double-clicking a node
     * - pressing Enter on a selected node
     */
    private fun installTreeNavigation() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) navigateFromSelectedNode()
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

    /**
     * Switches tool window scope to a user-chosen Go file or directory.
     */
    private fun browse() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
            .withTitle("Select a Go file or a directory")
            .withDescription("Pick a Go file, or a directory to scan recursively.")

        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return

        selectionMode = SelectionMode.CUSTOM
        selectedRoot = chosen
        selectionField.text = chosen.presentableUrl

        scheduleFullRescan()
    }

    /**
     * Sets the scope to the currently active editor file. If no editor is active,
     * keep the previous selection and still trigger a rescan to refresh the tree.
     */
    private fun useCurrentFile() {
        selectionMode = SelectionMode.CURRENT_FILE

        val fem = FileEditorManager.getInstance(project)
        val fromSelectedFiles = fem.selectedFiles.firstOrNull()
        val fromTextEditor = fem.selectedTextEditor
            ?.document
            ?.let { FileDocumentManager.getInstance().getFile(it) }

        val vf = fromSelectedFiles ?: fromTextEditor

        if (vf == null || !vf.isValid) {
            selectionField.text = selectedRoot?.presentableUrl ?: "No active file"
            scheduleFullRescan()
            return
        }

        selectedRoot = vf
        selectionField.text = vf.presentableUrl

        scheduleFullRescan()
    }

    /**
     * Updates the scope automatically when the user changes the active editor tab,
     * but only when in CURRENT_FILE mode.
     */
    private fun installCurrentFileSelectionListener() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (selectionMode != SelectionMode.CURRENT_FILE) return
                    val newFile = event.newFile ?: return
                    if (!newFile.isValid) return
                    selectedRoot = newFile
                    selectionField.text = newFile.presentableUrl
                    scheduleFullRescan()
                }
            }
        )
    }

    /**
     * Installs a VFS listener to refresh only impacted Go files instead of
     * rescanning the entire scope on every change.
     */
    private fun installAutoRefresh() {
        installCurrentFileSelectionListener()

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    val root = selectedRoot ?: return
                    if (!root.isValid) return

                    val impacted = linkedSetOf<VirtualFile>()
                    for (e in events) {
                        val f = e.file ?: continue
                        if (!f.isValid) continue
                        if (f.isDirectory) continue
                        if (!f.name.endsWith(".go", ignoreCase = true)) continue
                        if (isInScope(root, f)) impacted.add(f)
                    }

                    if (impacted.isEmpty()) return

                    refreshAlarm.cancelAllRequests()
                    refreshAlarm.addRequest({
                        val scopeRoot = selectedRoot ?: return@addRequest
                        if (scopeRoot.isDirectory) {
                            impacted.forEach { updateFileSubtreeIfNeeded(it) }
                            removeNowEmptyFileNodes()
                        } else {
                            if (impacted.contains(scopeRoot)) {
                                updateFileSubtreeIfNeeded(scopeRoot)
                                removeNowEmptyFileNodes()
                            }
                        }
                    }, 150)
                }
            }
        )
    }

    private fun isInScope(scopeRoot: VirtualFile, file: VirtualFile): Boolean {
        return if (scopeRoot.isDirectory) {
            VfsUtilCore.isAncestor(scopeRoot, file, true)
        } else {
            scopeRoot == file
        }
    }

    private fun scheduleFullRescan() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ fullRescan() }, 150)
    }

    private fun pathKey(path: TreePath): String {
        val sb = StringBuilder()
        for (p in path.path) {
            val n = p as? DefaultMutableTreeNode ?: continue
            val item = n.userObject as? TreeItem ?: continue
            if (sb.isNotEmpty()) sb.append(" / ")
            sb.append(item.label)
        }
        return sb.toString()
    }

    private fun captureExpandedKeys(): Set<String> {
        val expanded = LinkedHashSet<String>()
        val paths = TreeUtil.collectExpandedPaths(tree)
        for (p in paths) expanded.add(pathKey(p))
        return expanded
    }

    private fun captureSelectionKey(): String? {
        val sel = tree.selectionPath ?: return null
        return pathKey(sel)
    }

    private fun restoreExpandedKeys(expandedKeys: Set<String>) {
        fun visit(node: DefaultMutableTreeNode) {
            val tp = TreePath(node.path)
            if (expandedKeys.contains(pathKey(tp))) tree.expandPath(tp)
            for (i in 0 until node.childCount) {
                visit(node.getChildAt(i) as DefaultMutableTreeNode)
            }
        }
        visit(root)
    }

    private fun restoreSelectionByKey(selectionKey: String?) {
        if (selectionKey == null) return

        fun find(node: DefaultMutableTreeNode): TreePath? {
            val tp = TreePath(node.path)
            if (pathKey(tp) == selectionKey) return tp
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as DefaultMutableTreeNode
                val r = find(child)
                if (r != null) return r
            }
            return null
        }

        val found = find(root) ?: return
        tree.selectionPath = found
        tree.scrollPathToVisible(found)
    }

    /**
     * Rebuilds the entire tree for the current selection while preserving
     * expanded state and selection where possible.
     */
    private fun fullRescan() {
        val scopeRoot = selectedRoot
        val expandedKeys = captureExpandedKeys()
        val selectionKey = captureSelectionKey()

        root.removeAllChildren()
        fileNodeByVf.clear()

        if (scopeRoot == null || !scopeRoot.isValid) {
            root.add(DefaultMutableTreeNode(TreeItem("No selection", NodeKind.INFO)))
            model.reload()
            TreeUtil.expandAll(tree)
            return
        }

        if (!scopeRoot.isDirectory) {
            updateFileSubtreeIfNeeded(scopeRoot, ensureNodeExists = true)
            if (root.childCount == 0) {
                root.add(DefaultMutableTreeNode(TreeItem("No Echo routes found in: ${scopeRoot.name}", NodeKind.INFO)))
            }
            model.reload()
            TreeUtil.expandAll(tree)
            restoreExpandedKeys(expandedKeys)
            restoreSelectionByKey(selectionKey)
            return
        }

        val goFiles = collectGoFiles(scopeRoot)
        for (vf in goFiles) {
            updateFileSubtreeIfNeeded(vf, ensureNodeExists = true)
        }
        removeNowEmptyFileNodes()

        if (root.childCount == 0) {
            root.add(DefaultMutableTreeNode(TreeItem("No Echo routes found under: ${scopeRoot.presentableUrl}", NodeKind.INFO)))
        }

        model.reload()
        TreeUtil.expandAll(tree)
        restoreExpandedKeys(expandedKeys)
        restoreSelectionByKey(selectionKey)
    }

    private fun collectGoFiles(dir: VirtualFile): List<VirtualFile> {
        val out = ArrayList<VirtualFile>(256)
        VfsUtilCore.iterateChildrenRecursively(dir, null) { f ->
            if (!f.isDirectory && f.name.endsWith(".go", ignoreCase = true)) out.add(f)
            true
        }
        out.sortBy { it.presentableUrl }
        return out
    }

    /**
     * Updates a single file subtree based on document/VFS stamps. If the file has
     * no routes, the file node is removed.
     */
    private fun updateFileSubtreeIfNeeded(vf: VirtualFile, ensureNodeExists: Boolean = false) {
        if (!vf.isValid || vf.isDirectory) return
        if (!vf.name.endsWith(".go", ignoreCase = true)) return

        val currentStamp = computeStamp(vf)
        val cached = fileCache[vf]
        val existingNode = fileNodeByVf[vf]

        if (cached != null && cached.stamp == currentStamp) {
            if (!ensureNodeExists || existingNode != null) return
            if (!cached.hasRoutes || cached.parsed == null) return

            val node = DefaultMutableTreeNode(TreeItem(fileLabel(vf), NodeKind.FILE))
            fileNodeByVf[vf] = node
            root.add(node)

            node.removeAllChildren()
            buildGroupsUnderFileNode(node, vf, cached.parsed)
            return
        }

        val parsed = EchoRouteParser.parsePsi(project, vf)
        val hasRoutes = parsed.routes.isNotEmpty()

        fileCache[vf] = CachedFile(
            stamp = currentStamp,
            hasRoutes = hasRoutes,
            parsed = if (hasRoutes) parsed else null
        )

        if (!hasRoutes) {
            val existing = fileNodeByVf[vf]
            if (existing != null) {
                root.remove(existing)
                fileNodeByVf.remove(vf)
                model.reload(root)
            }
            return
        }

        val fileNode = fileNodeByVf[vf] ?: run {
            val node = DefaultMutableTreeNode(TreeItem(fileLabel(vf), NodeKind.FILE))
            root.add(node)
            fileNodeByVf[vf] = node
            node
        }

        fileNode.removeAllChildren()
        buildGroupsUnderFileNode(fileNode, vf, parsed)
        model.nodeStructureChanged(fileNode)

        sortRootFileNodesIfNeeded()
    }

    private fun fileLabel(vf: VirtualFile): String {
        val scopeRoot = selectedRoot
        if (scopeRoot != null && scopeRoot.isDirectory) {
            val rel = VfsUtilCore.getRelativePath(vf, scopeRoot, '/')
            if (rel != null) return rel
        }
        return vf.name
    }

    private fun sortRootFileNodesIfNeeded() {
        val scopeRoot = selectedRoot ?: return
        if (!scopeRoot.isDirectory) return

        val children = (0 until root.childCount)
            .map { root.getChildAt(it) as DefaultMutableTreeNode }
            .toMutableList()

        children.sortBy {
            val item = it.userObject as? TreeItem
            item?.label ?: ""
        }

        root.removeAllChildren()
        for (n in children) root.add(n)
        model.nodeStructureChanged(root)
    }

    private fun removeNowEmptyFileNodes() {
        val toRemove = ArrayList<DefaultMutableTreeNode>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val item = child.userObject as? TreeItem ?: continue
            if (item.kind == NodeKind.FILE && child.childCount == 0) toRemove.add(child)
        }
        if (toRemove.isEmpty()) return
        for (n in toRemove) root.remove(n)
        model.nodeStructureChanged(root)
    }

    /**
     * Builds a per-file subtree grouped by groupPrefix while keeping source order.
     * Each group node navigates to the group definition (if available) or the first route.
     */
    private fun buildGroupsUnderFileNode(
        fileNode: DefaultMutableTreeNode,
        vf: VirtualFile,
        parsed: EchoParseResult
    ) {
        val groupFirstOffset = HashMap<String, Int>(64)
        for (r in parsed.routes) {
            val existing = groupFirstOffset[r.groupPrefix]
            if (existing == null || r.offset < existing) groupFirstOffset[r.groupPrefix] = r.offset
        }
        for ((prefix, g) in parsed.groupsByPrefix) {
            val existing = groupFirstOffset[prefix]
            if (existing == null || g.offset < existing) groupFirstOffset[prefix] = g.offset
        }

        val grouped = parsed.routes.groupBy { it.groupPrefix }
        val groupPrefixesInOrder = grouped.keys.sortedBy { groupFirstOffset[it] ?: Int.MAX_VALUE }

        for (groupPrefix in groupPrefixesInOrder) {
            val groupRoutes = grouped[groupPrefix].orEmpty()

            val groupOffset = parsed.groupsByPrefix[groupPrefix]?.offset
                ?: groupRoutes.minOfOrNull { it.offset }
                ?: 0

            val groupNode = DefaultMutableTreeNode(
                TreeItem(
                    label = groupPrefix,
                    kind = NodeKind.GROUP,
                    nav = NavTarget(vf, groupOffset)
                )
            )

            for (r in groupRoutes) {
                groupNode.add(
                    DefaultMutableTreeNode(
                        TreeItem(
                            label = "${r.method} ${r.path}",
                            kind = NodeKind.ROUTE,
                            nav = NavTarget(vf, r.offset),
                            method = r.method
                        )
                    )
                )
            }

            fileNode.add(groupNode)
        }
    }

    private fun computeStamp(vf: VirtualFile): FileStamp {
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        return FileStamp(
            docStamp = doc?.modificationStamp,
            vfsStamp = vf.modificationStamp
        )
    }

    override fun dispose() {
        refreshAlarm.cancelAllRequests()
    }
}
