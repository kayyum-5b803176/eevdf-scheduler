package com.eevdf.scheduler.adapter;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000>\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\u0010\u0002\n\u0002\b\u0004\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0010\b\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0006\u0018\u00002\u0012\u0012\u0004\u0012\u00020\u0002\u0012\b\u0012\u00060\u0003R\u00020\u00000\u0001:\u0002\u0019\u001aB_\u0012\u0012\u0010\u0004\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005\u0012\u0012\u0010\u0007\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005\u0012\u0012\u0010\b\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005\u0012\u0012\u0010\t\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005\u0012\b\b\u0002\u0010\n\u001a\u00020\u000b\u00a2\u0006\u0002\u0010\fJ\u001c\u0010\u000f\u001a\u00020\u00062\n\u0010\u0010\u001a\u00060\u0003R\u00020\u00002\u0006\u0010\u0011\u001a\u00020\u0012H\u0016J\u001c\u0010\u0013\u001a\u00060\u0003R\u00020\u00002\u0006\u0010\u0014\u001a\u00020\u00152\u0006\u0010\u0016\u001a\u00020\u0012H\u0016J\u0010\u0010\u0017\u001a\u00020\u00062\b\u0010\u0018\u001a\u0004\u0018\u00010\u000eR\u001a\u0010\b\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001a\u0010\u0007\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001a\u0010\t\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001a\u0010\u0004\u001a\u000e\u0012\u0004\u0012\u00020\u0002\u0012\u0004\u0012\u00020\u00060\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0010\u0010\r\u001a\u0004\u0018\u00010\u000eX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u000bX\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u001b"}, d2 = {"Lcom/eevdf/scheduler/adapter/TaskAdapter;", "Landroidx/recyclerview/widget/ListAdapter;", "Lcom/eevdf/scheduler/model/Task;", "Lcom/eevdf/scheduler/adapter/TaskAdapter$TaskViewHolder;", "onTaskClick", "Lkotlin/Function1;", "", "onDeleteClick", "onCompleteClick", "onRunClick", "showScheduleRank", "", "(Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Z)V", "runningTaskId", "", "onBindViewHolder", "holder", "position", "", "onCreateViewHolder", "parent", "Landroid/view/ViewGroup;", "viewType", "setRunningTask", "id", "TaskDiffCallback", "TaskViewHolder", "app_debug"})
public final class TaskAdapter extends androidx.recyclerview.widget.ListAdapter<com.eevdf.scheduler.model.Task, com.eevdf.scheduler.adapter.TaskAdapter.TaskViewHolder> {
    @org.jetbrains.annotations.NotNull()
    private final kotlin.jvm.functions.Function1<com.eevdf.scheduler.model.Task, kotlin.Unit> onTaskClick = null;
    @org.jetbrains.annotations.NotNull()
    private final kotlin.jvm.functions.Function1<com.eevdf.scheduler.model.Task, kotlin.Unit> onDeleteClick = null;
    @org.jetbrains.annotations.NotNull()
    private final kotlin.jvm.functions.Function1<com.eevdf.scheduler.model.Task, kotlin.Unit> onCompleteClick = null;
    @org.jetbrains.annotations.NotNull()
    private final kotlin.jvm.functions.Function1<com.eevdf.scheduler.model.Task, kotlin.Unit> onRunClick = null;
    private final boolean showScheduleRank = false;
    @org.jetbrains.annotations.Nullable()
    private java.lang.String runningTaskId;
    
    public TaskAdapter(@org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function1<? super com.eevdf.scheduler.model.Task, kotlin.Unit> onTaskClick, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function1<? super com.eevdf.scheduler.model.Task, kotlin.Unit> onDeleteClick, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function1<? super com.eevdf.scheduler.model.Task, kotlin.Unit> onCompleteClick, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function1<? super com.eevdf.scheduler.model.Task, kotlin.Unit> onRunClick, boolean showScheduleRank) {
        super(null);
    }
    
    public final void setRunningTask(@org.jetbrains.annotations.Nullable()
    java.lang.String id) {
    }
    
    @java.lang.Override()
    @org.jetbrains.annotations.NotNull()
    public com.eevdf.scheduler.adapter.TaskAdapter.TaskViewHolder onCreateViewHolder(@org.jetbrains.annotations.NotNull()
    android.view.ViewGroup parent, int viewType) {
        return null;
    }
    
