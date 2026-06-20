package com.eevdf.app.feature.task

import android.os.Bundle
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.eevdf.app.R
import com.eevdf.data.task.Task
import com.eevdf.app.feature.task.TaskViewModel
import com.google.android.material.button.MaterialButton
import android.widget.AutoCompleteTextView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * Add / Edit task form activity.
 *
 * This file owns:
 *   • All view field declarations and activity-scoped state
 *   • [onCreate] orchestration
 *   • [setupViews] — binds all views to their lateinit fields
 *   • [loadExistingTask] / [populateFields] — delegates to per-section populate helpers
 *   • [onSupportNavigateUp]
 *
 * Section-specific setup, populate, parse, and format logic lives in sibling files:
 *   • AddTaskCategoryPrioritySection.kt  — category chips + priority slider
 *   • AddTaskGroupSection.kt             — parent group spinner
 *   • AddTaskInterruptSection.kt         — interrupt A/B switches
 *   • AddTaskPinnedShareSection.kt       — realtime / pinned share + weight calc
 *   • AddTaskTypeSection.kt              — task type spinner + notice delay fields
 *   • AddTaskQuotaSection.kt             — quota limit switch + duration fields
 *   • AddTaskSchedulerSection.kt         — scheduler class, DL, and RT sub-sections
 *   • AddTaskSaveHandler.kt              — full saveTask() validation + persistence
 */
class AddTaskActivity : AppCompatActivity() {

    internal val viewModel: TaskViewModel by viewModels()

    // ── Basic task fields ─────────────────────────────────────────────────────
    internal lateinit var etName:           TextInputEditText
    internal lateinit var etDescription:    TextInputEditText
    internal lateinit var sliderPriority:   Slider
    internal lateinit var tvPriorityLabel:  TextView
    internal lateinit var etHours:          TextInputEditText
    internal lateinit var etMinutes:        TextInputEditText
    internal lateinit var etSeconds:        TextInputEditText
    internal lateinit var etCategoryInput:  AutoCompleteTextView
    internal lateinit var btnSave:          MaterialButton
    internal lateinit var btnCancel:        MaterialButton
    internal lateinit var tvPriorityInfo:   TextView

    // ── Interrupt section ─────────────────────────────────────────────────────
    internal lateinit var switchIsInterrupt:  SwitchMaterial
    internal lateinit var tvInterruptOwner:   TextView
    internal lateinit var switchIsInterruptB: SwitchMaterial
    internal lateinit var tvInterruptOwnerB:  TextView

    // ── Groups section ────────────────────────────────────────────────────────
    internal lateinit var groupSection:        LinearLayout
    internal lateinit var groupTypeSection:    LinearLayout
    internal lateinit var switchIsGroup:       SwitchMaterial
    // Parent group picker — replaces the plain Spinner
    internal lateinit var btnParentGroupPicker: LinearLayout
    internal lateinit var tvParentGroupLabel:   TextView
    /** Currently selected parent group id; null = root level ("None"). */
    internal var selectedParentId: String? = null

    // ── Realtime / pinned share section ──────────────────────────────────────
    internal lateinit var switchRealtimeShare:       SwitchMaterial
    internal lateinit var layoutRealtimeShareFields: LinearLayout
    internal lateinit var etPinnedShare:             TextInputEditText
    internal lateinit var tvPinnedShareWarning:      TextView

    // ── Task type / notice section ────────────────────────────────────────────
    internal lateinit var spinnerTaskType:       Spinner
    internal lateinit var layoutNoticeSection:   LinearLayout
    internal lateinit var etNotifDelay:          TextInputEditText
    internal lateinit var tvNotifDelayPreview:   TextView
    internal lateinit var etNoticeRest:          TextInputEditText
    internal lateinit var tvNoticeRestPreview:   TextView
    internal lateinit var etNoticeRepeat:        TextInputEditText
    internal lateinit var spinnerNoticeResumeType: Spinner

    // ── Quota limit section ───────────────────────────────────────────────────
    internal lateinit var switchQuotaEnabled: SwitchMaterial
    internal lateinit var layoutQuotaFields:  LinearLayout
    internal lateinit var etQuota:            TextInputEditText
    internal lateinit var tvQuotaPreview:     TextView
    internal lateinit var etPeriod:           TextInputEditText
    internal lateinit var tvPeriodPreview:    TextView
    internal lateinit var tvQuotaError:       TextView

    // ── Scheduler class section ───────────────────────────────────────────────
    internal lateinit var switchSchedulerEnabled: SwitchMaterial
    internal lateinit var layoutSchedulerFields:  LinearLayout
    internal lateinit var spinnerSchedulerClass:  Spinner
    internal lateinit var tvSchedulerClassDesc:   TextView
    internal lateinit var tvSchedulerWarning:     TextView
    internal lateinit var layoutDlFields:         LinearLayout
    internal lateinit var etDlRuntime:            TextInputEditText
    internal lateinit var tvDlRuntimePreview:     TextView
    internal lateinit var etDlDeadline:           TextInputEditText
    internal lateinit var tvDlDeadlinePreview:    TextView
    internal lateinit var etDlPeriod:             TextInputEditText
    internal lateinit var tvDlPeriodPreview:      TextView
    internal lateinit var tvDlError:              TextView
    internal lateinit var tvDlRtSyncValue:        TextView
    internal lateinit var btnDlRtSync:            com.google.android.material.button.MaterialButton

