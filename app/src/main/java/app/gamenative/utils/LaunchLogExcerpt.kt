package app.gamenative.utils

import java.util.Locale

/**
 * Condenses a launch log into the lines that explain a failure.
 *
 * A game's log is mostly repetition: the same warning once per frame, the same shader log line for
 * every pipeline. Sending all of it to a model (or showing all of it in the viewer's summary) buries
 * the one line that matters and costs tokens. This keeps the distinct problem lines first, in the
 * order they appeared, then the tail, which is where a crash leaves its last words.
 *
 * Pure functions over text only, so the behaviour is testable without a device.
 */
object LaunchLogExcerpt {

    /** Substrings that mark a line as worth keeping. Matched case-insensitively. */
    private val PROBLEM_MARKERS = listOf(
        "err:",
        "error",
        "failed",
        "failure",
        "cannot",
        "can't",
        "unable",
        "not found",
        "no such file",
        "exception",
        "segfault",
        "backtrace",
        "abort",
        "fatal",
        "panic",
        "unsupported",
        "missing",
        "denied",
        "timed out",
        "out of memory",
        "crash",
    )

    /** Values that change every run (ids, addresses, timings) would defeat the dedupe. */
    private val HEX = Regex("0x[0-9a-fA-F]+")
    private val LONG_NUMBER = Regex("\\d{4,}")

    const val DEFAULT_PROBLEM_LINES = 60
    const val DEFAULT_TAIL_LINES = 40

    /**
     * `N lines` of the log compressed into the problem lines (deduplicated, with a repeat count)
     * followed by the tail. Never longer than the input, and always ends with the real last line so
     * a crash is still visible.
     */
    fun build(
        log: String,
        problemLines: Int = DEFAULT_PROBLEM_LINES,
        tailLines: Int = DEFAULT_TAIL_LINES,
    ): String {
        if (log.isBlank()) return ""
        val lines = log.lines()

        val seen = HashMap<String, Int>()
        val order = mutableListOf<String>()
        for (line in lines) {
            if (!isProblem(line)) continue
            val key = normalise(line)
            val count = seen[key]
            if (count == null) {
                seen[key] = 1
                order += line
            } else {
                seen[key] = count + 1
            }
        }

        val kept = order.take(problemLines)
        val dropped = order.size - kept.size
        val tail = lines.takeLast(tailLines)

        return buildString {
            appendLine("--- problem lines (${order.size} distinct, ${lines.size} total) ---")
            if (kept.isEmpty()) {
                appendLine("(none: the log has no line that looks like a failure)")
            } else {
                for (line in kept) {
                    val repeats = seen[normalise(line)] ?: 1
                    if (repeats > 1) {
                        appendLine("${line.take(1000)}   [x$repeats]")
                    } else {
                        appendLine(line.take(1000))
                    }
                }
                if (dropped > 0) appendLine("... $dropped further distinct problem lines omitted")
            }
            appendLine("--- last ${tail.size} lines ---")
            tail.forEach { appendLine(it.take(1000)) }
        }
    }

    /** True when the line looks like it explains a failure rather than routine progress. */
    fun isProblem(line: String): Boolean {
        if (line.isBlank()) return false
        val lower = line.lowercase(Locale.ENGLISH)
        return PROBLEM_MARKERS.any { lower.contains(it) }
    }

    /** Same problem, different addresses or timings: same key, so the count carries the signal. */
    internal fun normalise(line: String): String =
        LONG_NUMBER.replace(HEX.replace(line.trim(), "#"), "#")
}
