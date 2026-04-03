package com.eevdf.scheduler.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.adapter.TaskAdapter
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.viewmodel.TaskViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import androidx.appcompat.widget.Toolbar
import android.widget.TextView
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import android.view.View
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var activeAdapter: TaskAdapter
    private lateinit var scheduleAdapter: TaskAdapter
    private lateinit var completedAdapter: TaskAdapter

    private lateinit var tabLayout: TabLayout
    private lateinit var fabAdd: FloatingActionButton

    // Timer card views
    private lateinit var cardTimer: CardView
    private lateinit var tvCurrentTaskName: TextView
    private lateinit var tvTimerDisplay: TextView
    private lateinit var tvTimerPriority: TextView
    private lateinit var btnStartPause: com.google.android.material.button.MaterialButton
    private lateinit var btnSkip: com.google.android.material.button.MaterialButton
    private lateinit var btnScheduleNext: com.google.android.material.button.MaterialButton
    private lateinit var tvStats: TextView
    private lateinit var tvFairness: TextView
    private lateinit var emptyView: LinearLayout

    private var currentTab = 0

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

        viewModel.refreshSchedule()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "EEVDF Task Scheduler"
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerView)
        tabLayout = findViewById(R.id.tabLayout)
        fabAdd = findViewById(R.id.fabAdd)
        cardTimer = findViewById(R.id.cardTimer)
        tvCurrentTaskName = findViewById(R.id.tvCurrentTaskName)
        tvTimerDisplay = findViewById(R.id.tvTimerDisplay)
        tvTimerPriority = findViewById(R.id.tvTimerPriority)
        btnStartPause = findViewById(R.id.btnStartPause)
        btnSkip = findViewById(R.id.btnSkip)
        btnScheduleNext = findViewById(R.id.btnScheduleNext)
        tvStats = findViewById(R.id.tvStats)
        tvFairness = findViewById(R.id.tvFairness)
        emptyView = findViewById(R.id.emptyView)

        fabAdd.setOnClickListener {
            startActivity(Intent(this, AddTaskActivity::class.java))
        }
    }

    private fun setupAdapters() {
        activeAdapter = TaskAdapter(
            onTaskClick = { showTaskDetail(it) },
            onDeleteClick = { confirmDelete(it) },
            onCompleteClick = { viewModel.markCompleted(it) },
            onRunClick = { viewModel.setCurrentTask(it) }
        )
        scheduleAdapter = TaskAdapter(
            onTaskClick = { showTaskDetail(it) },
            onDeleteClick = { confirmDelete(it) },
            onCompleteClick = { viewModel.markCompleted(it) },
            onRunClick = { viewModel.setCurrentTask(it) },
            showScheduleRank = true
        )
        completedAdapter = TaskAdapter(
            onTaskClick = { showTaskDetail(it) },
            onDeleteClick = { confirmDelete(it) },
            onCompleteClick = {},
            onRunClick = {}
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
                when (tab.position) {
                    0 -> recyclerView.adapter = activeAdapter
                    1 -> recyclerView.adapter = scheduleAdapter
                    2 -> recyclerView.adapter = completedAdapter
                }
                updateEmptyView()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupTimerCard() {
        btnStartPause.setOnClickListener {
            if (viewModel.timerRunning.value == true) {
                viewModel.pauseTimer()
            } else {
                viewModel.startTimer()
            }
        }
        btnSkip.setOnClickListener { viewModel.skipTask() }
        btnScheduleNext.setOnClickListener { viewModel.scheduleNext() }
    }

    private fun setupObservers() {
        viewModel.activeTasks.observe(this) { tasks ->
            activeAdapter.submitList(tasks)
            activeAdapter.setRunningTask(viewModel.currentTask.value?.id)
            updateEmptyView()
        }

        viewModel.scheduleOrder.observe(this) { tasks ->
            scheduleAdapter.submitList(tasks)
            scheduleAdapter.setRunningTask(viewModel.currentTask.value?.id)
        }

        viewModel.completedTasks.observe(this) { tasks ->
            completedAdapter.submitList(tasks)
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
            btnStartPause.text = if (running) "⏸ Pause" else "▶ Start"
            btnStartPause.icon = null
        }

        viewModel.stats.observe(this) { stats ->
            tvStats.text = "Active: ${stats.activeTasks}  |  Done: ${stats.completedTasks}  |  Weight: ${"%.1f".format(stats.totalWeight)}"
            tvFairness.text = "Fairness: ${"%.0f".format(stats.fairnessScore * 100)}%  |  Avg VRT: ${"%.2f".format(stats.averageVruntime)}"
        }

        viewModel.toastMessage.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.clearToast()
            }
        }
    }

    private fun updateEmptyView() {
        val isEmpty = when (currentTab) {
            0 -> (viewModel.activeTasks.value?.isEmpty() ?: true)
            1 -> (viewModel.scheduleOrder.value?.isEmpty() ?: true)
            else -> (viewModel.completedTasks.value?.isEmpty() ?: true)
        }
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showTaskDetail(task: Task) {
        val intent = Intent(this, AddTaskActivity::class.java)
        intent.putExtra("task_id", task.id)
        startActivity(intent)
    }

    private fun confirmDelete(task: Task) {
        AlertDialog.Builder(this)
            .setTitle("Delete Task")
            .setMessage("Delete \"${task.name}\"?")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteTask(task) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_completed -> {
                viewModel.clearCompleted()
                true
            }
            R.id.action_schedule_next -> {
                viewModel.scheduleNext()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
