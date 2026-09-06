package com.eevdf.kernel.eventbus

import org.junit.Assert.assertEquals
import org.junit.Test

class TopicsTest {

    @Test
    fun `every known topic name is unique`() {
        val names = listOf(
            Topics.TIMER_EXPIRED.name,
            Topics.ALARM_RINGING.name,
            Topics.ALARM_STOPPED.name,
            Topics.REALTIME_WINDOW_EXPIRED.name,
            Topics.TASK_SAVED.name,
            Topics.PHONE_CALL_STATE_CHANGED.name,
            Topics.OVERLAY_SHOWN.name,
            Topics.TASK_CONFLICT_DETECTED.name,
            Topics.BACKUP_EXPORT_REQUESTED.name,
            Topics.BACKUP_IMPORT_REQUESTED.name,
        )
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `topic names match the implementation prompt's known-topics table`() {
        assertEquals("timer.expired", Topics.TIMER_EXPIRED.name)
        assertEquals("alarm.ringing", Topics.ALARM_RINGING.name)
        assertEquals("alarm.stopped", Topics.ALARM_STOPPED.name)
        assertEquals("realtime-window.expired", Topics.REALTIME_WINDOW_EXPIRED.name)
        assertEquals("task.saved", Topics.TASK_SAVED.name)
        assertEquals("phone.call-state-changed", Topics.PHONE_CALL_STATE_CHANGED.name)
        assertEquals("overlay.shown", Topics.OVERLAY_SHOWN.name)
        assertEquals("task.conflict-detected", Topics.TASK_CONFLICT_DETECTED.name)
    }
}
