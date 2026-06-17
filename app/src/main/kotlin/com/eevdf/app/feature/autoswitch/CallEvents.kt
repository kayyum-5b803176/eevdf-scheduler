package com.eevdf.app.feature.autoswitch

import androidx.lifecycle.MutableLiveData

/**
 * Process-level singleton that carries phone-state events from
 * [CallStateReceiver] (a BroadcastReceiver that has no direct ViewModel
 * access) to [MainActivity] (which observes it and delegates to the ViewModel).
 *
 * LiveData is used so the event is delivered on the main thread and
 * survives configuration changes without double-firing.
 */
object CallEvents {

    enum class Type { CALL_STARTED, CALL_ENDED }

    /** Null = no pending event. Set to null after consuming. */
    val event: MutableLiveData<Type?> = MutableLiveData(null)
}
