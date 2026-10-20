package dev.ahmed.savlino.domain

/** In-memory remainder of one intake. Never serialize this or include URLs in diagnostics. */
class LinkQueue private constructor(private val remaining: List<ValidatedLink>, val nextNumber: Int, val total: Int) {
    val next: ValidatedLink get() = remaining.first()
    fun advance(): LinkQueue? = remaining.drop(1).takeIf { it.isNotEmpty() }?.let {
        LinkQueue(it, nextNumber + 1, total)
    }
    override fun toString() = "LinkQueue(next=$nextNumber, total=$total)"

    companion object {
        fun afterFirst(candidates: List<ValidatedLink>): LinkQueue? = candidates.drop(1).takeIf { it.isNotEmpty() }
            ?.let { LinkQueue(it, 2, candidates.size) }
    }
}
