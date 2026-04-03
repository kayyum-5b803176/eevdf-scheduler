package com.eevdf.scheduler.ui;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u00000\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0010\t\n\u0000\b\u00c6\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u000e\u0010\t\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\fJ\u000e\u0010\r\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\fJ \u0010\u000e\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\u000f\u001a\u00020\u00042\b\u0010\u0010\u001a\u0004\u0018\u00010\u0004J\u001e\u0010\u0011\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\u000f\u001a\u00020\u00042\u0006\u0010\u0012\u001a\u00020\u0013R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\u0007X\u0082T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0014"}, d2 = {"Lcom/eevdf/scheduler/ui/NotificationHelper;", "", "()V", "CHANNEL_ID", "", "CHANNEL_NAME", "NOTIFICATION_ID_DONE", "", "NOTIFICATION_ID_TIMER", "cancelTimer", "", "context", "Landroid/content/Context;", "createChannel", "showTimerDone", "taskName", "nextTaskName", "showTimerRunning", "remainingSeconds", "", "app_debug"})
public final class NotificationHelper {
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String CHANNEL_ID = "eevdf_timer_channel";
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String CHANNEL_NAME = "EEVDF Task Timer";
    private static final int NOTIFICATION_ID_TIMER = 1001;
    private static final int NOTIFICATION_ID_DONE = 1002;
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
    
    public final void showTimerDone(@org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    java.lang.String taskName, @org.jetbrains.annotations.Nullable()
    java.lang.String nextTaskName) {
    }
    
    public final void cancelTimer(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
    }
}