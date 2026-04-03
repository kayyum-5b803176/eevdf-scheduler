package com.eevdf.scheduler.ui;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000@\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0003\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\t\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\b\b\u00c6\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u000e\u0010\n\u001a\u00020\u000b2\u0006\u0010\f\u001a\u00020\rJ\u000e\u0010\u000e\u001a\u00020\u000b2\u0006\u0010\f\u001a\u00020\rJ\u000e\u0010\u000f\u001a\u00020\u000b2\u0006\u0010\f\u001a\u00020\rJ\u000e\u0010\u0010\u001a\u00020\u00042\u0006\u0010\u0011\u001a\u00020\u0012J\u0018\u0010\u0013\u001a\u00020\u00142\u0006\u0010\f\u001a\u00020\r2\u0006\u0010\u0015\u001a\u00020\u0007H\u0002J \u0010\u0016\u001a\u00020\u000b2\u0006\u0010\f\u001a\u00020\r2\u0006\u0010\u0017\u001a\u00020\u00072\u0006\u0010\u0018\u001a\u00020\u0019H\u0002J \u0010\u001a\u001a\u00020\u000b2\u0006\u0010\f\u001a\u00020\r2\u0006\u0010\u001b\u001a\u00020\u00042\b\u0010\u001c\u001a\u0004\u0018\u00010\u0004J\u001e\u0010\u001d\u001a\u00020\u000b2\u0006\u0010\f\u001a\u00020\r2\u0006\u0010\u001b\u001a\u00020\u00042\u0006\u0010\u001e\u001a\u00020\u0012J\u001e\u0010\u001f\u001a\u00020\u000b2\u0006\u0010\f\u001a\u00020\r2\u0006\u0010\u001b\u001a\u00020\u00042\u0006\u0010 \u001a\u00020\u0012R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\u0007X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\u0007X\u0082T\u00a2\u0006\u0002\n\u0000\u00a8\u0006!"}, d2 = {"Lcom/eevdf/scheduler/ui/NotificationHelper;", "", "()V", "CHANNEL_ID_ALARM", "", "CHANNEL_ID_TIMER", "NOTIFICATION_ID_DONE", "", "NOTIFICATION_ID_EXPIRED", "NOTIFICATION_ID_TIMER", "cancelExpired", "", "context", "Landroid/content/Context;", "cancelTimer", "createChannel", "formatElapsed", "seconds", "", "mainActivityIntent", "Landroid/app/PendingIntent;", "requestCode", "safeNotify", "id", "notification", "Landroid/app/Notification;", "showTimerDone", "taskName", "nextTaskName", "showTimerExpired", "elapsedSeconds", "showTimerRunning", "remainingSeconds", "app_debug"})
public final class NotificationHelper {
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String CHANNEL_ID_TIMER = "eevdf_timer_channel";
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String CHANNEL_ID_ALARM = "eevdf_alarm_channel";
    private static final int NOTIFICATION_ID_TIMER = 1001;
    private static final int NOTIFICATION_ID_DONE = 1002;
    private static final int NOTIFICATION_ID_EXPIRED = 1003;
    @org.jetbrains.annotations.NotNull()
    public static final com.eevdf.scheduler.ui.NotificationHelper INSTANCE = null;
    
    private NotificationHelper() {
        super();
    }
    
    public final void createChannel(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
    }
    
    public final void showTimerRunning(@org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    java.lang.String taskName, long remainingSeconds) {
    }
    
    public final void cancelTimer(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
    }
    
    /**
     * Shows an ongoing, updating notification when the timer has expired.
     * [elapsedSeconds] counts up from 0 — same UX as Google Clock's "Timer expired".
     * Has a dedicated "Stop" action button that fires AlarmStopReceiver.
     */
    public final void showTimerExpired(@org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    java.lang.String taskName, long elapsedSeconds) {
    }
    
    public final void cancelExpired(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
    }
    
    public final void showTimerDone(@org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    java.lang.String taskName, @org.jetbrains.annotations.Nullable()
    java.lang.String nextTaskName) {
    }
    
    private final android.app.PendingIntent mainActivityIntent(android.content.Context context, int requestCode) {
        return null;
    }
    
    private final void safeNotify(android.content.Context context, int id, android.app.Notification notification) {
    }
    
    /**
     * Format seconds as "0:05", "1:23", "10:00" — same style as Google Clock
     */
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String formatElapsed(long seconds) {
        return null;
    }
}