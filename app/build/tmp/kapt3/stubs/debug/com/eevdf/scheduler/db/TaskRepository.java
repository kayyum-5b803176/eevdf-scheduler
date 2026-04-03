package com.eevdf.scheduler.db;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000:\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0010\u0002\n\u0002\b\u0007\n\u0002\u0010\u000e\n\u0002\b\b\n\u0002\u0010\t\n\u0002\b\u0002\u0018\u00002\u00020\u0001B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\u000e\u0010\u000f\u001a\u00020\u0010H\u0086@\u00a2\u0006\u0002\u0010\u0011J\u0016\u0010\u0012\u001a\u00020\u00102\u0006\u0010\u0013\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u0010\u0014J\u0014\u0010\u0015\u001a\b\u0012\u0004\u0012\u00020\b0\u0007H\u0086@\u00a2\u0006\u0002\u0010\u0011J\u0018\u0010\u0016\u001a\u0004\u0018\u00010\b2\u0006\u0010\u0017\u001a\u00020\u0018H\u0086@\u00a2\u0006\u0002\u0010\u0019J\u0016\u0010\u001a\u001a\u00020\u00102\u0006\u0010\u0013\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u0010\u0014J\u0016\u0010\u001b\u001a\u00020\u00102\u0006\u0010\u0013\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u0010\u0014J\u0010\u0010\u001c\u001a\u0004\u0018\u00010\bH\u0086@\u00a2\u0006\u0002\u0010\u0011J\u000e\u0010\u001d\u001a\u00020\u0010H\u0086@\u00a2\u0006\u0002\u0010\u0011J\u0016\u0010\u001e\u001a\u00020\u00102\u0006\u0010\u0013\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u0010\u0014J\u001e\u0010\u001f\u001a\u00020\u00102\u0006\u0010\u0013\u001a\u00020\b2\u0006\u0010 \u001a\u00020!H\u0086@\u00a2\u0006\u0002\u0010\"R\u001d\u0010\u0005\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u00070\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\t\u0010\nR\u001d\u0010\u000b\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u00070\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\f\u0010\nR\u001d\u0010\r\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\b0\u00070\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000e\u0010\nR\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006#"}, d2 = {"Lcom/eevdf/scheduler/db/TaskRepository;", "", "dao", "Lcom/eevdf/scheduler/db/TaskDao;", "(Lcom/eevdf/scheduler/db/TaskDao;)V", "activeTasks", "Landroidx/lifecycle/LiveData;", "", "Lcom/eevdf/scheduler/model/Task;", "getActiveTasks", "()Landroidx/lifecycle/LiveData;", "allTasks", "getAllTasks", "completedTasks", "getCompletedTasks", "clearCompleted", "", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "delete", "task", "(Lcom/eevdf/scheduler/model/Task;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getScheduleOrder", "getTaskById", "id", "", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "insert", "markCompleted", "selectNextTask", "stopAll", "update", "updateVruntimeAfterRun", "secondsRan", "", "(Lcom/eevdf/scheduler/model/Task;JLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "app_debug"})
public final class TaskRepository {
    @org.jetbrains.annotations.NotNull()
    private final com.eevdf.scheduler.db.TaskDao dao = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> allTasks = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> activeTasks = null;
    @org.jetbrains.annotations.NotNull()
    private final androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> completedTasks = null;
    
    public TaskRepository(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.db.TaskDao dao) {
        super();
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
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object insert(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object update(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object delete(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object markCompleted(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object stopAll(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object clearCompleted(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getTaskById(@org.jetbrains.annotations.NotNull()
    java.lang.String id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.eevdf.scheduler.model.Task> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object updateVruntimeAfterRun(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, long secondsRan, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object selectNextTask(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.eevdf.scheduler.model.Task> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getScheduleOrder(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.eevdf.scheduler.model.Task>> $completion) {
        return null;
    }
}