    // ── RT Sync state — epoch ms captured by the RT Sync button in the DL section.
    // 0L = not yet synced (field stays blank); non-zero = will be written to
    // dlPeriodStartEpoch on save, overriding whatever was previously stored.
    internal var pendingDlPeriodStartEpoch: Long = 0L

    // ── RT (SCHED_FIFO / SCHED_RR) fields ────────────────────────────────────
    internal lateinit var layoutRtFields:          LinearLayout
    internal lateinit var sliderRtPriority:        Slider
    internal lateinit var tvRtPriorityValue:       TextView
    internal lateinit var spinnerRtPolicy:         Spinner
    internal lateinit var cbRtSun:                 CheckBox
    internal lateinit var cbRtMon:                 CheckBox
    internal lateinit var cbRtTue:                 CheckBox
    internal lateinit var cbRtWed:                 CheckBox
    internal lateinit var cbRtThu:                 CheckBox
    internal lateinit var cbRtFri:                 CheckBox
    internal lateinit var cbRtSat:                 CheckBox
    internal lateinit var etRtHour:                TextInputEditText
    internal lateinit var etRtMinute:              TextInputEditText
    internal lateinit var etRtSecond:              TextInputEditText
    internal lateinit var etRtSliceTimeout:        TextInputEditText
    internal lateinit var tvRtSliceTimeoutPreview: TextView
    internal lateinit var tvRtError:               TextView

    // ── Activity-scoped mutable state ─────────────────────────────────────────
    internal var selectedTaskType  = "DEFAULT"
    internal var selectedCategory  = "General"
    internal var existingTaskId:   String? = null
    internal var existingTask:     Task?   = null
    /** Groups from the ViewModel for the parent spinner; first entry = "None (root)" */
    internal val groupsList        = mutableListOf<Task?>()   // null = no parent
    /**
     * Holds the auto-calculated internal weight derived from [etPinnedShare].
     * Non-null only while the pinned share field has a valid value; cleared when
     * the field is emptied so normal slider-based priority resumes.
     */
    internal var autoCalcWeight:   Double? = null

