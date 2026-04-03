package com.eevdf.scheduler.ui;

/**
 * Full-screen alarm activity — shown over the lock screen when a task timer expires.
 * Behaves like Google Clock's alarm screen: wakes the display, shows over other apps,
 * and keeps ringing until the user taps Stop.
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000@\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0005\u0018\u0000 \u00172\u00020\u0001:\u0001\u0017B\u0005\u00a2\u0006\u0002\u0010\u0002J\u0012\u0010\u0010\u001a\u00020\u00112\b\u0010\u0012\u001a\u0004\u0018\u00010\u0013H\u0014J\b\u0010\u0014\u001a\u00020\u0011H\u0014J\b\u0010\u0015\u001a\u00020\u0011H\u0014J\b\u0010\u0016\u001a\u00020\u0011H\u0014R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0007\u001a\u00020\bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\nX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000b\u001a\u00020\fX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\r\u001a\u00020\u000eX\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000f\u001a\u00020\u000eX\u0082.\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0018"}, d2 = {"Lcom/eevdf/scheduler/ui/AlarmActivity;", "Landroidx/appcompat/app/AppCompatActivity;", "()V", "btnStop", "Lcom/google/android/material/button/MaterialButton;", "elapsedRunnable", "Ljava/lang/Runnable;", "elapsedSeconds", "", "handler", "Landroid/os/Handler;", "stopReceiver", "Landroid/content/BroadcastReceiver;", "tvElapsed", "Landroid/widget/TextView;", "tvTaskName", "onCreate", "", "savedInstanceState", "Landroid/os/Bundle;", "onDestroy", "onPause", "onResume", "Companion", "app_debug"})
public final class AlarmActivity extends androidx.appcompat.app.AppCompatActivity {
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String EXTRA_TASK_NAME = "task_name";
    private android.widget.TextView tvTaskName;
    private android.widget.TextView tvElapsed;
    private com.google.android.material.button.MaterialButton btnStop;
    @org.jetbrains.annotations.NotNull()
    private final android.os.Handler handler = null;
    private long elapsedSeconds = 0L;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.Runnable elapsedRunnable = null;
    
    /**
     * Listens for the ViewModel stopping the alarm (e.g. user taps Stop in MainActivity)
     */
    @org.jetbrains.annotations.NotNull()
    private final android.content.BroadcastReceiver stopReceiver = null;
    @org.jetbrains.annotations.NotNull()
    public static final com.eevdf.scheduler.ui.AlarmActivity.Companion Companion = null;
    
    public AlarmActivity() {
        super();
    }
    
    @java.lang.Override()
    protected void onCreate(@org.jetbrains.annotations.Nullable()
    android.os.Bundle savedInstanceState) {
    }
    
    @java.lang.Override()
    protected void onResume() {
    }
    
    @java.lang.Override()
    protected void onPause() {
    }
    
    @java.lang.Override()
    protected void onDestroy() {
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000 \n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u0016\u0010\u0005\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\u0006\u0010\t\u001a\u00020\u0004R\u000e\u0010\u0003\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\n"}, d2 = {"Lcom/eevdf/scheduler/ui/AlarmActivity$Companion;", "", "()V", "EXTRA_TASK_NAME", "", "createIntent", "Landroid/content/Intent;", "context", "Landroid/content/Context;", "taskName", "app_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
        
        /**
         * Launch this activity from anywhere (notification, broadcast).
         */
        @org.jetbrains.annotations.NotNull()
        public final android.content.Intent createIntent(@org.jetbrains.annotations.NotNull()
        android.content.Context context, @org.jetbrains.annotations.NotNull()
        java.lang.String taskName) {
            return null;
        }
    }
}