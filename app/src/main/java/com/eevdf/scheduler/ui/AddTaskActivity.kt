package com.eevdf.scheduler.ui

import android.os.Bundle
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eevdf.scheduler.R
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.viewmodel.TaskViewModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import androidx.appcompat.widget.Toolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.Chip
import android.view.View
import com.google.android.material.slider.Slider

class AddTaskActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var etName: TextInputEditText
    private lateinit var etDescription: TextInputEditText
    private lateinit var sliderPriority: Slider
    private lateinit var tvPriorityLabel: TextView
    private lateinit var etHours: TextInputEditText
    private lateinit var etMinutes: TextInputEditText
    private lateinit var etSeconds: TextInputEditText
    private lateinit var chipGroupCategory: ChipGroup
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var tvPriorityInfo: TextView

    private var existingTaskId: String? = null
    private var existingTask: Task? = null

    private val categories = listOf("Work", "Study", "Health", "Personal", "Project", "Meeting", "General")
    private var selectedCategory = "General"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_task)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        existingTaskId = intent.getStringExtra("task_id")

        setupViews()
        setupCategoryChips()
        setupPrioritySlider()

        if (existingTaskId != null) {
            supportActionBar?.title = "Edit Task"
            loadExistingTask()
        } else {
            supportActionBar?.title = "Add New Task"
        }
    }

    private fun setupViews() {
        etName = findViewById(R.id.etTaskName)
        etDescription = findViewById(R.id.etDescription)
        sliderPriority = findViewById(R.id.sliderPriority)
        tvPriorityLabel = findViewById(R.id.tvPriorityLabel)
        etHours = findViewById(R.id.etHours)
        etMinutes = findViewById(R.id.etMinutes)
        etSeconds = findViewById(R.id.etSeconds)
        chipGroupCategory = findViewById(R.id.chipGroupCategory)
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)
        tvPriorityInfo = findViewById(R.id.tvPriorityInfo)

        btnSave.setOnClickListener { saveTask() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun setupCategoryChips() {
        categories.forEach { cat ->
            val chip = Chip(this)
            chip.text = cat
            chip.isCheckable = true
            chip.isChecked = cat == selectedCategory
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedCategory = cat
            }
            chipGroupCategory.addView(chip)
        }
    }

    private fun setupPrioritySlider() {
        sliderPriority.valueFrom = 1f
        sliderPriority.valueTo = 10f
        sliderPriority.stepSize = 1f
        sliderPriority.value = 5f

        updatePriorityInfo(5)

        sliderPriority.addOnChangeListener { _, value, _ ->
            updatePriorityInfo(value.toInt())
        }
    }

    private fun updatePriorityInfo(priority: Int) {
        tvPriorityLabel.text = "Priority: $priority/10"
        val info = when (priority) {
            10 -> "🔴 Critical — Maximum CPU weight (10×). Runs first, vruntime grows slowest."
            9  -> "🔴 Urgent — Very high weight. Near-immediate scheduling."
            8  -> "🟠 High — Prioritized over average tasks."
            7  -> "🟠 Above Average — Scheduled before medium tasks."
            6  -> "🔵 Medium-High — Slightly favored over default."
            5  -> "🔵 Medium — Balanced scheduling weight."
            4  -> "🟢 Medium-Low — Slightly deprioritized."
            3  -> "🟢 Low — Runs after higher-priority tasks."
            2  -> "⚫ Background — Minimal urgency."
            1  -> "⚫ Minimal — Runs only when nothing else is pending."
            else -> ""
        }
        tvPriorityInfo.text = info
    }

    private fun loadExistingTask() {
        lifecycleScope.launch {
            val task = existingTaskId?.let { viewModel.activeTasks.value?.find { t -> t.id == it } }
                ?: existingTaskId?.let { id ->
                    viewModel.completedTasks.value?.find { t -> t.id == id }
                }
            if (task != null) {
                existingTask = task
                populateFields(task)
            }
        }
    }

    private fun populateFields(task: Task) {
        etName.setText(task.name)
        etDescription.setText(task.description)
        sliderPriority.value = task.priority.toFloat()
        updatePriorityInfo(task.priority)

        val totalSec = task.timeSliceSeconds
        etHours.setText((totalSec / 3600).toString())
        etMinutes.setText(((totalSec % 3600) / 60).toString())
        etSeconds.setText((totalSec % 60).toString())

        selectedCategory = task.category
        for (i in 0 until chipGroupCategory.childCount) {
            val chip = chipGroupCategory.getChildAt(i) as? Chip
            chip?.isChecked = chip?.text == task.category
        }
    }

    private fun saveTask() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = "Task name required"
            return
        }

        val h = etHours.text.toString().toLongOrNull() ?: 0L
        val m = etMinutes.text.toString().toLongOrNull() ?: 0L
        val s = etSeconds.text.toString().toLongOrNull() ?: 0L
        val totalSeconds = h * 3600 + m * 60 + s

        if (totalSeconds <= 0) {
            Toast.makeText(this, "Please set a time slice > 0", Toast.LENGTH_SHORT).show()
            return
        }

        val priority = sliderPriority.value.toInt()
        val description = etDescription.text.toString().trim()

        if (existingTask != null) {
            val updated = existingTask!!.copy(
                name = name,
                description = description,
                priority = priority,
                timeSliceSeconds = totalSeconds,
                category = selectedCategory
            )
            viewModel.updateTask(updated)
        } else {
            val task = Task(
                name = name,
                description = description,
                priority = priority,
                timeSliceSeconds = totalSeconds,
                remainingSeconds = totalSeconds,
                category = selectedCategory
            )
            viewModel.addTask(task)
        }

        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