    internal val prefs by lazy { getSharedPreferences("eevdf_prefs", MODE_PRIVATE) }
    internal val groupsEnabled get() = prefs.getBoolean("groups_enabled", false)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_task)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        existingTaskId = intent.getStringExtra("task_id")

        setupViews()
        setupCategoryInput()
        setupPrioritySlider()
        setupGroupSection()
        setupInterruptSwitch()
        setupTaskTypeSection()
        setupPinnedShare()
        setupRealtimeShare()
        setupQuotaSection()
        setupSchedulerClassSection()

        // Observe activeTasks here (not just in validation) so .value is populated
        // the moment the user opens this screen and types a pinned share value.
        // Also re-triggers weight calc if the task list changes while the form is open.
        viewModel.activeTasks.observe(this) { recalcWeightFromPinned() }

        if (existingTaskId != null) {
            supportActionBar?.title = "Edit Task"
            loadExistingTask()
        } else {
            supportActionBar?.title = "Add New Task"
        }
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun setupViews() {
        etName            = findViewById(R.id.etTaskName)
        etDescription     = findViewById(R.id.etDescription)
        sliderPriority    = findViewById(R.id.sliderPriority)
        tvPriorityLabel   = findViewById(R.id.tvPriorityLabel)
        etHours           = findViewById(R.id.etHours)
        etMinutes         = findViewById(R.id.etMinutes)
        etSeconds         = findViewById(R.id.etSeconds)
        etCategoryInput   = findViewById(R.id.etCategoryInput)
        btnSave           = findViewById(R.id.btnSave)
        btnCancel         = findViewById(R.id.btnCancel)
        tvPriorityInfo    = findViewById(R.id.tvPriorityInfo)

        switchIsInterrupt  = findViewById(R.id.switchIsInterrupt)
        tvInterruptOwner   = findViewById(R.id.tvInterruptOwner)
        switchIsInterruptB = findViewById(R.id.switchIsInterruptB)
        tvInterruptOwnerB  = findViewById(R.id.tvInterruptOwnerB)

        groupSection        = findViewById(R.id.groupSection)
        groupTypeSection    = findViewById(R.id.groupTypeSection)
        switchIsGroup       = findViewById(R.id.switchIsGroup)
        btnParentGroupPicker = findViewById(R.id.btnParentGroupPicker)
        tvParentGroupLabel  = findViewById(R.id.tvParentGroupLabel)

        spinnerTaskType       = findViewById(R.id.spinnerTaskType)
        layoutNoticeSection   = findViewById(R.id.layoutNotifDelay)
        etNotifDelay          = findViewById(R.id.etNotifDelay)
        tvNotifDelayPreview   = findViewById(R.id.tvNotifDelayPreview)
        etNoticeRest          = findViewById(R.id.etNoticeRest)
        tvNoticeRestPreview   = findViewById(R.id.tvNoticeRestPreview)
        etNoticeRepeat        = findViewById(R.id.etNoticeRepeat)
        spinnerNoticeResumeType = findViewById(R.id.spinnerNoticeResumeType)

        etPinnedShare         = findViewById(R.id.etPinnedShare)
        tvPinnedShareWarning  = findViewById(R.id.tvPinnedShareWarning)
        switchRealtimeShare       = findViewById(R.id.switchRealtimeShare)
        layoutRealtimeShareFields = findViewById(R.id.layoutRealtimeShareFields)

        switchQuotaEnabled = findViewById(R.id.switchQuotaEnabled)
        layoutQuotaFields  = findViewById(R.id.layoutQuotaFields)
        etQuota            = findViewById(R.id.etQuota)
        tvQuotaPreview     = findViewById(R.id.tvQuotaPreview)
        etPeriod           = findViewById(R.id.etPeriod)
        tvPeriodPreview    = findViewById(R.id.tvPeriodPreview)
        tvQuotaError       = findViewById(R.id.tvQuotaError)

        switchSchedulerEnabled = findViewById(R.id.switchSchedulerEnabled)
        layoutSchedulerFields  = findViewById(R.id.layoutSchedulerFields)
        spinnerSchedulerClass  = findViewById(R.id.spinnerSchedulerClass)
        tvSchedulerClassDesc   = findViewById(R.id.tvSchedulerClassDesc)
        tvSchedulerWarning     = findViewById(R.id.tvSchedulerWarning)
        layoutDlFields         = findViewById(R.id.layoutDlFields)
        etDlRuntime            = findViewById(R.id.etDlRuntime)
        tvDlRuntimePreview     = findViewById(R.id.tvDlRuntimePreview)
        etDlDeadline           = findViewById(R.id.etDlDeadline)
        tvDlDeadlinePreview    = findViewById(R.id.tvDlDeadlinePreview)
        etDlPeriod             = findViewById(R.id.etDlPeriod)
        tvDlPeriodPreview      = findViewById(R.id.tvDlPeriodPreview)
        tvDlError              = findViewById(R.id.tvDlError)
        tvDlRtSyncValue        = findViewById(R.id.tvDlRtSyncValue)
        btnDlRtSync            = findViewById(R.id.btnDlRtSync)

        layoutRtFields          = findViewById(R.id.layoutRtFields)
        sliderRtPriority        = findViewById(R.id.sliderRtPriority)
        tvRtPriorityValue       = findViewById(R.id.tvRtPriorityValue)
        spinnerRtPolicy         = findViewById(R.id.spinnerRtPolicy)
        cbRtSun                 = findViewById(R.id.cbRtSun)
        cbRtMon                 = findViewById(R.id.cbRtMon)
        cbRtTue                 = findViewById(R.id.cbRtTue)
        cbRtWed                 = findViewById(R.id.cbRtWed)
        cbRtThu                 = findViewById(R.id.cbRtThu)
        cbRtFri                 = findViewById(R.id.cbRtFri)
        cbRtSat                 = findViewById(R.id.cbRtSat)
        etRtHour                = findViewById(R.id.etRtHour)
        etRtMinute              = findViewById(R.id.etRtMinute)
        etRtSecond              = findViewById(R.id.etRtSecond)
        etRtSliceTimeout        = findViewById(R.id.etRtSliceTimeout)
        tvRtSliceTimeoutPreview = findViewById(R.id.tvRtSliceTimeoutPreview)
        tvRtError               = findViewById(R.id.tvRtError)

        btnSave.setOnClickListener { saveTask() }
        btnCancel.setOnClickListener { finish() }
    }

    // ── Existing task load ────────────────────────────────────────────────────

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

    /**
     * Populates all form sections from [task].
     * Each call delegates to the section's own populate helper so that adding a
     * new field only requires touching that section's file.
     */
    private fun populateFields(task: Task) {
        populateBasicFields(task)
        populateCategoryPrioritySection(task)
        populateGroupSection(task)
        populateTaskTypeSection(task)
        populateInterruptSection(task)
        populatePinnedShareSection(task)
        populateQuotaSection(task)
        populateSchedulerSection(task)
    }

    /** Fills the name, description, priority slider, and time-slice fields. */
    private fun populateBasicFields(task: Task) {
        etName.setText(task.name)
        etDescription.setText(task.description)
        sliderPriority.value = task.priority.coerceIn(1, 7).toFloat()
        val totalSec = task.timeSliceSeconds
        etHours.setText((totalSec / 3600).toString())
        etMinutes.setText(((totalSec % 3600) / 60).toString())
        etSeconds.setText((totalSec % 60).toString())
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
