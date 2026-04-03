package com.eevdf.scheduler.viewmodel;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000d\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0010\t\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010 \n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u000b\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\r\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0002\b\u0018\u0018\u00002\u00020\u0001B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\u000e\u00103\u001a\u0002042\u0006\u00105\u001a\u00020\u000bJ\u0010\u00106\u001a\u0002072\u0006\u00108\u001a\u00020\u0007H\u0002J\u0006\u00109\u001a\u000204J\u0006\u0010:\u001a\u000207J\u000e\u0010;\u001a\u0002042\u0006\u00105\u001a\u00020\u000bJ\u000e\u0010<\u001a\u0002042\u0006\u00105\u001a\u00020\u000bJ\b\u0010=\u001a\u000207H\u0014J\b\u0010>\u001a\u000207H\u0002J\u0006\u0010?\u001a\u000207J\u0010\u0010@\u001a\u0002072\u0006\u0010A\u001a\u00020\u0007H\u0002J\u0006\u0010B\u001a\u000204J\u0006\u0010C\u001a\u000207J\u0006\u0010D\u001a\u000204J\u000e\u0010E\u001a\u0002072\u0006\u00105\u001a\u00020\u000bJ\u0006\u0010F\u001a\u000207J\u0010\u0010G\u001a\u0002072\u0006\u0010H\u001a\u00020\tH\u0002J\u0006\u0010I\u001a\u000207J\u0006\u0010J\u001a\u000207J\b\u0010K\u001a\u000207H\u0002J\u0010\u0010L\u001a\u0002072\u0006\u0010M\u001a\u00020\u0011H\u0002J\u000e\u0010N\u001a\u0002042\u0006\u00105\u001a\u00020\u000bR\u0014\u0010\u0005\u001a\b\u0012\u0004\u0012\u00020\u00070\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0016\u0010\b\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\t0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0016\u0010\n\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u000b0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001a\u0010\f\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u000b0\r0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u000e\u001a\b\u0012\u0004\u0012\u00020\u000f0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u0010\u001a\b\u0012\u0004\u0012\u00020\u00110\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u0012\u001a\b\u0012\u0004\u0012\u00020\u00070\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0016\u0010\u0013\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\t0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001d\u0010\u0014\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u000b0\r0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0016\u0010\u0017R\u0017\u0010\u0018\u001a\b\u0012\u0004\u0012\u00020\u00070\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0019\u0010\u0017R\u0019\u0010\u001a\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\t0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001b\u0010\u0017R\u001d\u0010\u001c\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u000b0\r0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001d\u0010\u0017R\u001d\u0010\u001e\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u000b0\r0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001f\u0010\u0017R\u0010\u0010 \u001a\u0004\u0018\u00010!X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0019\u0010\"\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u000b0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b#\u0010\u0017R\u0010\u0010$\u001a\u0004\u0018\u00010!X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010%\u001a\u00020&X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001d\u0010\'\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u000b0\r0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b(\u0010\u0017R\u000e\u0010)\u001a\u00020\u0007X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010*\u001a\u00020\u0007X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0017\u0010+\u001a\b\u0012\u0004\u0012\u00020\u000f0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b,\u0010\u0017R\u0017\u0010-\u001a\b\u0012\u0004\u0012\u00020\u00110\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b.\u0010\u0017R\u0017\u0010/\u001a\b\u0012\u0004\u0012\u00020\u00070\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b0\u0010\u0017R\u0019\u00101\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\t0\u0015\u00a2\u0006\b\n\u0000\u001a\u0004\b2\u0010\u0017\u00a8\u0006O"}, d2 = {"Lcom/eevdf/scheduler/viewmodel/TaskViewModel;", "Landroidx/lifecycle/AndroidViewModel;", "application", "Landroid/app/Application;", "(Landroid/app/Application;)V", "_alarmElapsedSeconds", "Landroidx/lifecycle/MutableLiveData;", "", "_alarmTaskName", "", "_currentTask", "Lcom/eevdf/scheduler/model/Task;", "_scheduleOrder", "", "_stats", "Lcom/eevdf/scheduler/scheduler/SchedulerStats;", "_timerRunning", "", "_timerSeconds", "_toastMessage", "activeTasks", "Landroidx/lifecycle/LiveData;", "getActiveTasks", "()Landroidx/lifecycle/LiveData;", "alarmElapsedSeconds", "getAlarmElapsedSeconds", "alarmTaskName", "getAlarmTaskName", "allTasks", "getAllTasks", "completedTasks", "getCompletedTasks", "countDownTimer", "Landroid/os/CountDownTimer;", "currentTask", "getCurrentTask", "overrunTimer", "repository", "Lcom/eevdf/scheduler/db/TaskRepository;", "scheduleOrder", "getScheduleOrder", "sessionElapsed", "sessionStartSeconds", "stats", "getStats", "timerRunning", "getTimerRunning", "timerSeconds", "getTimerSeconds", "toastMessage", "getToastMessage", "addTask", "Lkotlinx/coroutines/Job;", "task", "applyVruntimeUpdate", "", "ranSeconds", "clearCompleted", "clearToast", "deleteTask", "markCompleted", "onCleared", "onTimerFinished", "pauseTimer", "persistTimerState", "secondsLeft", "refreshSchedule", "resetTimer", "scheduleNext", "setCurrentTask", "skipTask", "startInAppOverrunCounter", "taskName", "startTimer", "stopAlarmSound", "stopOverrunCounter", "stopTimer", "completed", "updateTask", "app_debug"})
public final class TaskViewModel extends androidx.lifecycle.AndroidViewModel {
    @org.jetbrains.annotations.NotNull()
    private final com.eevdf.scheduler.db.TaskRepository repository = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> allTasks = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> activeTasks = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> completedTasks = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<com.eevdf.scheduler.model.Task> _currentTask = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<com.eevdf.scheduler.model.Task> currentTask = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<java.lang.Long> _timerSeconds = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.lang.Long> timerSeconds = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<java.lang.Boolean> _timerRunning = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.lang.Boolean> timerRunning = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<java.util.List<com.eevdf.scheduler.model.Task>> _scheduleOrder = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> scheduleOrder = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<com.eevdf.scheduler.scheduler.SchedulerStats> _stats = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<com.eevdf.scheduler.scheduler.SchedulerStats> stats = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<java.lang.String> _toastMessage = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.lang.String> toastMessage = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<java.lang.String> _alarmTaskName = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.lang.String> alarmTaskName = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.MutableLiveData<java.lang.Long> _alarmElapsedSeconds = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.lang.Long> alarmElapsedSeconds = null;
    @org.jetbrains.annotations.Nullable()
    private android.os.CountDownTimer countDownTimer;
    @org.jetbrains.annotations.Nullable()
    private android.os.CountDownTimer overrunTimer;
    private long sessionStartSeconds = 0L;
    private long sessionElapsed = 0L;
    
