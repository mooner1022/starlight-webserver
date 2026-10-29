package dev.mooner.starlightwebserver.route

import io.ktor.http.decodeURLPart

/**
 * A route path such as `/users/:id`, or `/files/` followed by a `*` segment.
 *
 * `:name` captures one segment, and a trailing `*` captures the rest of the path (possibly empty) as the `*` param.
 */
class PathPattern private constructor(
    val pattern: String,
    private val segments: List<Segment>,
) {

    private sealed interface Segment {
        data class Literal(val value: String) : Segment
        data class Param(val name: String) : Segment
        data object Wildcard : Segment
    }

    /** Per-segment ranks, compared to prefer literal segments over params, and params over a wildcard. */
    internal val specificity: List<Int> = segments.map { segment ->
        when (segment) {
            is Segment.Literal -> 0
            is Segment.Param   -> 1
            Segment.Wildcard   -> 2
        }
    }

    /** The captured params if [path] matches, or null. */
    fun match(path: String): Map<String, String>? {
        val parts = splitPath(path)
        val params: MutableMap<String, String> = linkedMapOf()
        for ((i, segment) in segments.withIndex()) {
            when (segment) {
                Segment.Wildcard -> {
                    params[WILDCARD] = parts.drop(i).joinToString("/")
                    return params
                }
                is Segment.Literal ->
                    if (parts.getOrNull(i) != segment.value)
                        return null
                is Segment.Param ->
                    params[segment.name] = parts.getOrNull(i) ?: return null
            }
        }
        return if (parts.size == segments.size) params else null
    }

    override fun equals(other: Any?): Boolean =
        other is PathPattern && other.segments == segments

    override fun hashCode(): Int =
        segments.hashCode()

    override fun toString(): String =
        pattern

    companion object {

        const val WILDCARD = "*"

        fun parse(pattern: String): PathPattern {
            require(pattern.startsWith("/")) { "Route path must start with '/': $pattern" }
            val parts = pattern.split('/').filter(String::isNotEmpty)
            val segments = parts.mapIndexed { i, part ->
                when {
                    part == WILDCARD -> {
                        require(i == parts.lastIndex) { "'*' is only allowed at the end of a route path: $pattern" }
                        Segment.Wildcard
                    }
                    part.startsWith(":") -> {
                        val name = part.substring(1)
                        require(name.isNotEmpty()) { "Empty param name in route path: $pattern" }
                        Segment.Param(name)
                    }
                    else -> Segment.Literal(part.decodeURLPart())
                }
            }
            val params = segments.filterIsInstance<Segment.Param>().map(Segment.Param::name)
            require(params.size == params.toSet().size) { "Duplicated param name in route path: $pattern" }
            return PathPattern(pattern, segments)
        }

        /** Decoded, non-empty segments of a request path. */
        internal fun splitPath(path: String): List<String> =
            path.split('/')
                .filter(String::isNotEmpty)
                .map { it.decodeURLPart() }
    }
}
