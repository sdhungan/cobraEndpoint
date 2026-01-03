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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.JTextField
import javax.swing.SwingConstants
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

        Disposer.register(content, view)
    }
}

private class CobraEndpointToolWindowView(private val project: Project) : Disposable {

    // ---------------------------------
    // Tree model objects
    // ---------------------------------

    private data class NavTarget(val file: VirtualFile, val offset: Int)

    private enum class NodeKind { FILE, GROUP, ROUTE, INFO }

    private data class TreeItem(
        val label: String,
        val kind: NodeKind,
        val nav: NavTarget? = null,
        val method: String? = null, // for ROUTE only
    ) {
        override fun toString(): String = label
    }

    // ---------------------------------
    // State / cache
    // ---------------------------------

    private enum class SelectionMode { CURRENT_FILE, CUSTOM }

    private data class FileStamp(
        val docStamp: Long?,   // if document exists (unsaved changes)
        val vfsStamp: Long     // filesystem stamp
    )

    private data class CachedFile(
        val stamp: FileStamp,
        val hasRoutes: Boolean,
        val parsed: EchoParseResult?
    )

    private var selectionMode: SelectionMode = SelectionMode.CURRENT_FILE
    private var selectedRoot: VirtualFile? = null // file or directory
    private val fileCache = HashMap<VirtualFile, CachedFile>(128)

    // subtree mapping for incremental updates (directory mode)
    private val fileNodeByVf = HashMap<VirtualFile, DefaultMutableTreeNode>(128)

    // ---------------------------------
    // UI
    // ---------------------------------

