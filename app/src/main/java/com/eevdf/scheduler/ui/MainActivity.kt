package com.eevdf.scheduler.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.adapter.TaskAdapter
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.viewmodel.TaskViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var recyclerView:        RecyclerView
    private lateinit var activeAdapter:       TaskAdapter
    private lateinit var scheduleAdapter:     TaskAdapter
    private lateinit var completedAdapter:    TaskAdapter
    private lateinit var tabLayout:           TabLayout
    private lateinit var fabAdd:              FloatingActionButton
    private lateinit var cardTimer:           CardView
    private lateinit var tvCurrentTaskName:   TextView
    private lateinit var tvTimerDisplay:      TextView
    private lateinit var tvTimerPriority:     TextView
    private lateinit var btnStartPause:       MaterialButton
    private lateinit var btnSkip:             MaterialButton
    private lateinit var btnScheduleNext:     MaterialButton
    private lateinit var tvStats:             TextView
    private lateinit var tvFairness:          TextView
    private lateinit var emptyView:           LinearLayout
    private lateinit var cardAlarmBanner:     CardView
    private lateinit var tvAlarmTaskName:     TextView
    private lateinit var tvAlarmElapsed:      TextView
    private lateinit var tvAlarmSubtitle:     TextView
    private lateinit var btnStopAlarm:        MaterialButton

    private var currentTab = 0
    private var groupsMenuItem: MenuItem? = null

    private val alarmStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.stopAlarmSound()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupToolbar()
        setupViews()
        setupAdapters()
        setupRecyclerView()
        setupTabs()
        setupObservers()
        setupTimerCard()
        setupAlarmBanner()

        ContextCompat.registerReceiver(
            this, alarmStopReceiver,
            IntentFilter(AlarmStopReceiver.ACTION_STOP_ALARM),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModel.refreshSchedule()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(alarmStopReceiver)
    }

    override fun onResume() { super.onResume() }
    override fun onPause()  { super.onPause() }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "EEVDF Task Scheduler"
    }

    private fun setupViews() {
        recyclerView      = findViewById(R.id.recyclerView)
        tabLayout         = findViewById(R.id.tabLayout)
        fabAdd            = findViewById(R.id.fabAdd)
        cardTimer         = findViewById(R.id.cardTimer)
        tvCurrentTaskName = findViewById(R.id.tvCurrentTaskName)
        tvTimerDisplay    = findViewById(R.id.tvTimerDisplay)
        tvTimerPriority   = findViewById(R.id.tvTimerPriority)
        btnStartPause     = findViewById(R.id.btnStartPause)
        btnSkip           = findViewById(R.id.btnSkip)
        btnScheduleNext   = findViewById(R.id.btnScheduleNext)
        tvStats           = findViewById(R.id.tvStats)
        tvFairness        = findViewById(R.id.tvFairness)
        emptyView         = findViewById(R.id.emptyView)
        cardAlarmBanner   = findViewById(R.id.cardAlarmBanner)
        tvAlarmTaskName   = findViewById(R.id.tvAlarmTaskName)
        tvAlarmElapsed    = findViewById(R.id.tvAlarmElapsed)
        tvAlarmSubtitle   = findViewById(R.id.tvAlarmSubtitle)
        btnStopAlarm      = findViewById(R.id.btnStopAlarm)

        fabAdd.setOnClickListener {
            startActivity(Intent(this, AddTaskActivity::class.java))
        }
    }

    private fun setupAlarmBanner() {
        btnStopAlarm.setOnClickListener { viewModel.stopAlarmSound() }
    }

    private fun makeAdapter(showRank: Boolean = false) = TaskAdapter(
        onTaskClick          = { showTaskDetail(it) },
        onDeleteClick        = { confirmDelete(it) },
        onCompleteClick      = { viewModel.markCompleted(it) },
        onRunClick           = { viewModel.setCurrentTask(it) },
        onGroupToggle        = { viewModel.toggleGroupExpanded(it) },
        onResetSliceClick    = { viewModel.resetSlice(it) },
        showScheduleRank     = showRank
    )

    private fun setupAdapters() {
        activeAdapter   = makeAdapter()
        scheduleAdapter = makeAdapter(showRank = true)
        completedAdapter = TaskAdapter(
            onTaskClick    = { showTaskDetail(it) },
            onDeleteClick  = { confirmDelete(it) },
            onCompleteClick = {},
            onRunClick      = {},
            onGroupToggle   = {},
            onRevertClick   = { viewModel.revertTask(it) },
            isCompletedTab  = true
        )
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = activeAdapter
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Queue"))
        tabLayout.addTab(tabLayout.newTab().setText("Schedule"))
        tabLayout.addTab(tabLayout.newTab().setText("Completed"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                recyclerView.adapter = when (tab.position) {
                    0    -> activeAdapter
                    1    -> scheduleAdapter
                    else -> completedAdapter
                }
                updateEmptyView()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupTimerCard() {
        btnStartPause.setOnClickListener {
            viewModel.stopAlarmSound()
            if (viewModel.timerRunning.value == true) viewModel.pauseTimer()
            else viewModel.startTimer()
        }
        btnSkip.setOnClickListener { viewModel.skipTask() }
        btnScheduleNext.setOnClickListener { viewModel.scheduleNext() }
    }

    private fun setupObservers() {
        // Queue tab — flat group-aware list
        viewModel.flatActiveTasks.observe(this) { items ->
            activeAdapter.submitList(items)
            activeAdapter.setRunningTask(viewModel.currentTask.value?.id)
            updateEmptyView()
        }

        // Schedule tab — flat group-aware list
        viewModel.flatScheduleOrder.observe(this) { items ->
            scheduleAdapter.submitList(items)
            scheduleAdapter.setRunningTask(viewModel.currentTask.value?.id)
        }

        // Completed tab — flat (no group hierarchy for completed)
        viewModel.completedTasks.observe(this) { tasks ->
            completedAdapter.submitList(tasks.map {
                com.eevdf.scheduler.model.TaskDisplayItem(it, 0)
            })
            updateEmptyView()
        }

        viewModel.currentTask.observe(this) { task ->
            if (task != null) {
                cardTimer.visibility = View.VISIBLE
                tvCurrentTaskName.text = task.name
                tvTimerPriority.text = "Priority ${task.priority} · ${task.category}"
                tvTimerDisplay.text = task.remainingDisplay
                activeAdapter.setRunningTask(task.id)
                scheduleAdapter.setRunningTask(task.id)
            } else {
                cardTimer.visibility = View.GONE
                activeAdapter.setRunningTask(null)
                scheduleAdapter.setRunningTask(null)
                tvTimerDisplay.text = "00:00"
            }
        }

        viewModel.timerSeconds.observe(this) { seconds ->
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            tvTimerDisplay.text = if (h > 0)
                String.format("%d:%02d:%02d", h, m, s)
            else
                String.format("%02d:%02d", m, s)
        }

        viewModel.timerRunning.observe(this) { running ->
            btnStartPause.text = if (running) "Pause" else "Start"
            btnStartPause.icon = null
        }

        viewModel.stats.observe(this) { stats ->
            tvStats.text    = "Active: ${stats.activeTasks}  |  Done: ${stats.completedTasks}  |  Weight: ${"%.1f".format(stats.totalWeight)}"
            tvFairness.text = "Fairness: ${"%.0f".format(stats.fairnessScore * 100)}%  |  Avg VRT: ${"%.2f".format(stats.averageVruntime)}"
        }

        viewModel.toastMessage.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.clearToast()
            }
        }

        viewModel.alarmTaskName.observe(this) { taskName ->
            if (taskName != null) {
                cardAlarmBanner.visibility = View.VISIBLE
                tvAlarmTaskName.text = taskName
                tvAlarmSubtitle.text = "Time slice complete · tap Stop to dismiss"
            } else {
                cardAlarmBanner.visibility = View.GONE
            }
        }

        viewModel.alarmElapsedSeconds.observe(this) { elapsed ->
            tvAlarmElapsed.text = NotificationHelper.formatElapsed(elapsed)
        }

        // Sync groups menu checkmark
        viewModel.groupsEnabled.observe(this) { enabled ->
            groupsMenuItem?.isChecked = enabled
        }
    }

    private fun updateEmptyView() {
        val isEmpty = when (currentTab) {
            0    -> viewModel.flatActiveTasks.value?.isEmpty() ?: true
            1    -> viewModel.flatScheduleOrder.value?.isEmpty() ?: true
            else -> viewModel.completedTasks.value?.isEmpty() ?: true
        }
        emptyView.visibility    = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE   else View.VISIBLE
    }

    private fun showTaskDetail(task: Task) {
        startActivity(Intent(this, AddTaskActivity::class.java).apply {
            putExtra("task_id", task.id)
        })
    }

    private fun confirmDelete(task: Task) {
        val msg = if (task.isGroup)
            "Delete group \"${task.name}\" and all its tasks?"
        else
            "Delete \"${task.name}\"?"
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteTask(task) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        groupsMenuItem = menu.findItem(R.id.action_toggle_groups)
        groupsMenuItem?.isChecked = viewModel.groupsEnabled.value ?: false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_groups -> {
                viewModel.toggleGroupsEnabled()
                item.isChecked = viewModel.groupsEnabled.value ?: false
                true
            }
            R.id.action_clear_completed -> { viewModel.clearCompleted(); true }
            R.id.action_schedule_next   -> { viewModel.scheduleNext(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
