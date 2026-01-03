package com.monkops.cobraendpointstructure

data class EchoGroup(
    val varName: String,
    val prefix: String,
    val offset: Int,   // offset of the group assignment in the file text
)

data class EchoRoute(
    val groupVar: String,
    val groupPrefix: String,
    val method: String,
    val path: String,
    val offset: Int,   // offset of the route call in the file text
)

data class EchoParseResult(
    val groupsByPrefix: Map<String, EchoGroup>,
    val routes: List<EchoRoute>,
)

object EchoRouteParser {

    // Matches:
    // templDomain := e.Group("/templDomain")
    // v1 := templDomain.Group("/v1")
    private val groupAssignRegex = Regex(
        pattern = """\b(\w+)\s*:=\s*(\w+)\.Group\(\s*"([^"]+)"\s*\)"""
    )

    // Matches:
    // templDomain.GET("/info", ...)
    private val routeRegex = Regex(
        pattern = """\b(\w+)\.(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\(\s*"([^"]+)""""
    )

    fun parse(text: String): EchoParseResult {
        // var -> full prefix
        val groupPrefixByVar = mutableMapOf<String, String>()
        // prefix -> group (we keep the first seen definition for that prefix)
        val groupByPrefix = linkedMapOf<String, EchoGroup>()

        // multiple passes so nested groups can resolve when parent is found
        repeat(3) {
            for (m in groupAssignRegex.findAll(text)) {
                val childVar = m.groupValues[1]
                val parentVar = m.groupValues[2]
                val childPart = m.groupValues[3]
                val offset = m.range.first

                val parentPrefix = groupPrefixByVar[parentVar] ?: ""
                val full = normalizePath(parentPrefix + "/" + childPart)

                groupPrefixByVar[childVar] = full

                // Record group by its full prefix (stable for tree nodes)
                groupByPrefix.putIfAbsent(
                    full,
                    EchoGroup(varName = childVar, prefix = full, offset = offset)
                )
            }
        }

        val routes = mutableListOf<EchoRoute>()
        for (m in routeRegex.findAll(text)) {
            val groupVar = m.groupValues[1]
            val method = m.groupValues[2]
            val routePath = m.groupValues[3]
            val offset = m.range.first

            val groupPrefix = groupPrefixByVar[groupVar] ?: "/"
            routes += EchoRoute(
                groupVar = groupVar,
                groupPrefix = if (groupPrefix.isBlank()) "/" else groupPrefix,
                method = method,
                path = normalizePath(routePath),
                offset = offset
            )
        }

        return EchoParseResult(
            groupsByPrefix = groupByPrefix,
            routes = routes
        )
    }

    private fun normalizePath(p: String): String {
        var s = p.trim()
        if (!s.startsWith("/")) s = "/$s"
        s = s.replace(Regex("""/+"""), "/")
        if (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }
}
