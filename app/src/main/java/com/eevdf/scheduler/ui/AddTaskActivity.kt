package com.eevdf.scheduler.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.eevdf.scheduler.R
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.viewmodel.TaskViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class AddTaskActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var etName:          TextInputEditText
    private lateinit var etDescription:   TextInputEditText
    private lateinit var sliderPriority:  Slider
    private lateinit var tvPriorityLabel: TextView
    private lateinit var etHours:         TextInputEditText
    private lateinit var etMinutes:       TextInputEditText
    private lateinit var etSeconds:       TextInputEditText
    private lateinit var chipGroupCategory: ChipGroup
    private lateinit var btnSave:         MaterialButton
    private lateinit var btnCancel:       MaterialButton
    private lateinit var tvPriorityInfo:  TextView

    // Groups section
    private lateinit var groupSection:    LinearLayout
    private lateinit var switchIsGroup:   SwitchMaterial
    private lateinit var spinnerParent:   Spinner

    private var existingTaskId: String? = null
    private var existingTask:   Task?   = null
    private var selectedCategory = "General"

    /** Groups from the ViewModel for the parent spinner; first entry = "None (root)" */
    private val groupsList = mutableListOf<Task?>()   // null = no parent

    private val categories = listOf("Work", "Study", "Health", "Personal", "Project", "Meeting", "General")

    private val prefs by lazy { getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE) }
    private val groupsEnabled get() = prefs.getBoolean("groups_enabled", false)

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
        setupGroupSection()

        if (existingTaskId != null) {
            supportActionBar?.title = "Edit Task"
            loadExistingTask()
        } else {
            supportActionBar?.title = "Add New Task"
        }
    }

    private fun setupViews() {
        etName           = findViewById(R.id.etTaskName)
        etDescription    = findViewById(R.id.etDescription)
        sliderPriority   = findViewById(R.id.sliderPriority)
        tvPriorityLabel  = findViewById(R.id.tvPriorityLabel)
        etHours          = findViewById(R.id.etHours)
        etMinutes        = findViewById(R.id.etMinutes)
        etSeconds        = findViewById(R.id.etSeconds)
        chipGroupCategory = findViewById(R.id.chipGroupCategory)
        btnSave          = findViewById(R.id.btnSave)
        btnCancel        = findViewById(R.id.btnCancel)
        tvPriorityInfo   = findViewById(R.id.tvPriorityInfo)
        groupSection     = findViewById(R.id.groupSection)
        switchIsGroup    = findViewById(R.id.switchIsGroup)
        spinnerParent    = findViewById(R.id.spinnerParentGroup)

        btnSave.setOnClickListener { saveTask() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun setupGroupSection() {
        if (!groupsEnabled) {
            groupSection.visibility = View.GONE
            return
        }
        groupSection.visibility = View.VISIBLE

        // Observe available groups and populate spinner
        viewModel.activeGroups.observe(this) { groups ->
            groupsList.clear()
            groupsList.add(null)  // index 0 = no parent
            groupsList.addAll(groups.filter { it.id != existingTaskId }) // can't parent to self

            val labels = listOf("None (root level)") + groups
                .filter { it.id != existingTaskId }
                .map { it.name }

            spinnerParent.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            // Restore existing parent selection when editing
            existingTask?.parentId?.let { pid ->
                val idx = groupsList.indexOfFirst { it?.id == pid }
                if (idx >= 0) spinnerParent.setSelection(idx)
            }
        }

        // Hide the parent spinner when "this is a group" is toggled — a group's
        // parent can still be another group, so we keep it visible.
        // But show a hint that group items can contain other groups.
        switchIsGroup.setOnCheckedChangeListener { _, _ ->
            // No extra behaviour needed; just cosmetic feedback is enough.
        }
    }

    private fun setupCategoryChips() {
        categories.forEach { cat ->
            val chip = Chip(this)
            chip.text = cat
            chip.isCheckable = true
            chip.isChecked = cat == selectedCategory
            chip.setOnCheckedChangeListener { _, checked -> if (checked) selectedCategory = cat }
            chipGroupCategory.addView(chip)
        }
    }

    private fun setupPrioritySlider() {
        sliderPriority.valueFrom = 1f
        sliderPriority.valueTo   = 10f
        sliderPriority.stepSize  = 1f
        sliderPriority.value     = 5f
        updatePriorityInfo(5)
        sliderPriority.addOnChangeListener { _, value, _ -> updatePriorityInfo(value.toInt()) }
    }

    private fun updatePriorityInfo(priority: Int) {
        tvPriorityLabel.text = "Priority: $priority/10"
        tvPriorityInfo.text = when (priority) {
            10 -> "Critical — Maximum weight (10x). Runs first."
            9  -> "Urgent — Very high weight."
            8  -> "High — Prioritized over average tasks."
            7  -> "Above Average — Scheduled before medium tasks."
            6  -> "Medium-High — Slightly favored."
            5  -> "Medium — Balanced scheduling weight."
            4  -> "Medium-Low — Slightly deprioritized."
            3  -> "Low — Runs after higher-priority tasks."
            2  -> "Background — Minimal urgency."
            1  -> "Minimal — Runs only when nothing else is pending."
            else -> ""
        }
    }

    private fun loadExistingTask() {
        lifecycleScope.launch {
            val task = existingTaskId?.let { id ->
                viewModel.activeTasks.value?.find { it.id == id }
                    ?: viewModel.completedTasks.value?.find { it.id == id }
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

        if (groupsEnabled) {
            switchIsGroup.isChecked = task.isGroup
        }
    }

    private fun saveTask() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) { etName.error = "Task name required"; return }

        val h = etHours.text.toString().toLongOrNull() ?: 0L
        val m = etMinutes.text.toString().toLongOrNull() ?: 0L
        val s = etSeconds.text.toString().toLongOrNull() ?: 0L
        val totalSeconds = h * 3600 + m * 60 + s

        if (totalSeconds <= 0) {
            Toast.makeText(this, "Please set a time slice > 0", Toast.LENGTH_SHORT).show()
            return
        }

        val priority    = sliderPriority.value.toInt()
        val description = etDescription.text.toString().trim()
        val isGroup     = if (groupsEnabled) switchIsGroup.isChecked else false
        val parentId    = if (groupsEnabled) {
            val idx = spinnerParent.selectedItemPosition
            groupsList.getOrNull(idx)?.id
        } else null

        if (existingTask != null) {
            val updated = existingTask!!.copy(
                name             = name,
                description      = description,
                priority         = priority,
                timeSliceSeconds = totalSeconds,
                category         = selectedCategory,
                isGroup          = isGroup,
                parentId         = parentId
            )
            viewModel.updateTask(updated)
        } else {
            val task = Task(
                name             = name,
                description      = description,
                priority         = priority,
                timeSliceSeconds = totalSeconds,
                remainingSeconds = totalSeconds,
                category         = selectedCategory,
                isGroup          = isGroup,
                parentId         = parentId
            )
            viewModel.addTask(task)
        }
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
