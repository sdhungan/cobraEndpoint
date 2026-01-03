package com.monkops.cobraendpointstructure

import com.goide.psi.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

data class EchoGroup(
    val varName: String,
    val prefix: String,
    val offset: Int,
)

data class EchoRoute(
    val groupVar: String,
    val groupPrefix: String,
    val method: String,
    val path: String,
    val offset: Int,
)

data class EchoParseResult(
    val groupsByPrefix: Map<String, EchoGroup>,
    val routes: List<EchoRoute>,
)

object EchoRouteParser {

    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")

    /**
     * PSI-based entrypoint:
     * - Commits documents so PSI reflects unsaved editor changes.
     * - Uses PSI tied to the editor document when available.
     */
    fun parsePsi(project: Project, file: VirtualFile): EchoParseResult {
        return ReadAction.compute<EchoParseResult, RuntimeException> {
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val fileDocumentManager = FileDocumentManager.getInstance()

            val doc = fileDocumentManager.getDocument(file)
            if (doc != null) {
                psiDocumentManager.commitDocument(doc)
            } else {
                psiDocumentManager.commitAllDocuments()
            }

            val psiFile =
                if (doc != null) psiDocumentManager.getPsiFile(doc)
                else PsiManager.getInstance(project).findFile(file)

            val goFile = psiFile as? GoFile
                ?: return@compute EchoParseResult(emptyMap(), emptyList())

            parsePsiFile(goFile)
        }
    }

    /**
     * Parses group declarations and routes from a GoFile.
     * Routes are returned in source order (by offset).
     */
    fun parsePsiFile(goFile: GoFile): EchoParseResult {
        val groupPrefixByVar = HashMap<String, String>(64)    // var -> full prefix
        val groupsByPrefix = linkedMapOf<String, EchoGroup>() // prefix -> group
        val routes = ArrayList<EchoRoute>(64)

        // Resolve nested Group() chains by doing a few passes.
        repeat(3) {
            val shortDecls = PsiTreeUtil.findChildrenOfType(goFile, GoShortVarDeclaration::class.java)
            for (decl in shortDecls) {
                val vars = decl.varDefinitionList.mapNotNull { it.name }
                val exprs = decl.expressionList.toList()
                val n = minOf(vars.size, exprs.size)

                for (i in 0 until n) {
                    val assignedVar = vars[i]
                    val rhs = exprs[i] as? GoCallExpr ?: continue

                    val callee = rhs.expression as? GoReferenceExpression ?: continue
                    val calleeName = callee.identifier?.text ?: continue
                    if (calleeName != "Group") continue

                    val parentVar = extractReceiverVarName(callee.qualifier) ?: continue
                    val childPart = evalString(rhs.argumentList?.expressionList?.firstOrNull()) ?: continue

                    val parentPrefix = groupPrefixByVar[parentVar] ?: ""
                    val full = normalizePath("$parentPrefix/$childPart")
                    val offset = decl.textRange.startOffset

                    groupPrefixByVar[assignedVar] = full
                    groupsByPrefix.putIfAbsent(full, EchoGroup(assignedVar, full, offset))
                }
            }
        }

        // Routes: receiver.METHOD("/path", ...)
        val calls = PsiTreeUtil.findChildrenOfType(goFile, GoCallExpr::class.java)
        for (call in calls) {
            val callee = call.expression as? GoReferenceExpression ?: continue

            val method = callee.identifier?.text ?: continue
            if (method !in HTTP_METHODS) continue

            val groupVar = extractReceiverVarName(callee.qualifier) ?: continue
            val routePath = evalString(call.argumentList?.expressionList?.firstOrNull()) ?: continue

            val groupPrefix = groupPrefixByVar[groupVar] ?: "/"
            routes += EchoRoute(
                groupVar = groupVar,
                groupPrefix = groupPrefix.ifBlank { "/" },
                method = method,
                path = normalizePath(routePath),
                offset = call.textRange.startOffset
            )
        }

        return EchoParseResult(
            groupsByPrefix = groupsByPrefix,
            routes = routes.sortedBy { it.offset }
        )
    }

    private fun extractReceiverVarName(qualifier: GoQualifier?): String? {
        val ref = qualifier as? GoReferenceExpression ?: return null
        val name = ref.identifier?.text ?: ref.text
        return if (name.matches(Regex("""[A-Za-z_]\w*"""))) name else null
    }

    /**
     * Evaluates simple string expressions:
     * - string literals ("..." or `...`)
     * - concatenations using "+"
     */
    private fun evalString(expr: GoExpression?): String? {
        if (expr == null) return null

        (expr as? GoStringLiteral)?.let { return it.decodedText }

        val bin = expr as? GoBinaryExpr
        if (bin?.operator?.text == "+") {
            val left = evalString(bin.left)
            val right = evalString(bin.right)
            if (left != null && right != null) return left + right
        }

        val t = expr.text?.trim() ?: return null
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("`") && t.endsWith("`"))) {
            return t.substring(1, t.length - 1)
        }

        return null
    }

    private fun normalizePath(p: String): String {
        var s = p.trim()
        if (!s.startsWith("/")) s = "/$s"
        s = s.replace(Regex("""/+"""), "/")
        if (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }
}
