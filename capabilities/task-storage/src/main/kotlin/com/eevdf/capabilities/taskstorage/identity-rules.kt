package com.eevdf.capabilities.taskstorage

/**
 * Pure rules for a task's identity — no Android, no clock, no database.
 *
 * An identity is a KIND plus a numeric ID, stored in ONE column ([Task.identity])
 * as `kind:id`, e.g. `process:101`, `user:101`, `undefined:104`.
 *
 * Mirrors a real system, where the number spaces are separate: UID 1000 and
 * PID 1000 are unrelated, so every kind has its OWN pool of IDs. Within a kind
 * an ID is unique; changing a task's kind moves it to the new kind's pool and
 * gives it a fresh ID there, so it can never conflict with anything.
 *
 *   • IDs 1–[LAST_RESERVED] are reserved for the system ("root"). Nothing a
 *     person does can reach them: the ID is generated, never typed.
 *   • Everything else comes from [FIRST_ASSIGNABLE]..[MAX_ID], always the
 *     SMALLEST number not currently in use in that kind's pool, so a freed
 *     number is reused before the pool grows.
 *   • [MAX_ID] is the Linux PID ceiling (2^22) and applies to every kind.
 *
 * Uniqueness is enforced by [TaskRepository] under a lock, not by a database
 * index: multi-device sync writes tasks with `INSERT OR REPLACE`, and a unique
 * index would let a remote row silently delete a different local task.
 */
enum class IdentityKind(val key: String, val label: String, val idLabel: String) {
    UNDEFINED("undefined", "Undefined", "ID"),
    USER("user", "User", "UID"),
    GROUP("group", "Group", "GID"),
    PROCESS("process", "Process", "PID"),
    THREAD("thread", "Thread", "TID"),
    PROCESS_GROUP("pgroup", "Process Group", "PGID"),
    SESSION("session", "Session", "SID");

    companion object {
        /** What a new task starts as. */
        val DEFAULT = UNDEFINED

        fun fromKey(key: String): IdentityKind? = values().firstOrNull { it.key == key }
    }
}

/** A parsed identity. [id] is always inside 1..[IdentityRules.MAX_ID]. */
data class Identity(val kind: IdentityKind, val id: Int)

/** Projection used to repair identities in creation order. */
data class IdentityRow(val id: String, val identity: String)

/** Thrown when a kind's pool has no free number left (practically unreachable). */
class IdentityPoolFullException(val kind: IdentityKind) :
    IllegalStateException("No free ID left for kind '${kind.key}'")

object IdentityRules {

    const val FIRST_RESERVED = 1
    const val LAST_RESERVED = 100
    const val FIRST_ASSIGNABLE = 101
    const val MAX_ID = 4_194_304

    /** `kind:id`. */
    fun format(kind: IdentityKind, id: Int): String = "${kind.key}:$id"

    /** The kind named by [raw], even when no ID follows it (`"user:"` → USER). Null if unknown. */
    fun parseKind(raw: String): IdentityKind? =
        IdentityKind.fromKey(raw.substringBefore(':').trim())

    /** Full `kind:id`, or null when either half is missing, unknown or out of range. */
    fun parse(raw: String): Identity? {
        val colon = raw.indexOf(':')
        if (colon < 0) return null
        val kind = IdentityKind.fromKey(raw.substring(0, colon).trim()) ?: return null
        val id = raw.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (id < FIRST_RESERVED || id > MAX_ID) return null
        return Identity(kind, id)
    }

    /** Text for cards and subtitles. A not-yet-assigned identity reads as plain "undefined". */
    fun display(raw: String): String =
        parse(raw)?.let { format(it.kind, it.id) } ?: IdentityKind.UNDEFINED.key

    /** True for 1..[LAST_RESERVED], the range held back for the system. */
    fun isReserved(id: Int): Boolean = id in FIRST_RESERVED..LAST_RESERVED

    /** The IDs already taken inside [kind]'s pool, from a list of stored identity strings. */
    fun idsOfKind(identities: Collection<String>, kind: IdentityKind): Set<Int> {
        val out = HashSet<Int>()
        for (raw in identities) {
            val p = parse(raw) ?: continue
            if (p.kind == kind) out.add(p.id)
        }
        return out
    }

    /**
     * The smallest number >= [FIRST_ASSIGNABLE] that is not in [taken], or null
     * when everything up to [MAX_ID] is in use. Reserved numbers in [taken] are
     * irrelevant — they are never handed out anyway.
     */
    fun smallestFree(taken: Collection<Int>, max: Int = MAX_ID): Int? {
        val sorted = taken.filter { it >= FIRST_ASSIGNABLE }.distinct().sorted()
        var candidate = FIRST_ASSIGNABLE
        for (t in sorted) {
            if (t == candidate) candidate++ else if (t > candidate) break
        }
        return if (candidate > max) null else candidate
    }

    /** Smallest free ID in [kind]'s pool given every OTHER task's stored identity. */
    fun nextId(kind: IdentityKind, othersIdentities: Collection<String>, max: Int = MAX_ID): Int? =
        smallestFree(idsOfKind(othersIdentities, kind), max)

    /**
     * Turns what a caller asked for into an identity that is valid right now.
     *
     *  • A well-formed, assignable, unused `kind:id` is kept as asked (this is
     *    what lets an edit that leaves the kind alone keep its number).
     *  • Anything else — empty, unknown kind, reserved number, number already
     *    used by another task — becomes the smallest free ID in the asked
     *    kind's pool ([IdentityKind.DEFAULT] when no kind can be read).
     *
     * Returns null only when that pool is completely full. [max] is the pool's
     * ceiling ([MAX_ID] in production; a parameter so a full pool is testable
     * without building four million rows).
     */
    fun resolve(requested: String, othersIdentities: Collection<String>, max: Int = MAX_ID): String? {
        val asked = parse(requested)
        val kind = asked?.kind ?: parseKind(requested) ?: IdentityKind.DEFAULT
        val used = idsOfKind(othersIdentities, kind)
        if (asked != null && !isReserved(asked.id) && asked.id !in used) {
            return format(asked.kind, asked.id)
        }
        val id = smallestFree(used, max) ?: return null
        return format(kind, id)
    }

    /**
     * Repairs a whole table: returns `taskId → new identity` for every task whose
     * identity is missing, malformed, reserved or a duplicate. [rows] must be in
     * creation order — the OLDER task keeps a contested number, the newer one is
     * renumbered. Used after sync and restore, which can bring in rows with no
     * (or someone else's) identity.
     */
    fun repair(rows: List<IdentityRow>, max: Int = MAX_ID): Map<String, String> {
        val taken = HashMap<IdentityKind, MutableSet<Int>>()
        val needsNew = ArrayList<Pair<String, IdentityKind>>()
        for (row in rows) {
            val p = parse(row.identity)
            val ok = p != null && !isReserved(p.id) &&
                taken.getOrPut(p.kind) { HashSet() }.add(p.id)
            if (!ok) needsNew.add(row.id to (p?.kind ?: parseKind(row.identity) ?: IdentityKind.DEFAULT))
        }
        val out = LinkedHashMap<String, String>()
        for ((taskId, kind) in needsNew) {
            val pool = taken.getOrPut(kind) { HashSet() }
            val id = smallestFree(pool, max) ?: throw IdentityPoolFullException(kind)
            pool.add(id)
            out[taskId] = format(kind, id)
        }
        return out
    }
}
