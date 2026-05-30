package com.eevdf.scheduler.ui.task

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
import com.eevdf.scheduler.ui.autoswitch.AutoSwitchPrefs
import com.eevdf.scheduler.ui.autoswitch.CallEvents
import com.eevdf.scheduler.ui.settings.UiCustomizationPrefs
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.adapter.TaskAdapter
import com.eevdf.scheduler.model.notice.NoticePhase
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.model.timer.TimerCardAction
import com.eevdf.scheduler.model.timer.IntButtonState
import com.eevdf.scheduler.model.timer.NextButtonState
import com.eevdf.scheduler.viewmodel.task.TaskViewModel
import com.google.android.material.button.MaterialButton
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import com.eevdf.scheduler.sync.SyncState
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.eevdf.scheduler.ui.settings.VibrationManager
import com.eevdf.scheduler.ui.alarm.AlarmStopReceiver
import com.eevdf.scheduler.ui.notification.NotificationHelper
import com.eevdf.scheduler.ui.stats.StatsActivity
import com.eevdf.scheduler.ui.settings.SettingsActivity

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
    private lateinit var btnInt:              MaterialButton
    private lateinit var btnScheduleNext:     MaterialButton
    private lateinit var tvStats:             TextView
    private lateinit var tvFairness:          TextView
    private lateinit var tvScheduleRank:      TextView
    private lateinit var emptyView:           LinearLayout
    private lateinit var cardAlarmBanner:     CardView
    private lateinit var tvAlarmTaskName:     TextView
    private lateinit var tvAlarmElapsed:      TextView
    private lateinit var tvAlarmSubtitle:     TextView
    private lateinit var btnStopAlarm:        MaterialButton
    private lateinit var viewPhaseStatus:     View

    // ── UI Customization: card content containers for height scaling ───────────
    private lateinit var layoutAlarmContent:  LinearLayout
    private lateinit var layoutTimerContent:  LinearLayout

    // ── Float-mode banner hiding ───────────────────────────────────────────────
    private lateinit var mainToolbar:  Toolbar
    private lateinit var statsBar:     LinearLayout

    /** True while the activity is in a floating or PiP window — compact stats hidden. */
    private var isCompactModeActive: Boolean = false

    // ── Sync icon views (set in onCreateOptionsMenu after action view inflates) ──
    private var syncDotView:  View?  = null
    private var syncIconView: android.widget.ImageView? = null
    private var syncSpinAnim: ObjectAnimator? = null

    // ── Key1 (Schedule Next) status dot — set in onCreateOptionsMenu ──────────
    private var schedNextDotView: View? = null

    /** True when the user manually hid the timer card via hold on key1.
     *  Prevents currentTask observer from re-showing the card until the user
     *  explicitly reopens it (hold key1 again). Cleared when task becomes null. */
    private var isCardManuallyHidden: Boolean = false

    private var currentTab = 0
    private val prefs by lazy { getSharedPreferences("eevdf_prefs", MODE_PRIVATE) }

    // ── Quota real-time ticker ─────────────────────────────────────────────────
    // Fires every second while the activity is resumed. Sends a lightweight
    // PAYLOAD_QUOTA_TICK to visible items only — no full rebind, no flicker.
    private val quotaTickHandler  = android.os.Handler(android.os.Looper.getMainLooper())
    private val quotaTickRunnable = object : Runnable {
        override fun run() {
            tickQuotaOnVisibleItems()
            quotaTickHandler.postDelayed(this, 1_000L)
        }
    }

    /** Convenience: fire haptic feedback on [v] if enabled in prefs. */
    private fun haptic(v: View) {
        if (!prefs.getBoolean(VibrationManager.KEY_HAPTIC, VibrationManager.DEFAULT_HAPTIC)) return
        v.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            @Suppress("DEPRECATION")
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
    private var groupsMenuItem:       MenuItem? = null
    private var globalRotateMenuItem: MenuItem? = null
    private var allowEditMenuItem:    MenuItem? = null
    private var autoScrollMenuItem:   MenuItem? = null

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
        // Restore last active tab using a one-shot observer so the tab is
        // selected only AFTER the target adapter has received its first data
        // from Room (which is async — the value is never ready synchronously).
        val savedTab = viewModel.getSavedTab()
        if (savedTab == 0) {
            // Queue is default — nothing to do
        } else {
            // Switch to the saved tab only after DiffUtil has actually painted
            // the items into scheduleAdapter. Using AdapterDataObserver is the
            // only reliable hook that fires AFTER ListAdapter's async DiffUtil
            // completes and items are visible — a LiveData observer fires too
            // early (before DiffUtil finishes).
            scheduleAdapter.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        if (itemCount > 0) {
                            tabLayout.getTabAt(savedTab)?.select()
                            scheduleAdapter.unregisterAdapterDataObserver(this)
                        }
                    }
                }
            )
        }
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

    override fun onResume() {
        super.onResume()
        quotaTickHandler.post(quotaTickRunnable)
        viewModel.onSyncResume()
        // Re-read UI customization prefs every time we come back to the activity
        // (user may have changed them in UiCustomizationActivity and pressed Back)
        applyDisplayPrefs()
    }
    override fun onPause()  {
        super.onPause()
        quotaTickHandler.removeCallbacks(quotaTickRunnable)
    }

    /**
     * Reads card height scale and auto-adjust preference from [UiCustomizationPrefs]
     * and pushes them to all three adapters and the fixed timer / alarm cards.
     *
     * Called on [onResume] so changes made in [UiCustomizationActivity] are picked
     * up immediately when the user navigates back.
     */
    private fun applyDisplayPrefs() {
        val scale      = UiCustomizationPrefs.getCardHeightScale(this)
        val autoAdj    = UiCustomizationPrefs.isAutoAdjustEnabled(this)
        val simpleMode = UiCustomizationPrefs.isSimpleModeEnabled(this)
        val unitFormat = UiCustomizationPrefs.isUnitFormatEnabled(this)

        activeAdapter.setSimpleMode(simpleMode)
        scheduleAdapter.setSimpleMode(simpleMode)
        completedAdapter.setSimpleMode(simpleMode)

        activeAdapter.setUnitFormat(unitFormat)
        scheduleAdapter.setUnitFormat(unitFormat)
        completedAdapter.setUnitFormat(unitFormat)

        updateCompactMode(scale, autoAdj)
    }

    /**
     * Detects whether the activity is currently running in a floating or
     * picture-in-picture window and updates [isCompactModeActive] accordingly.
     *
     * Floating detection strategy (API 26+):
     *  • PiP mode         → definitive compact trigger
     *  • Multi-window     → compact trigger (covers freeform floating windows,
     *                        split-screen, and any other windowed mode).
     *                        resources.configuration.screenWidthDp reflects the
     *                        real window width, not the physical screen — used
     *                        for logging / future threshold tuning if needed.
     *
     * When auto-adjust is disabled, compact mode is always off regardless of
     * the window state.
     */
    private fun updateCompactMode(scale: Int, autoAdjust: Boolean) {
        val density   = resources.displayMetrics.density
        val widthDp   = resources.configuration.screenWidthDp
        val heightDp  = resources.configuration.screenHeightDp
        val inPip     = isInPictureInPictureMode
        val inMulti   = isInMultiWindowMode

        val matched   = UiCustomizationPrefs.matchProfile(this, widthDp, heightDp)
        val shouldBeCompact = autoAdjust && when (matched) {
            null -> inPip || inMulti
            else -> UiCustomizationPrefs.isCompactProfile(matched)
        }

        isCompactModeActive = shouldBeCompact

        activeAdapter.setDisplayPrefs(scale, shouldBeCompact)
        scheduleAdapter.setDisplayPrefs(scale, shouldBeCompact)
        completedAdapter.setDisplayPrefs(scale, shouldBeCompact)

        // ── Float-mode banner hiding ──────────────────────────────────────────
        // When the window matches the FLOAT calibration profile, hide:
        //   • Banner 1 — toolbar (app name + all menu icons)
        //   • Banner 2 — statsBar (task status statistics)
        // The TabLayout row stays visible so the user can switch tabs.
        // Both banners are restored for any other profile or when auto-adjust
        // is off, so normal / mini / uncalibrated modes are unaffected.
        val isFloatProfile = autoAdjust && matched == UiCustomizationPrefs.CalibrateProfile.MINI
        val bannerVis = if (isFloatProfile) View.GONE else View.VISIBLE
        mainToolbar.visibility = bannerVis
        statsBar.visibility    = bannerVis

        // Scale the fixed cards (timer + alarm) to match task cards
        applyCardScaleToView(layoutTimerContent, scale, density)
        applyCardScaleToView(layoutAlarmContent, scale, density)
    }

    /**
     * Scales the padding of a card content [LinearLayout] to match [scale].
     * The same scale table used in [TaskAdapter.applyCardScale] — keeps all
     * card types visually consistent.
     */
    private fun applyCardScaleToView(layout: LinearLayout, scale: Int, density: Float) {
        val paddingDp = when (scale) { 5 -> 16f; 4 -> 13f; 3 -> 10f; 2 -> 7f; else -> 5f }
        val p = (paddingDp * density + 0.5f).toInt()
        layout.setPadding(p, p, p, p)
    }

    // ── Window / configuration change callbacks ───────────────────────────────

    /**
     * Fired when the user resizes a freeform / floating window.
     * Because we declare screenSize|smallestScreenSize in android:configChanges
     * (see AndroidManifest), the activity is NOT recreated — this callback fires
     * instead, letting us react to width changes immediately.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (UiCustomizationPrefs.isAutoAdjustEnabled(this)) {
            applyDisplayPrefs()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (UiCustomizationPrefs.isAutoAdjustEnabled(this)) {
            applyDisplayPrefs()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (UiCustomizationPrefs.isAutoAdjustEnabled(this)) {
            applyDisplayPrefs()
        }
    }

    /**
     * RecyclerView. Only tasks with quota enabled need a redraw — the adapter's
     * partial-bind handler skips all other views untouched.
     */
    private fun tickQuotaOnVisibleItems() {
        val lm      = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val adapter = when (currentTab) {
            0    -> activeAdapter
            1    -> scheduleAdapter
            else -> completedAdapter
        }
        val first = lm.findFirstVisibleItemPosition()
        val last  = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last < first) return
        for (i in first..last) {
            adapter.notifyItemChanged(i, TaskAdapter.PAYLOAD_QUOTA_TICK)
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        mainToolbar = toolbar
        statsBar    = findViewById(R.id.statsBar)
        // Tap the stats bar to open the Task Statistics page
        statsBar.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
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
        btnInt            = findViewById(R.id.btnInt)
        btnScheduleNext   = findViewById(R.id.btnScheduleNext)
        tvStats           = findViewById(R.id.tvStats)
        tvFairness        = findViewById(R.id.tvFairness)
        tvScheduleRank    = findViewById(R.id.tvScheduleRank)
        emptyView         = findViewById(R.id.emptyView)
        cardAlarmBanner   = findViewById(R.id.cardAlarmBanner)
        tvAlarmTaskName   = findViewById(R.id.tvAlarmTaskName)
        tvAlarmElapsed    = findViewById(R.id.tvAlarmElapsed)
        tvAlarmSubtitle   = findViewById(R.id.tvAlarmSubtitle)
        btnStopAlarm      = findViewById(R.id.btnStopAlarm)
        viewPhaseStatus   = findViewById(R.id.viewPhaseStatus)
        layoutAlarmContent = findViewById(R.id.layoutAlarmContent)
        layoutTimerContent = findViewById(R.id.layoutTimerContent)

        fabAdd.setOnClickListener {
            haptic(it)
            startActivity(Intent(this, AddTaskActivity::class.java))
        }
    }

    private fun setupAlarmBanner() {
        btnStopAlarm.setOnClickListener { viewModel.stopAlarmSound() }
    }

    private fun makeAdapter(showRank: Boolean = false, scheduleTab: Boolean = false) = TaskAdapter(
        onTaskClick          = { /* tap does nothing — use long-press to edit */ },
        onTaskLongClick      = { if (viewModel.allowEditEnabled.value == true) showTaskDetail(it)
        else Toast.makeText(this, "Enable \"Allow Edit\" from the menu", Toast.LENGTH_SHORT).show() },
        onDeleteClick        = { confirmDelete(it) },
        onCompleteClick      = { viewModel.markCompleted(it) },
        onRunClick           = { viewModel.setCurrentTask(it) },
        onGroupToggle        = { if (scheduleTab) viewModel.toggleScheduleGroupExpanded(it)
                                 else viewModel.toggleQueueGroupExpanded(it) },
        onGroupToggleDeep    = { if (scheduleTab) viewModel.deepToggleScheduleGroupExpanded(it)
                                 else viewModel.deepToggleQueueGroupExpanded(it) },
        onResetSliceClick    = { viewModel.resetSlice(it) },
        showScheduleRank     = showRank,
        expandStateProvider  = { id -> if (scheduleTab) viewModel.getScheduleExpanded(id)
                                        else viewModel.getQueueExpanded(id) }
    )

    private fun setupAdapters() {
        activeAdapter   = makeAdapter()
        scheduleAdapter = makeAdapter(showRank = true, scheduleTab = true)
        completedAdapter = TaskAdapter(
            onTaskClick      = { /* tap does nothing */ },
            onTaskLongClick  = { if (viewModel.allowEditEnabled.value == true) showTaskDetail(it)
            else Toast.makeText(this, "Enable \"Allow Edit\" from the menu", Toast.LENGTH_SHORT).show() },
            onDeleteClick    = { confirmDelete(it) },
            onCompleteClick  = {},
            onRunClick       = {},
            onGroupToggle    = {},
            onRevertClick    = { viewModel.revertTask(it) },
            isCompletedTab   = true
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
                viewModel.activeTab = tab.position
                viewModel.saveTab(tab.position)
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
        // ── Start / Pause / Cancel ────────────────────────────────────────────
        // CRITICAL: dispatch from timerCardAction — the pre-derived, already-settled
        // value — NEVER from viewModel.timerRunning.value or viewModel.noticePhase.value
        // read at click time.  Reading two separate LiveData at tap time is the root
        // cause of "button stuck at Start" and "button dispatches the wrong action":
        // if the tap lands between two LiveData dispatches, one value is stale.
        btnStartPause.setOnClickListener {
            haptic(it)
            viewModel.stopAlarmSound()
            when (viewModel.timerCardAction.value) {
                TimerCardAction.Start       -> viewModel.startTimer()
                TimerCardAction.Pause       -> viewModel.pauseTimer()
                TimerCardAction.Cancel      -> viewModel.cancelNotice()
                TimerCardAction.Unavailable -> Unit   // disabled — no-op
                null                        -> Unit   // not yet derived — no-op
            }
        }

        // ── INT button ────────────────────────────────────────────────────────
        btnInt.setOnClickListener      { haptic(it); viewModel.jumpToInterrupt() }
        btnInt.setOnLongClickListener  { haptic(it); viewModel.toggleInterruptSlot(); true }

        // ── Next / Auto button ────────────────────────────────────────────────
        btnScheduleNext.setOnClickListener    { haptic(it); viewModel.nextSibling(onQueueTab = currentTab == 0) }
        btnScheduleNext.setOnLongClickListener { haptic(it); viewModel.toggleAutoMode(); true }
    }

    private fun setupObservers() {
        // ── Auto Switch — Call Detection ──────────────────────────────────────
        CallEvents.event.observe(this) { type ->
            if (type == null) return@observe
            val callTaskId = AutoSwitchPrefs.getCallTaskId(this) ?: return@observe
            when (type) {
                CallEvents.Type.CALL_STARTED -> viewModel.handleCallStarted(callTaskId)
                CallEvents.Type.CALL_ENDED   -> viewModel.handleCallEnded()
            }
            CallEvents.event.value = null   // consume
        }

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
            updateScheduleRankBadge()
        }

        // Completed tab — flat (no group hierarchy for completed)
        viewModel.completedTasks.observe(this) { tasks ->
            completedAdapter.submitList(tasks.map {
                com.eevdf.scheduler.model.task.TaskDisplayItem(it, 0)
            })
            updateEmptyView()
        }

        viewModel.currentTask.observe(this) { task ->
            if (task != null) {
                // Only show the card if the user has not manually hidden it via hold on key1.
                if (!isCardManuallyHidden) cardTimer.visibility = View.VISIBLE
                tvCurrentTaskName.text = task.name
                tvTimerPriority.text = "Priority ${task.priority} · ${task.category}"
                tvTimerDisplay.text = task.remainingDisplay
                activeAdapter.setRunningTask(task.id)
                scheduleAdapter.setRunningTask(task.id)
                if (viewModel.autoScrollEnabled.value == true) scrollToTask(task.id)
                // Auto mode: onTimerFinished queued the next task — start it immediately
                if (viewModel.consumePendingAutoStart()) viewModel.startTimer()
            } else {
                // No active task — always hide card and reset the manual-hide flag so
                // the next task selection shows the card normally.
                isCardManuallyHidden = false
                cardTimer.visibility = View.GONE
                activeAdapter.setRunningTask(null)
                scheduleAdapter.setRunningTask(null)
                tvTimerDisplay.text = "00:00"
            }
            updateScheduleRankBadge()
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

        // ── Start / Pause / Cancel button ─────────────────────────────────────
        //
        // Single observer on timerCardAction. The ViewModel has already combined
        // noticePhase + timerRunning + currentTask into one atomic value, so this
        // fires ONCE per logical state change — not once per individual LiveData
        // update.  The click handler (setupTimerCard) dispatches from the same
        // object, so UI and behaviour are guaranteed to match.
        viewModel.timerCardAction.observe(this) { action ->
            btnStartPause.text    = action.label
            btnStartPause.icon    = null
            btnStartPause.isEnabled = action.enabled
            btnStartPause.backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(this, action.colorRes)
                )
            btnStartPause.jumpDrawablesToCurrentState()

            // Dot reflects timer state only when the card is manually hidden.
            // When card is visible the card itself shows the state — dot stays grey.
            updateScheduleNextDot()
        }

        // Phase-status bar — depends on NoticePhase subtype detail (remainingSecs)
        // that TimerCardAction intentionally omits, so driven separately here.
        viewModel.noticePhase.observe(this) { phase ->
            when (phase) {
                is NoticePhase.Delay -> {
                    viewPhaseStatus.setBackgroundColor(android.graphics.Color.parseColor("#FFB300"))
                    viewPhaseStatus.visibility = View.VISIBLE
                    val m = phase.remainingSecs / 60; val s = phase.remainingSecs % 60
                    tvTimerDisplay.text = "%02d:%02d".format(m, s)
                }
                is NoticePhase.Wait -> {
                    viewPhaseStatus.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    viewPhaseStatus.visibility = View.VISIBLE
                    val m = phase.remainingSecs / 60; val s = phase.remainingSecs % 60
                    tvTimerDisplay.text = "%02d:%02d".format(m, s)
                }
                else -> viewPhaseStatus.visibility = View.GONE
            }
            // Forward phase to adapters so the notice segmented bar shows live progress.
            // This fires on every Wait tick (postValue) and on Execute phase entry,
            // giving second-by-second fills.  Execute fill is driven by task.progressPercent
            // which updates via timerSeconds → currentTask → setRunningTask → notifyItemChanged.
            val noticeTaskId = viewModel.currentTask.value?.id
            activeAdapter.setNoticeState(noticeTaskId, phase)
            scheduleAdapter.setNoticeState(noticeTaskId, phase)
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
        viewModel.globalRotateEnabled.observe(this) { enabled ->
            globalRotateMenuItem?.isChecked = enabled
        }
        viewModel.allowEditEnabled.observe(this) { enabled ->
            allowEditMenuItem?.isChecked = enabled
            fabAdd.visibility = if (enabled) View.VISIBLE else View.GONE
            // Sync RecyclerView bottom padding with FAB presence.
            // When FAB is hidden the 80dp reserved gap is no longer needed;
            // keeping it causes smoothScrollToPosition to overscroll and bounce.
            val fabPadPx = if (enabled)
                (80 * resources.displayMetrics.density).toInt() else 0
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                fabPadPx
            )
        }
        viewModel.autoScrollEnabled.observe(this) { enabled ->
            autoScrollMenuItem?.isChecked = enabled
        }

        // ── Sync state → toolbar dot color ────────────────────────────────────
        viewModel.syncState.observe(this) { state -> updateSyncIcon(state) }

        // ── Remote sync import → restart app so Room opens the new DB cleanly ─
        viewModel.restartNeeded.observe(this) {
            Toast.makeText(
                this, "Sync received — reloading…", Toast.LENGTH_SHORT
            ).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val intent = packageManager.getLaunchIntentForPackage(packageName)!!
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 600)
        }

        // ── Next / Auto button ────────────────────────────────────────────────
        viewModel.nextButtonState.observe(this) { state ->
            btnScheduleNext.text = state.label
        }
        // Lock Global Rotate menu item while Auto mode is active
        viewModel.autoMode.observe(this) { auto ->
            globalRotateMenuItem?.isEnabled = !auto
        }

        // ── INT button ────────────────────────────────────────────────────────
        //
        // Previously three separate observers (slot, taskA, taskB) each read
        // the other two LiveData via .value at dispatch time — if dispatches
        // interleaved, the color and label could be set from mismatched values
        // for one frame.  Single observer on intButtonState fixes that.
        viewModel.intButtonState.observe(this) { state ->
            btnInt.text = state.label
            val color = if (state.textColorHex.isNotEmpty())
                android.graphics.Color.parseColor(state.textColorHex)
            else
                ContextCompat.getColor(this, R.color.colorPrimary)
            btnInt.setTextColor(color)
            btnInt.jumpDrawablesToCurrentState()
        }
    }

    /**
     * Scrolls the RecyclerView to the card of [taskId].
     *
     * Always uses an instant positional jump (scrollToPositionWithOffset) so the
     * target row appears on the very next frame regardless of how many rows are
     * between the current viewport and the destination.  The previous
     * smoothScrollToPosition call was removed because it animated through every
     * intermediate row — unacceptable with 100+ tasks in the list.
     */
    private fun scrollToTask(taskId: String) {
        // Only scroll within the currently visible tab — never switch tabs
        val currentAdapter = when (currentTab) {
            0    -> activeAdapter
            1    -> scheduleAdapter
            else -> return
        }
        val position = currentAdapter.currentList.indexOfFirst { it.task.id == taskId }
        if (position < 0) return

        val llm = recyclerView.layoutManager as? LinearLayoutManager ?: return

        // Guard: already at least partially on screen — nothing to do.
        // Prevents the card bouncing back on every timer tick.
        val firstVisible = llm.findFirstVisibleItemPosition()
        val lastVisible  = llm.findLastVisibleItemPosition()
        if (position in firstVisible..lastVisible) return

        // Instant jump — target row snaps to the top of the viewport in one
        // layout pass, no animation, no intermediate rows rendered.
        llm.scrollToPositionWithOffset(position, 0)
    }

    private fun updateScheduleRankBadge() {
        val runningId = viewModel.currentTask.value?.id
        val number = if (runningId != null) {
            viewModel.flatScheduleOrder.value
                ?.find { it.task.id == runningId }
                ?.queueNumber
        } else null
        if (!number.isNullOrEmpty()) {
            tvScheduleRank.text       = "#$number"
            tvScheduleRank.visibility = View.VISIBLE
        } else {
            tvScheduleRank.visibility = View.GONE
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
        groupsMenuItem       = menu.findItem(R.id.action_toggle_groups)
        groupsMenuItem?.isChecked = viewModel.groupsEnabled.value ?: false
        globalRotateMenuItem = menu.findItem(R.id.action_toggle_global_rotate)
        globalRotateMenuItem?.isChecked = viewModel.globalRotateEnabled.value ?: false
        allowEditMenuItem    = menu.findItem(R.id.action_allow_edit)
        allowEditMenuItem?.isChecked = viewModel.allowEditEnabled.value ?: false
        autoScrollMenuItem   = menu.findItem(R.id.action_auto_scroll)
        autoScrollMenuItem?.isChecked = viewModel.autoScrollEnabled.value ?: false

        // ── Sync icon action view ─────────────────────────────────────────────
        menu.findItem(R.id.action_sync)?.actionView?.let { syncView ->
            syncDotView  = syncView.findViewById(R.id.viewSyncDot)
            syncIconView = syncView.findViewById(R.id.ivSyncIcon)
            syncView.setOnClickListener {
                // Tap sync icon → trigger an immediate sync export
                viewModel.triggerSyncExport()
                Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show()
            }
        }

        // Action view supports both tap and long-press; a plain MenuItem only fires tap.
        menu.findItem(R.id.action_schedule_next)?.actionView?.let { view ->
            // Grab the status dot so timerCardAction observer can tint it.
            schedNextDotView = view.findViewById(R.id.viewScheduleNextDot)

            // Tap — two cases:
            //   Case 1: any non-interrupt leaf task is visible in the current tab
            //           → jump to the first visible leaf (interrupt tasks skipped).
            //   Case 2: all tasks are under collapsed groups (no non-interrupt leaf
            //           visible) → select the assigned interrupt task instead.
            view.setOnClickListener {
                val list = if (currentTab == 0) viewModel.flatActiveTasks.value
                           else                 viewModel.flatScheduleOrder.value
                val hasLeaves = list?.any {
                    !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt
                } == true
                haptic(view)
                if (hasLeaves) {
                    viewModel.jumpToFirst(onQueueTab = currentTab == 0)
                } else {
                    // No visible normal tasks — fall back to the active interrupt slot
                    viewModel.jumpToInterrupt()
                }
            }
            // Hold → toggle timer card open/closed (UI only — timer state unchanged).
            //   • Card visible    → hide it; dot switches to colored state
            //   • Card hidden + active task → show it; dot reverts to grey
            //   • Card hidden + no task     → no-op
            view.setOnLongClickListener {
                haptic(view)
                if (cardTimer.visibility == View.VISIBLE) {
                    isCardManuallyHidden = true
                    cardTimer.visibility = View.GONE
                } else if (viewModel.currentTask.value != null) {
                    isCardManuallyHidden = false
                    cardTimer.visibility = View.VISIBLE
                }
                updateScheduleNextDot()
                true
            }
        }
        // ── Overflow (3-dot) long-press: global group collapse / expand ───────
        // Deferred with toolbar.post so the overflow button is in the view tree.
        // Collapse if any non-interrupt leaf is visible; expand if all collapsed.
        // Interrupt-task ancestor groups are excluded from the toggle.
        findViewById<Toolbar>(R.id.toolbar)?.post {
            val desc = getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
            findViewByContentDesc(findViewById(R.id.toolbar), desc)
                ?.setOnLongClickListener { v ->
                    haptic(v)
                    val list = if (currentTab == 0) viewModel.flatActiveTasks.value
                               else                 viewModel.flatScheduleOrder.value
                    val hasLeaves = list?.any {
                        !it.task.isGroup && !it.task.isCompleted && !it.task.isInterrupt
                    } == true
                    viewModel.toggleAllGroupsGlobal(
                        onQueueTab       = currentTab == 0,
                        hasVisibleLeaves = hasLeaves
                    )
                    true
                }
        }

        return true
    }

    // ── Key1 (Schedule Next) dot update ──────────────────────────────────────

    /**
     * Updates the key1 status dot to reflect timer state — but only when the
     * timer card is manually hidden.  While the card is open (visible) the dot
     * stays grey because the card itself already shows the full state; coloring
     * the dot too would be redundant and visually noisy.
     *
     * Call from:
     *  • timerCardAction observer  — timer state changed
     *  • key1 hold handler         — card visibility toggled
     */
    private fun updateScheduleNextDot() {
        val dot = schedNextDotView ?: return
        val grey = android.graphics.Color.parseColor("#9E9E9E")
        val color = if (isCardManuallyHidden) {
            // Card is hidden — show actual timer state so user knows what's running
            val action = viewModel.timerCardAction.value
            if (action == null || action is TimerCardAction.Unavailable) grey
            else ContextCompat.getColor(this, action.colorRes)
        } else {
            // Card is visible (or no task) — grey dot; card shows the state
            grey
        }
        dot.visibility = View.VISIBLE
        dot.backgroundTintList = ColorStateList.valueOf(color)
    }

    // ── View-tree helper ──────────────────────────────────────────────────────

    /**
     * Recursively walks [root]'s view tree and returns the first child whose
     * [android.view.View.contentDescription] exactly matches [desc], or null.
     * Used to locate the overflow (3-dot) button by its AppCompat content-
     * description, which is the only stable cross-version identifier.
     */
    private fun findViewByContentDesc(
        root: android.view.ViewGroup,
        desc: String
    ): android.view.View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.contentDescription?.toString() == desc) return child
            if (child is android.view.ViewGroup) {
                val found = findViewByContentDesc(child, desc)
                if (found != null) return found
            }
        }
        return null
    }

    // ── Sync icon update ──────────────────────────────────────────────────────

    /**
     * Updates the sync status dot color and the sync icon spin animation
     * based on the current [SyncState].
     *
     *   Disabled / Idle  → gray dot,  no spin
     *   Syncing          → gray dot,  spin animation
     *   OK               → green dot, no spin
     *   Error            → red dot,   no spin
     */
    private fun updateSyncIcon(state: SyncState) {
        val dot  = syncDotView  ?: return
        val icon = syncIconView ?: return

        // Colors
        val color = when (state) {
            SyncState.OK       -> android.graphics.Color.parseColor("#4CAF50") // green
            is SyncState.Error -> android.graphics.Color.parseColor("#F44336") // red
            SyncState.Syncing  -> android.graphics.Color.parseColor("#FF9800") // amber
            else               -> android.graphics.Color.parseColor("#9E9E9E") // gray
        }
        dot.backgroundTintList = ColorStateList.valueOf(color)

        // Spin animation while syncing
        if (state == SyncState.Syncing) {
            if (syncSpinAnim?.isRunning != true) {
                syncSpinAnim = ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f).apply {
                    duration    = 1000
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        } else {
            syncSpinAnim?.cancel()
            syncSpinAnim = null
            icon.rotation = 0f
        }

        // Show a tooltip / snackbar on error so the user knows what went wrong
        if (state is SyncState.Error) {
            dot.contentDescription = "Sync error: ${state.message}"
        } else {
            dot.contentDescription = null
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_groups -> {
                viewModel.toggleGroupsEnabled()
                item.isChecked = viewModel.groupsEnabled.value ?: false
                true
            }
            R.id.action_toggle_global_rotate -> {
                if (viewModel.autoMode.value == true) {
                    Toast.makeText(this, "Global Rotate is managed by Auto mode", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.toggleGlobalRotate()
                    item.isChecked = viewModel.globalRotateEnabled.value ?: false
                }
                true
            }
            R.id.action_allow_edit -> {
                viewModel.toggleAllowEdit()
                item.isChecked = viewModel.allowEditEnabled.value ?: false
                true
            }
            R.id.action_auto_scroll -> {
                viewModel.toggleAutoScroll()
                item.isChecked = viewModel.autoScrollEnabled.value ?: false
                true
            }
            R.id.action_clear_completed -> { viewModel.clearCompleted(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
