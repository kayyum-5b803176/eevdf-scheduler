package com.eevdf.scheduler.ui;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000*\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\b\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\t\n\u0000\b\u00c6\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u000e\u0010\u0005\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\bJ\u000e\u0010\t\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\fR\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\r"}, d2 = {"Lcom/eevdf/scheduler/ui/NotificationHelper;", "", "()V", "NOTIFICATION_ID_EXPIRED", "", "cancelExpired", "", "context", "Landroid/content/Context;", "formatElapsed", "", "seconds", "", "app_debug"})
public final class NotificationHelper {
    private static final int NOTIFICATION_ID_EXPIRED = 3001;
    @org.jetbrains.annotations.NotNull()
    public static final com.eevdf.scheduler.ui.NotificationHelper INSTANCE = null;
    
    private NotificationHelper() {
        super();
    }
    
    public final void cancelExpired(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
    }
    
    /**
     * Format seconds as "0:05", "1:23", "1:02:34" — same style as Google Clock
     */
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String formatElapsed(long seconds) {
        return null;
    }
}