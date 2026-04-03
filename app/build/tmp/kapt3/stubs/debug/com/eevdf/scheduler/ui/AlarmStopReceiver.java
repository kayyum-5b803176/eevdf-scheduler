package com.eevdf.scheduler.ui;

/**
 * Receives the "Stop" action from the expired-timer notification.
 * Sends a local broadcast that MainActivity listens to, so it can call
 * viewModel.stopAlarmSound() and dismiss the alarm UI.
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000 \n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\u0018\u0000 \t2\u00020\u0001:\u0001\tB\u0005\u00a2\u0006\u0002\u0010\u0002J\u0018\u0010\u0003\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\bH\u0016\u00a8\u0006\n"}, d2 = {"Lcom/eevdf/scheduler/ui/AlarmStopReceiver;", "Landroid/content/BroadcastReceiver;", "()V", "onReceive", "", "context", "Landroid/content/Context;", "intent", "Landroid/content/Intent;", "Companion", "app_debug"})
public final class AlarmStopReceiver extends android.content.BroadcastReceiver {
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_STOP_ALARM = "com.eevdf.scheduler.ACTION_STOP_ALARM";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_TIMER_EXPIRED = "com.eevdf.scheduler.ACTION_TIMER_EXPIRED";
    @org.jetbrains.annotations.NotNull()
    public static final com.eevdf.scheduler.ui.AlarmStopReceiver.Companion Companion = null;
    
    public AlarmStopReceiver() {
        super();
    }
    
    @java.lang.Override()
    public void onReceive(@org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    android.content.Intent intent) {
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0014\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0002\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0006"}, d2 = {"Lcom/eevdf/scheduler/ui/AlarmStopReceiver$Companion;", "", "()V", "ACTION_STOP_ALARM", "", "ACTION_TIMER_EXPIRED", "app_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
}