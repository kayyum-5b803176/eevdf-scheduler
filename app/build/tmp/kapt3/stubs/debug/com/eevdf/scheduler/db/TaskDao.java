package com.eevdf.scheduler.db;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000.\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0010 \n\u0002\b\u0005\n\u0002\u0010\u000e\n\u0002\b\u0005\bg\u0018\u00002\u00020\u0001J\u000e\u0010\u0002\u001a\u00020\u0003H\u00a7@\u00a2\u0006\u0002\u0010\u0004J\u0016\u0010\u0005\u001a\u00020\u00032\u0006\u0010\u0006\u001a\u00020\u0007H\u00a7@\u00a2\u0006\u0002\u0010\bJ\u0014\u0010\t\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\u000b0\nH\'J\u0014\u0010\f\u001a\b\u0012\u0004\u0012\u00020\u00070\u000bH\u00a7@\u00a2\u0006\u0002\u0010\u0004J\u0014\u0010\r\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\u000b0\nH\'J\u0014\u0010\u000e\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\u000b0\nH\'J\u0018\u0010\u000f\u001a\u0004\u0018\u00010\u00072\u0006\u0010\u0010\u001a\u00020\u0011H\u00a7@\u00a2\u0006\u0002\u0010\u0012J\u0016\u0010\u0013\u001a\u00020\u00032\u0006\u0010\u0006\u001a\u00020\u0007H\u00a7@\u00a2\u0006\u0002\u0010\bJ\u000e\u0010\u0014\u001a\u00020\u0003H\u00a7@\u00a2\u0006\u0002\u0010\u0004J\u0016\u0010\u0015\u001a\u00020\u00032\u0006\u0010\u0006\u001a\u00020\u0007H\u00a7@\u00a2\u0006\u0002\u0010\b\u00a8\u0006\u0016"}, d2 = {"Lcom/eevdf/scheduler/db/TaskDao;", "", "clearCompleted", "", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "delete", "task", "Lcom/eevdf/scheduler/model/Task;", "(Lcom/eevdf/scheduler/model/Task;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getActiveTasks", "Landroidx/lifecycle/LiveData;", "", "getActiveTasksSync", "getAllTasks", "getCompletedTasks", "getTaskById", "id", "", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "insert", "stopAllRunning", "update", "app_debug"})
@androidx.room.Dao()
public abstract interface TaskDao {
    
    @androidx.room.Query(value = "SELECT * FROM tasks ORDER BY virtualDeadline ASC, priority DESC")
    @org.jetbrains.annotations.NotNull()
    public abstract androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> getAllTasks();
    
    @androidx.room.Query(value = "SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY virtualDeadline ASC")
    @org.jetbrains.annotations.NotNull()
    public abstract androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> getActiveTasks();
    
    @androidx.room.Query(value = "SELECT * FROM tasks WHERE isCompleted = 1 ORDER BY createdAt DESC")
    @org.jetbrains.annotations.NotNull()
    public abstract androidx.lifecycle.LiveData<java.util.List<com.eevdf.scheduler.model.Task>> getCompletedTasks();
    
    @androidx.room.Query(value = "SELECT * FROM tasks WHERE id = :id")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getTaskById(@org.jetbrains.annotations.NotNull()
    java.lang.String id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.eevdf.scheduler.model.Task> $completion);
    
    @androidx.room.Insert(onConflict = 1)
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object insert(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Update()
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object update(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Delete()
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object delete(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.model.Task task, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "DELETE FROM tasks WHERE isCompleted = 1")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object clearCompleted(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "UPDATE tasks SET isRunning = 0")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object stopAllRunning(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion);
    
    @androidx.room.Query(value = "SELECT * FROM tasks WHERE isCompleted = 0")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getActiveTasksSync(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.eevdf.scheduler.model.Task>> $completion);
}