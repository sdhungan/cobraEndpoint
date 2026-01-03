package com.monkops.cobraendpointstructure

data class EchoRoute(val groupPrefix: String, val method: String, val path: String)

object EchoRouteParser {

    // Matches: templDomain := e.Group("/templDomain")
    // Matches: v1 := templDomain.Group("/v1")
    private val groupAssignRegex = Regex(
        pattern = """\b(\w+)\s*:=\s*(\w+)\.Group\(\s*"([^"]+)"\s*\)"""
    )

    // Matches: templDomain.GET("/info", ...)
    private val routeRegex = Regex(
        pattern = """\b(\w+)\.(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\(\s*"([^"]+)""""
    )

    fun parse(text: String): List<EchoRoute> {
        // Map of variable -> full prefix, e.g. templDomain -> /templDomain
        val groupPrefixByVar = mutableMapOf<String, String>()

        // pass 1: collect group assignments in order (handles nested groups if base already known)
        // We do multiple passes to resolve nested groups even if ordering is a bit weird.
        repeat(3) {
            for (m in groupAssignRegex.findAll(text)) {
                val childVar = m.groupValues[1]
                val parentVar = m.groupValues[2]
                val childPart = m.groupValues[3]

                val parentPrefix = groupPrefixByVar[parentVar] ?: "" // e might not exist -> ""
                val full = normalizePath(parentPrefix + "/" + childPart)

                groupPrefixByVar[childVar] = full
            }
        }

        // pass 2: collect routes
        val routes = mutableListOf<EchoRoute>()
        for (m in routeRegex.findAll(text)) {
            val groupVar = m.groupValues[1]
            val method = m.groupValues[2]
            val routePath = m.groupValues[3]

            val groupPrefix = groupPrefixByVar[groupVar] ?: "" // if unknown, treat as root
            routes += EchoRoute(
                groupPrefix = if (groupPrefix.isBlank()) "/" else groupPrefix,
                method = method,
                path = normalizePath(routePath)
            )
        }

        return routes
    }

    private fun normalizePath(p: String): String {
        var s = p.trim()
        if (!s.startsWith("/")) s = "/$s"
        // collapse multiple slashes
        s = s.replace(Regex("""/+"""), "/")
        // remove trailing slash except root
        if (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }
}
