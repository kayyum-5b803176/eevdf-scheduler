package com.eevdf.scheduler.adapter

/**
 * Unit-format wrappers for [TaskAdapter].
 *
 * These three extension functions are the only callers of the pure SI helpers
 * in [TaskAdapterFormatters]. They read [TaskAdapter.unitFormatEnabled] so the
 * pure formatter functions stay stateless.
 *
 * Domain boundary: anything that asks "plain value or SI?" lives here.
 * Pure conversion math lives in TaskAdapterFormatters.kt.
 */

/** VRT / VDL: 2 d.p. always; SI suffixes when unitFormatEnabled. */
internal fun TaskAdapter.fmtFloat(v: Double): String =
    if (unitFormatEnabled) siFloat(v) else "%.2f".format(v)

/** Runs: plain int when off; SI with no trailing ".0" when on. */
internal fun TaskAdapter.fmtInt(v: Int): String =
    if (unitFormatEnabled) siInt(v) else v.toString()

/** TRT: full unit string when off; top-2 units when on. */
internal fun TaskAdapter.fmtDur(totalSec: Long): String =
    if (unitFormatEnabled) siDur(totalSec) else formatTRT(totalSec)
