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
import com.eevdf.scheduler.scheduler.EEVDFScheduler
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

    // Interrupt section
    private lateinit var switchIsInterrupt: SwitchMaterial
    private lateinit var tvInterruptOwner:  TextView

    // Groups section
    private lateinit var groupSection:    LinearLayout
    private lateinit var switchIsGroup:   SwitchMaterial
    private lateinit var spinnerParent:   Spinner

    // Task type section
    private lateinit var spinnerTaskType:       Spinner
    private lateinit var layoutNoticeSection:   LinearLayout
    private lateinit var etNotifDelay:          TextInputEditText
    private lateinit var tvNotifDelayPreview:   TextView
    private lateinit var etNoticeRest:          TextInputEditText
    private lateinit var tvNoticeRestPreview:   TextView
    private lateinit var etNoticeRepeat:        TextInputEditText

    // Pinned share section
    private lateinit var etPinnedShare:         TextInputEditText
    private lateinit var tvPinnedShareWarning:  TextView

    private val taskTypeLabels = listOf("Default", "Notice", "Alert", "Custom")
    private val taskTypeValues = listOf("DEFAULT", "NOTIFICATION", "ALARM", "CUSTOM")
    private var selectedTaskType = "DEFAULT"

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
        setupInterruptSwitch()
        setupTaskTypeSection()
        setupPinnedShare()

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
        switchIsInterrupt = findViewById(R.id.switchIsInterrupt)
        tvInterruptOwner  = findViewById(R.id.tvInterruptOwner)
        tvPriorityInfo   = findViewById(R.id.tvPriorityInfo)
        groupSection     = findViewById(R.id.groupSection)
        switchIsGroup    = findViewById(R.id.switchIsGroup)
        spinnerParent    = findViewById(R.id.spinnerParentGroup)
        spinnerTaskType      = findViewById(R.id.spinnerTaskType)
        layoutNoticeSection  = findViewById(R.id.layoutNotifDelay)
        etNotifDelay         = findViewById(R.id.etNotifDelay)
        tvNotifDelayPreview  = findViewById(R.id.tvNotifDelayPreview)
        etNoticeRest         = findViewById(R.id.etNoticeRest)
        tvNoticeRestPreview  = findViewById(R.id.tvNoticeRestPreview)
        etNoticeRepeat       = findViewById(R.id.etNoticeRepeat)
        etPinnedShare        = findViewById(R.id.etPinnedShare)
        tvPinnedShareWarning = findViewById(R.id.tvPinnedShareWarning)

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
        sliderPriority.valueTo   = 7f
        sliderPriority.stepSize  = 1f
        sliderPriority.value     = 4f
        updatePriorityInfo(4)
        sliderPriority.addOnChangeListener { _, value, _ -> updatePriorityInfo(value.toInt()) }
    }

    private fun updatePriorityInfo(priority: Int) {
        tvPriorityLabel.text = "Priority: $priority"
        tvPriorityInfo.text = when (priority) {
            7    -> "Critical — Maximum weight. Runs first."
            6    -> "High — Prioritized over most tasks."
            5    -> "Above Average — Scheduled before medium tasks."
            4    -> "Medium — Balanced scheduling weight."
            3    -> "Below Average — Slightly deprioritized."
            2    -> "Low — Runs after higher-priority tasks."
            else -> "Minimal — Runs only when nothing else is pending."
        }
    }

    private fun loadExistingTask() {
        lifecycleScope.launch {
            // Use a direct DB query so we never rely on LiveData.value being
            // non-null on the first frame (which causes the "creates new task" bug)
            val task = existingTaskId?.let { viewModel.getTaskById(it) }
            if (task != null) {
                existingTask = task
                runOnUiThread { populateFields(task) }
            }
        }
    }

    private fun populateFields(task: Task) {
        etName.setText(task.name)
        etDescription.setText(task.description)
        sliderPriority.value = task.priority.coerceIn(1, 7).toFloat()
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
        // Restore task type
        val typeIdx = taskTypeValues.indexOf(task.taskType).coerceAtLeast(0)
        spinnerTaskType.setSelection(typeIdx)
        selectedTaskType = task.taskType
        if (task.taskType == "NOTIFICATION") {
            layoutNoticeSection.visibility = android.view.View.VISIBLE
            val dm = task.notificationDelaySeconds
            etNotifDelay.setText(if (dm == 0L) "" else "%02d-%02d".format(dm / 60, dm % 60))
            val rm = task.notificationRestSeconds
            etNoticeRest.setText(if (rm == 0L) "" else "%02d-%02d".format(rm / 60, rm % 60))
            etNoticeRepeat.setText(if (task.notificationRepeatCount == 0) "" else task.notificationRepeatCount.toString())
        }
        if (task.isInterrupt) {
            switchIsInterrupt.isChecked = true
            switchIsInterrupt.isEnabled = true
            tvInterruptOwner.visibility = android.view.View.GONE
        }
        // Pinned share
        task.pinnedShare?.let { etPinnedShare.setText(it.toString()) }
    }

    private fun setupInterruptSwitch() {
        // Show which task currently owns the interrupt slot
        lifecycleScope.launch {
            val current = viewModel.interruptTask.value
                ?: repository_getInterrupt()
            if (current != null && current.id != existingTaskId) {
                tvInterruptOwner.text = "Currently assigned to: \"${current.name}\""
                tvInterruptOwner.visibility = android.view.View.VISIBLE
                switchIsInterrupt.isEnabled = false  // can't steal without clearing first
            }
        }
        // If editing the interrupt task itself, show checked
        existingTask?.let { if (it.isInterrupt) switchIsInterrupt.isChecked = true }
    }

    /** Inline helper — avoids exposing repository directly, uses ViewModel. */
    private suspend fun repository_getInterrupt(): Task? {
        // Use ViewModel's LiveData value (already loaded on init)
        return viewModel.interruptTask.value
    }

    private fun setupPinnedShare() {
        etPinnedShare.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validatePinnedShare(s?.toString()?.toIntOrNull())
            }
        })
    }

    private fun validatePinnedShare(newValue: Int?) {
        if (newValue == null) {
            // Empty = auto-float, no warning needed
            tvPinnedShareWarning.visibility = android.view.View.GONE
            return
        }
        val tasks = viewModel.activeTasks.value ?: emptyList()
        val otherPinned = EEVDFScheduler.otherPinnedTotal(tasks, existingTaskId)
        val total = otherPinned + newValue
        when {
            newValue < 1 || newValue > 99 -> {
                tvPinnedShareWarning.text = "Value must be between 1 and 99."
                tvPinnedShareWarning.visibility = android.view.View.VISIBLE
            }
            total > 100 -> {
                tvPinnedShareWarning.text =
                    "Total pinned share would be $total% (other tasks: $otherPinned%). " +
                    "Reduce this or other pinned tasks so total ≤ 100%."
                tvPinnedShareWarning.visibility = android.view.View.VISIBLE
            }
            total == 100 -> {
                tvPinnedShareWarning.text =
                    "Warning: all 100% is pinned. Floating tasks will receive 0% CPU share."
                tvPinnedShareWarning.visibility = android.view.View.VISIBLE
            }
            else -> {
                tvPinnedShareWarning.visibility = android.view.View.GONE
            }
        }
    }

    private fun setupTaskTypeSection() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, taskTypeLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTaskType.adapter = adapter
        spinnerTaskType.setSelection(taskTypeValues.indexOf(selectedTaskType).coerceAtLeast(0))

        spinnerTaskType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                selectedTaskType = taskTypeValues.getOrElse(pos) { "DEFAULT" }
                layoutNoticeSection.visibility =
                    if (selectedTaskType == "NOTIFICATION") android.view.View.VISIBLE else android.view.View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        fun watchDelay(et: TextInputEditText, preview: TextView) {
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    preview.text = formatDelaySecs(parseDelayInput(s?.toString() ?: ""))
                }
            })
        }
        watchDelay(etNotifDelay, tvNotifDelayPreview)
        watchDelay(etNoticeRest, tvNoticeRestPreview)
    }

    /** Parses mm-ss format (e.g. "01-30") into total seconds. Also accepts plain seconds. */
    private fun parseDelayInput(raw: String): Long {
        val trimmed = raw.trim()
        return if (trimmed.contains('-')) {
            val parts = trimmed.split('-')
            val mm = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val ss = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            (mm * 60 + ss).coerceIn(0, 300)
        } else {
            trimmed.toLongOrNull()?.coerceIn(0, 300) ?: 0L
        }
    }

    private fun formatDelaySecs(secs: Long): String = when {
        secs == 0L -> "0s (no delay)"
        secs < 60  -> "${secs}s"
        secs % 60 == 0L -> "${secs / 60} min"
        else       -> "${secs / 60}m ${secs % 60}s"
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
        val notifDelaySecs  = if (selectedTaskType == "NOTIFICATION") parseDelayInput(etNotifDelay.text.toString()) else 0L
        val notifRestSecs   = if (selectedTaskType == "NOTIFICATION") parseDelayInput(etNoticeRest.text.toString()) else 0L
        val notifRepeat     = if (selectedTaskType == "NOTIFICATION") (etNoticeRepeat.text.toString().toIntOrNull() ?: 0).coerceIn(0, 12) else 0

        // Pinned share — null if field empty (auto-float), else validated 1–99
        val pinnedShareRaw = etPinnedShare.text.toString().toIntOrNull()
        val pinnedShare: Int? = if (pinnedShareRaw != null) {
            val clamped     = pinnedShareRaw.coerceIn(1, 99)
            val tasks       = viewModel.activeTasks.value ?: emptyList()
            val otherPinned = EEVDFScheduler.otherPinnedTotal(tasks, existingTaskId)
            if (otherPinned + clamped > 100) {
                tvPinnedShareWarning.text =
                    "Cannot save: total pinned share would be ${otherPinned + clamped}%. " +
                    "Reduce this or other pinned tasks so total ≤ 100%."
                tvPinnedShareWarning.visibility = android.view.View.VISIBLE
                return
            }
            clamped
        } else null

        if (existingTask != null) {
            val updated = existingTask!!.copy(
                name             = name,
                description      = description,
                priority         = priority,
                timeSliceSeconds = totalSeconds,
                category         = selectedCategory,
                isGroup          = isGroup,
                parentId         = parentId,
                taskType         = selectedTaskType,
                notificationDelaySeconds = notifDelaySecs,
                notificationRestSeconds  = notifRestSecs,
                notificationRepeatCount  = notifRepeat,
                pinnedShare      = pinnedShare
            )
            // Handle interrupt assignment
        if (switchIsInterrupt.isChecked) viewModel.assignInterruptTask(updated)
        else if (updated.isInterrupt) viewModel.clearInterruptTask()
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
                parentId         = parentId,
                taskType         = selectedTaskType,
                notificationDelaySeconds = notifDelaySecs,
                notificationRestSeconds  = notifRestSecs,
                notificationRepeatCount  = notifRepeat,
                pinnedShare      = pinnedShare
            )
            viewModel.addTask(task)
            if (switchIsInterrupt.isChecked) viewModel.assignInterruptTask(task)
        }
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