    @java.lang.Override()
    public void onBindViewHolder(@org.jetbrains.annotations.NotNull()
    com.eevdf.scheduler.adapter.TaskAdapter.TaskViewHolder holder, int position) {
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0004\u0018\u00002\b\u0012\u0004\u0012\u00020\u00020\u0001B\u0005\u00a2\u0006\u0002\u0010\u0003J\u0018\u0010\u0004\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u00022\u0006\u0010\u0007\u001a\u00020\u0002H\u0016J\u0018\u0010\b\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u00022\u0006\u0010\u0007\u001a\u00020\u0002H\u0016\u00a8\u0006\t"}, d2 = {"Lcom/eevdf/scheduler/adapter/TaskAdapter$TaskDiffCallback;", "Landroidx/recyclerview/widget/DiffUtil$ItemCallback;", "Lcom/eevdf/scheduler/model/Task;", "()V", "areContentsTheSame", "", "oldItem", "newItem", "areItemsTheSame", "app_debug"})
    public static final class TaskDiffCallback extends androidx.recyclerview.widget.DiffUtil.ItemCallback<com.eevdf.scheduler.model.Task> {
        
        public TaskDiffCallback() {
            super();
        }
        
        @java.lang.Override()
        public boolean areItemsTheSame(@org.jetbrains.annotations.NotNull()
        com.eevdf.scheduler.model.Task oldItem, @org.jetbrains.annotations.NotNull()
        com.eevdf.scheduler.model.Task newItem) {
            return false;
        }
        
        @java.lang.Override()
        public boolean areContentsTheSame(@org.jetbrains.annotations.NotNull()
        com.eevdf.scheduler.model.Task oldItem, @org.jetbrains.annotations.NotNull()
        com.eevdf.scheduler.model.Task newItem) {
            return false;
        }
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u00002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0016\b\u0086\u0004\u0018\u00002\u00020\u0001B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004R\u0011\u0010\u0005\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0007\u0010\bR\u0011\u0010\t\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\n\u0010\bR\u0011\u0010\u000b\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\f\u0010\bR\u0011\u0010\r\u001a\u00020\u000e\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000f\u0010\u0010R\u0011\u0010\u0011\u001a\u00020\u0012\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0013\u0010\u0014R\u0011\u0010\u0015\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0017\u0010\u0018R\u0011\u0010\u0019\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001a\u0010\u0018R\u0011\u0010\u001b\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001c\u0010\u0018R\u0011\u0010\u001d\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001e\u0010\u0018R\u0011\u0010\u001f\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b \u0010\u0018R\u0011\u0010!\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b\"\u0010\u0018R\u0011\u0010#\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b$\u0010\u0018R\u0011\u0010%\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b&\u0010\u0018R\u0011\u0010\'\u001a\u00020\u0016\u00a2\u0006\b\n\u0000\u001a\u0004\b(\u0010\u0018R\u0011\u0010)\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b*\u0010+\u00a8\u0006,"}, d2 = {"Lcom/eevdf/scheduler/adapter/TaskAdapter$TaskViewHolder;", "Landroidx/recyclerview/widget/RecyclerView$ViewHolder;", "itemView", "Landroid/view/View;", "(Lcom/eevdf/scheduler/adapter/TaskAdapter;Landroid/view/View;)V", "btnComplete", "Landroid/widget/ImageButton;", "getBtnComplete", "()Landroid/widget/ImageButton;", "btnDelete", "getBtnDelete", "btnRun", "getBtnRun", "card", "Landroidx/cardview/widget/CardView;", "getCard", "()Landroidx/cardview/widget/CardView;", "progressBar", "Landroid/widget/ProgressBar;", "getProgressBar", "()Landroid/widget/ProgressBar;", "tvCategory", "Landroid/widget/TextView;", "getTvCategory", "()Landroid/widget/TextView;", "tvName", "getTvName", "tvPriority", "getTvPriority", "tvRank", "getTvRank", "tvRemaining", "getTvRemaining", "tvRunCount", "getTvRunCount", "tvTimeSlice", "getTvTimeSlice", "tvVdeadline", "getTvVdeadline", "tvVruntime", "getTvVruntime", "viewRunning", "getViewRunning", "()Landroid/view/View;", "app_debug"})
    public final class TaskViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        @org.jetbrains.annotations.NotNull()
        private final androidx.cardview.widget.CardView card = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvRank = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvName = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvCategory = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvPriority = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvTimeSlice = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvRemaining = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvVruntime = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvVdeadline = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.ProgressBar progressBar = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.ImageButton btnDelete = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.ImageButton btnComplete = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.ImageButton btnRun = null;
        @org.jetbrains.annotations.NotNull()
        private final android.widget.TextView tvRunCount = null;
        @org.jetbrains.annotations.NotNull()
        private final android.view.View viewRunning = null;
        
        public TaskViewHolder(@org.jetbrains.annotations.NotNull()
        android.view.View itemView) {
            super(null);
        }
        
        @org.jetbrains.annotations.NotNull()
        public final androidx.cardview.widget.CardView getCard() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvRank() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvName() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvCategory() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvPriority() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvTimeSlice() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvRemaining() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvVruntime() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvVdeadline() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.ProgressBar getProgressBar() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.ImageButton getBtnDelete() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.ImageButton getBtnComplete() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.ImageButton getBtnRun() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.widget.TextView getTvRunCount() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final android.view.View getViewRunning() {
            return null;
        }
    }
}