    private val root = DefaultMutableTreeNode(TreeItem("Echo Endpoints", NodeKind.INFO))
    private val model = DefaultTreeModel(root)

    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 22
    }

    private val selectionField = JTextField().apply {
        isEditable = false
        columns = 40
        text = "No selection"
        toolTipText = "Current selection"
    }

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false

        add(buildTopPanel(), BorderLayout.NORTH)
        add(buildCenterPanel(), BorderLayout.CENTER)

        installTreeRenderer()
        installTreeNavigation()
        installAutoRefresh()

        // initial
        useCurrentFile()
    }

    // ---------------------------------
    // Layout
    // ---------------------------------

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

            add(makeButton("Browse…", AllIcons.General.OpenDisk, "Choose a Go file or a directory") {
                browse()
            })

            add(Box.createHorizontalStrut(6))

            add(makeButton("Use current file", AllIcons.Actions.Back, "Reset to the currently active editor file") {
                useCurrentFile()
            })

            add(Box.createHorizontalGlue())

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false

                add(iconButton(AllIcons.Actions.Expandall, "Expand all") {
                    TreeUtil.expandAll(tree)
                })

                add(iconButton(AllIcons.Actions.Collapseall, "Collapse all") {
                    TreeUtil.collapseAll(tree, 0)
                })
            }
            add(right)
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
        val center = JPanel(BorderLayout()).apply { isOpaque = false }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Echo Endpoints").apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
            }, BorderLayout.WEST)
        }

        center.add(header, BorderLayout.NORTH)
        center.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        return center
    }

    private fun makeButton(text: String, icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JButton {
        return JButton(text, icon).apply {
            toolTipText = tooltip
            horizontalAlignment = SwingConstants.LEFT

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

    // ---------------------------------
    // Tree renderer (icons)
    // ---------------------------------

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

                icon = when (item?.kind) {
                    NodeKind.FILE -> AllIcons.FileTypes.Any_type
                    NodeKind.GROUP -> AllIcons.Nodes.Class // node-like symbol
                    NodeKind.ROUTE -> methodIcon(item.method ?: "")
                    NodeKind.INFO, null -> AllIcons.Nodes.Unknown
                }

                return c
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

    // ---------------------------------
    // Navigation: double-click / Enter
    // ---------------------------------

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

    // ---------------------------------
    // Browse / selection
    // ---------------------------------

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

    private fun useCurrentFile() {
        selectionMode = SelectionMode.CURRENT_FILE
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val vf = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }

        selectedRoot = vf
        selectionField.text = vf?.presentableUrl ?: "No active file"

        scheduleFullRescan()
    }

    // keep current file selection updated in CURRENT_FILE mode
    private fun installCurrentFileSelectionListener() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (selectionMode != SelectionMode.CURRENT_FILE) return
                    useCurrentFile()
                }
            }
        )
    }

    // ---------------------------------
    // Auto refresh (incremental)
    // ---------------------------------

    private fun installAutoRefresh() {
        installCurrentFileSelectionListener()

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    val root = selectedRoot ?: return
                    if (!root.isValid) return

                    // find potentially impacted files within scope
                    val impacted = linkedSetOf<VirtualFile>()
                    for (e in events) {
                        val f = e.file ?: continue
                        if (!f.isValid) continue
                        if (f.isDirectory) continue
                        if (!f.name.endsWith(".go", ignoreCase = true)) continue

                        if (isInScope(root, f)) impacted.add(f)
                    }

                    if (impacted.isEmpty()) return

                    // directory: update only impacted files
                    // single file: update if it's the selected file
                    refreshAlarm.cancelAllRequests()
                    refreshAlarm.addRequest({
                        val scopeRoot = selectedRoot ?: return@addRequest
                        if (scopeRoot.isDirectory) {
                            impacted.forEach { updateFileSubtreeIfNeeded(it) }
                            removeNowEmptyFileNodes()
                            restoreTreeUiStateAfterIncrementalUpdate()
                        } else {
                            if (impacted.contains(scopeRoot)) {
                                updateFileSubtreeIfNeeded(scopeRoot)
                                removeNowEmptyFileNodes()
                                restoreTreeUiStateAfterIncrementalUpdate()
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

    // ---------------------------------
    // State preservation
    // ---------------------------------

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
        // brute-force expand matching paths by walking current tree
        fun visit(node: DefaultMutableTreeNode) {
            val tp = TreePath(node.path)
            if (expandedKeys.contains(pathKey(tp))) {
                tree.expandPath(tp)
            }
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

    private fun restoreTreeUiStateAfterIncrementalUpdate() {
        // We can’t perfectly preserve everything after arbitrary structure changes
        // unless we capture before + restore after. For incremental updates we
        // capture on-demand inside update operations.
        // (No-op here; used by callers that already captured state.)
    }

    // ---------------------------------
    // Scanning + incremental tree updates
    // ---------------------------------

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
            // single file
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

        // directory
        val goFiles = collectGoFiles(scopeRoot)

        // only add files with routes
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
        val out = ArrayList<VirtualFile>(128)
        VfsUtilCore.iterateChildrenRecursively(dir, null) { f ->
            if (!f.isDirectory && f.name.endsWith(".go", ignoreCase = true)) out.add(f)
            true
        }
        out.sortBy { it.presentableUrl }
        return out
    }

    private fun updateFileSubtreeIfNeeded(vf: VirtualFile, ensureNodeExists: Boolean = false) {
        if (!vf.isValid || vf.isDirectory) return
        if (!vf.name.endsWith(".go", ignoreCase = true)) return

        val currentStamp = computeStamp(vf)
        val cached = fileCache[vf]
        if (cached != null && cached.stamp == currentStamp) {
            // stamp unchanged → nothing to do
            return
        }

        val text = readFileTextPreferDocument(vf)
        val parsed = EchoRouteParser.parsePsi(project, vf)
        val hasRoutes = parsed.routes.isNotEmpty()

        fileCache[vf] = CachedFile(
            stamp = currentStamp,
            hasRoutes = hasRoutes,
            parsed = if (hasRoutes) parsed else null
        )

        // Capture UI state before we mutate this part of the tree
        val expandedKeys = captureExpandedKeys()
        val selectionKey = captureSelectionKey()

        if (!hasRoutes) {
            // remove node if present
            val existing = fileNodeByVf[vf]
            if (existing != null) {
                root.remove(existing)
                fileNodeByVf.remove(vf)
                model.reload(root)
            }
            restoreExpandedKeys(expandedKeys)
            restoreSelectionByKey(selectionKey)
            return
        }

        val fileNode = fileNodeByVf[vf] ?: run {
            if (!ensureNodeExists) {
                // in incremental mode, if it wasn't visible before but now has routes, add it
            }
            val node = DefaultMutableTreeNode(TreeItem(fileLabel(vf), NodeKind.FILE))
            root.add(node)
            fileNodeByVf[vf] = node
            node
        }

        // rebuild just this file's children
        fileNode.removeAllChildren()
        buildGroupsUnderFileNode(fileNode, vf, parsed)

        model.nodeStructureChanged(fileNode)

        // Keep ordering stable in directory mode (sort file nodes)
        sortRootFileNodesIfNeeded()

        restoreExpandedKeys(expandedKeys)
        restoreSelectionByKey(selectionKey)
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

    private fun buildGroupsUnderFileNode(
        fileNode: DefaultMutableTreeNode,
        vf: VirtualFile,
        parsed: EchoParseResult
    ) {
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
                    kind = NodeKind.GROUP,
                    nav = NavTarget(vf, groupOffset)
                )
            )

            val sorted = groupRoutes.sortedWith(compareBy<EchoRoute> { it.method }.thenBy { it.path })
            for (r in sorted) {
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

    // ---------------------------------
    // Reading / stamps
    // ---------------------------------

    private fun computeStamp(vf: VirtualFile): FileStamp {
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        return FileStamp(
            docStamp = doc?.modificationStamp,
            vfsStamp = vf.modificationStamp
        )
    }

    private fun readFileTextPreferDocument(vf: VirtualFile): String {
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        return doc?.text ?: VfsUtilCore.loadText(vf)
    }

    // ---------------------------------
    // Dispose
    // ---------------------------------

    override fun dispose() {
        refreshAlarm.cancelAllRequests()
    }
}
