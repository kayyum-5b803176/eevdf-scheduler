package com.eevdf.scheduler.viewmodel;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000b\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010 \n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\r\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0002\b\u0014\u0018\u00002\u00020\u0001B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\u000e\u0010,\u001a\u00020-2\u0006\u0010.\u001a\u00020\u0007J\u0010\u0010/\u001a\u0002002\u0006\u00101\u001a\u00020\u000fH\u0002J\u0006\u00102\u001a\u00020-J\u0006\u00103\u001a\u000200J\u000e\u00104\u001a\u00020-2\u0006\u0010.\u001a\u00020\u0007J\u000e\u00105\u001a\u00020-2\u0006\u0010.\u001a\u00020\u0007J\b\u00106\u001a\u000200H\u0014J\b\u00107\u001a\u000200H\u0002J\u0006\u00108\u001a\u000200J\u0010\u00109\u001a\u0002002\u0006\u0010:\u001a\u00020\u000fH\u0002J\u0006\u0010;\u001a\u00020-J\u0006\u0010<\u001a\u000200J\u0006\u0010=\u001a\u00020-J\u000e\u0010>\u001a\u0002002\u0006\u0010.\u001a\u00020\u0007J\u0006\u0010?\u001a\u000200J\u0006\u0010@\u001a\u000200J\u0010\u0010A\u001a\u0002002\u0006\u0010B\u001a\u00020\rH\u0002J\u000e\u0010C\u001a\u00020-2\u0006\u0010.\u001a\u00020\u0007R\u0016\u0010\u0005\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u00070\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001a\u0010\b\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\t0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\n\u001a\b\u0012\u0004\u0012\u00020\u000b0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\f\u001a\b\u0012\u0004\u0012\u00020\r0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u000e\u001a\b\u0012\u0004\u0012\u00020\u000f0\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0016\u0010\u0010\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u00110\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001d\u0010\u0012\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\t0\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0014\u0010\u0015R\u001d\u0010\u0016\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\t0\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0017\u0010\u0015R\u001d\u0010\u0018\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\t0\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0019\u0010\u0015R\u0010\u0010\u001a\u001a\u0004\u0018\u00010\u001bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0019\u0010\u001c\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u00070\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001d\u0010\u0015R\u000e\u0010\u001e\u001a\u00020\u001fX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001d\u0010 \u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\t0\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b!\u0010\u0015R\u000e\u0010\"\u001a\u00020\u000fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010#\u001a\u00020\u000fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0017\u0010$\u001a\b\u0012\u0004\u0012\u00020\u000b0\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b%\u0010\u0015R\u0017\u0010&\u001a\b\u0012\u0004\u0012\u00020\r0\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b\'\u0010\u0015R\u0017\u0010(\u001a\b\u0012\u0004\u0012\u00020\u000f0\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b)\u0010\u0015R\u0019\u0010*\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u00110\u0013\u00a2\u0006\b\n\u0000\u001a\u0004\b+\u0010\u0015\u00a8\u0006D"}, d2 = {"Lcom/eevdf/scheduler/viewmodel/TaskViewModel;", "Landroidx/lifecycle/AndroidViewModel;", "application", "Landroid/app/Application;", "(Landroid/app/Application;)V", "_currentTask", "Landroidx/lifecycle/MutableLiveData;", "Lcom/eevdf/scheduler/model/Task;", "_scheduleOrder", "", "_stats", "Lcom/eevdf/scheduler/scheduler/SchedulerStats;", "_timerRunning", "", "_timerSeconds", "", "_toastMessage", "", "activeTasks", "Landroidx/lifecycle/LiveData;", "getActiveTasks", "()Landroidx/lifecycle/LiveData;", "allTasks", "getAllTasks", "completedTasks", "getCompletedTasks", "countDownTimer", "Landroid/os/CountDownTimer;", "currentTask", "getCurrentTask", "repository", "Lcom/eevdf/scheduler/db/TaskRepository;", "scheduleOrder", "getScheduleOrder", "sessionElapsed", "sessionStartSeconds", "stats", "getStats", "timerRunning", "getTimerRunning", "timerSeconds", "getTimerSeconds", "toastMessage", "getToastMessage", "addTask", "Lkotlinx/coroutines/Job;", "task", "applyVruntimeUpdate", "", "ranSeconds", "clearCompleted", "clearToast", "deleteTask", "markCompleted", "onCleared", "onTimerFinished", "pauseTimer", "persistTimerState", "secondsLeft", "refreshSchedule", "resetTimer", "scheduleNext", "setCurrentTask", "skipTask", "startTimer", "stopTimer", "completed", "updateTask", "app_debug"})
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
    @org.jetbrains.annotations.Nullable()
    private android.os.CountDownTimer countDownTimer;
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