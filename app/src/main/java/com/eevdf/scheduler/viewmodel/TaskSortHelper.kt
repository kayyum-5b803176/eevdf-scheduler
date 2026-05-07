package com.eevdf.scheduler.viewmodel

import com.eevdf.scheduler.model.Task

/**
 * Shared sort utility used by [TaskListBuilderDelegate] (Queue list),
 * [TaskSchedulerDelegate] (sibling / global rotation), and
 * [AddTaskActivity] (parent-group spinner).
 *
 * ── Sort order ────────────────────────────────────────────────────────────────
 *
 *   1, 2, 2.5, 2.a, 2.!, 🔵 5, 🔴 6, 11, 11.a, apple, !
 *
 *  Leading emoji / decorative characters are transparent to sorting: "🔵 5"
 *  and "🔴 6" both sort by their first meaningful character (the digit '5'
 *  and '6' respectively).  The full name is still used for the final tie-break
 *  so two names that differ only in their emoji prefix stay in a stable order.
 *
 *  For names whose first meaningful character IS a digit, sorting is hierarchical:
 *    1. Integer prefix value        (1 < 2 < 5 < 11)
 *    2. Suffix type                 (none < digit-sub < letter-sub < symbol-sub)
 *    3. Digit-suffix numeric value  (2.5 < 2.9 < 2.10  — numeric, not lexicographic)
 *    4. Full name lowercase         (tie-break)
 *
 *  Names whose first meaningful character is a letter come after all digit-leading
 *  names; symbol-leading names come last.
 *
 * ── Extending ─────────────────────────────────────────────────────────────────
 *
 *  • New sort tier or sub-tier order → edit [subTier] / [sortTier] only.
 *  • New emoji or prefix to skip     → edit [findSortAnchor] only.
 *  All call sites pick up changes via [nameComparator] / [taskNameComparator].
 */
internal object TaskSortHelper {

    // ── Anchor finder ─────────────────────────────────────────────────────────

    /**
     * Scans forward past any "decorative" leading characters and returns the
     * substring starting at the first meaningful sort character.
     *
     * Skipped: emoji (UTF-16 surrogate halves), whitespace, and any non-ASCII
     * character that is neither a letter nor a digit (e.g. combining marks,
     * pictographs encoded in the BMP).
     *
     * Kept: ASCII digits, ASCII/Unicode letters, ASCII symbols (code 33–126).
     *
     * Examples:
     *   "🔵 5 tasks" → "5 tasks"
     *   "🔴 6"       → "6"
     *   "2.a"        → "2.a"   (no prefix to skip)
     *   "  !alert"   → "!alert"
     */
    private fun findSortAnchor(name: String): String {
        var i = 0
        while (i < name.length) {
            val c = name[i]
            when {
                // UTF-16 surrogate halves — half of an emoji or supplementary char
                c.isSurrogate()                                     -> i++
                // Whitespace of any kind (space, NBSP, ideographic space, …)
                c.isWhitespace()                                    -> i++
                // Non-ASCII character that is neither letter nor digit
                // (emoji encoded in the BMP, combining diacritics used as prefixes, etc.)
                c.code > 127 && !c.isLetter() && !c.isDigit()      -> i++
                // Found first meaningful character — stop scanning
                else                                                -> break
            }
        }
        return if (i == 0) name else name.substring(i)
    }

    // ── Sort key ──────────────────────────────────────────────────────────────

    private data class SortKey(
        val tier:      Int,    // 0=digit-leading, 1=letter-leading, 2=symbol-leading
        val intPart:   Long,   // leading integer value  (0 for non-digit names)
        val subTier:   Int,    // 0=no suffix, 1=digit-sub, 2=letter-sub, 3=symbol-sub
        val subNumber: Double, // numeric value of a digit-suffix (0.0 otherwise)
        val nameLC:    String  // original name lowercased — final tie-break
    )

    private val intPrefixRegex = Regex("""^(\d+)""")
    private val dotDigitRegex  = Regex("""^\.(\d+(?:\.\d+)?)""") // ".5", ".10", ".2.5"

    private fun sortKey(name: String): SortKey {
        // Resolve the anchor: first meaningful character in the name
        val s     = findSortAnchor(name)
        val first = s.firstOrNull()
            ?: return SortKey(2, 0, 0, 0.0, name.lowercase())

        // ── No leading integer → letter or symbol tier ────────────────────────
        val intMatch = intPrefixRegex.find(s)
        if (intMatch == null) {
            val tier = if (first.isLetter()) 1 else 2
            return SortKey(tier, 0, 0, 0.0, name.lowercase())
        }

        // ── Leading integer → classify the suffix ────────────────────────────
        val intPart  = intMatch.groupValues[1].toLongOrNull() ?: 0L
        val rest     = s.substring(intMatch.value.length)
        val restTrim = rest.trimStart(' ')

        val subTier:   Int
        val subNumber: Double

        when {
            restTrim.isEmpty() -> {
                // "2", "🔵 5" (after anchor) — nothing after the integer
                subTier = 0; subNumber = 0.0
            }
            dotDigitRegex.containsMatchIn(restTrim) -> {
                // ".5", ".10" — dot followed by digits
                subTier   = 1
                subNumber = dotDigitRegex.find(restTrim)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            }
            restTrim.trimStart('.').firstOrNull()?.isLetter() == true -> {
                // ".a", "a", ".alpha" — dot (optional) then a letter
                subTier = 2; subNumber = 0.0
            }
            else -> {
                // ".!", "!" — anything else is symbol sub
                subTier = 3; subNumber = 0.0
            }
        }

        return SortKey(0, intPart, subTier, subNumber, name.lowercase())
    }

    // ── Public comparators ────────────────────────────────────────────────────

    val nameComparator: Comparator<String> = Comparator { a, b ->
        val ka = sortKey(a)
        val kb = sortKey(b)
        compareValuesBy(ka, kb,
            SortKey::tier,
            SortKey::intPart,
            SortKey::subTier,
            SortKey::subNumber,
            SortKey::nameLC
        )
    }

    val taskNameComparator: Comparator<Task> =
        Comparator { a, b -> nameComparator.compare(a.name, b.name) }

    // Legacy call-site compat — new code should use nameComparator directly
    fun extractNumber(name: String): Double {
        val k = sortKey(name)
        return if (k.tier == 0) k.intPart.toDouble() else Double.MAX_VALUE
    }
}