    public TaskViewModel(@org.jetbrains.annotations.NotNull()
    android.app.Application application) {
        super(null);
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> getAllTasks() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> getActiveTasks() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> getCompletedTasks() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<com.eevdf.scheduler.model.Task> getCurrentTask() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.lang.Long> getTimerSeconds() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.lang.Boolean> getTimerRunning() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> getScheduleOrder() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<com.eevdf.scheduler.scheduler.SchedulerStats> getStats() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.lang.String> getToastMessage() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.lang.String> getAlarmTaskName() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final androidx.lifecycle.LiveData<java.lang.Long> getAlarmElapsedSeconds() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final kotlinx.coroutines.Job addTask(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final kotlinx.coroutines.Job updateTask(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final kotlinx.coroutines.Job deleteTask(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final kotlinx.coroutines.Job markCompleted(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final kotlinx.coroutines.Job clearCompleted() {
        return null;
    }
    
    public final void clearToast() {
    }
    
    @org.jetbrains.annotations.NotNull()
    public final kotlinx.coroutines.Job scheduleNext() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final kotlinx.coroutines.Job refreshSchedule() {
        return null;
    }
    
    public final void startTimer() {
    }
    
    public final void pauseTimer() {
    }
    
    public final void resetTimer() {
    }
    
    public final void skipTask() {
    }
    
    private final void onTimerFinished() {
    }
    
    /**
     * Drives the in-app banner elapsed counter — mirrors what the service shows in notification
     */
    private final void startInAppOverrunCounter(java.lang.String taskName) {
    }
    
    private final void stopOverrunCounter() {
    }
    
    /**
     * Called when user dismisses the alarm from any surface (banner, AlarmActivity, notification)
     */
    public final void stopAlarmSound() {
    }
    
    private final void stopTimer(boolean completed) {
    }
    
    private final void persistTimerState(long secondsLeft) {
    }
    
    private final void applyVruntimeUpdate(long ranSeconds) {
    }
    
    public final void setCurrentTask(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task) {
    }
    
    @java.lang.Override()
    protected void onCleared() {
    }
}