package com.eevdf.feature.task.list

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.eevdf.feature.task.addtask.AddTaskActivity
import com.eevdf.feature.shared.prefs.AutoSwitchPrefs
import com.eevdf.feature.shared.signals.BubbleEventBus
import com.eevdf.feature.shared.prefs.DisplayPrefs
import com.eevdf.feature.shared.prefs.HardwareKeyPrefs
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.feature.R
import com.eevdf.feature.task.adapter.TaskAdapter
import com.eevdf.data.task.Task
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.eevdf.platform.media.VibrationManager
import dagger.hilt.android.AndroidEntryPoint
import com.eevdf.contract.control.AlarmActions
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Composition root for the main task-list screen.
 *
 * Phase 10: this used to be a 1362-line, 54-function file — the single
 * heaviest shared-edit surface in the app. Four concerns were extracted into
 * their own delegates ([DisplayScaleDelegate], [TimerCardDelegate],
 * [MenuSyncDelegate], [ObserverDelegate]), following the exact composition
 * pattern [TaskViewModel] already used for its own 7. What's left here is
 * lifecycle glue, view lookup, and wiring — no behavior changed, every line
 * that remains or moved is the original.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    internal val viewModel: TaskViewModel by viewModels()

    internal lateinit var recyclerView:        RecyclerView
    internal lateinit var activeAdapter:       TaskAdapter
    internal lateinit var scheduleAdapter:     TaskAdapter
    internal lateinit var completedAdapter:    TaskAdapter
    private lateinit var tabLayout:           TabLayout
    internal lateinit var fabAdd:              FloatingActionButton
    internal lateinit var fabQuickAction:      FloatingActionButton
    internal lateinit var cardTimer:           CardView
    internal lateinit var tvCurrentTaskName:   TextView
    internal lateinit var tvTimerDisplay:      TextView
    internal lateinit var tvTimerPriority:     TextView
    internal lateinit var btnStartPause:       MaterialButton
    internal lateinit var btnInt:              MaterialButton
    internal lateinit var btnScheduleNext:     MaterialButton
    internal lateinit var tvStats:             TextView
    internal lateinit var tvFairness:          TextView
    private lateinit var tvScheduleRank:      TextView
    private lateinit var emptyView:           LinearLayout
    internal lateinit var viewPhaseStatus:     View

    // ── Expired/alarm block — dedicated views, fully styled in XML ─────────────
    internal lateinit var tvAlarmTaskName:     TextView
    private lateinit var tvAlarmSubtitle:     TextView
    internal lateinit var tvAlarmElapsed:      TextView
    private lateinit var btnStopAlarm:        MaterialButton

    // ── UI Customization: card content containers for height scaling ───────────
    internal lateinit var layoutTimerContent:  LinearLayout
    internal lateinit var layoutAlarmContent:  LinearLayout

    // ── Float-mode banner hiding ───────────────────────────────────────────────
    internal lateinit var mainToolbar:  Toolbar
    internal lateinit var statsBar:     LinearLayout

    /** True while the activity is in a floating or PiP window — compact stats hidden. */
    internal var isCompactModeActive: Boolean = false

    // ── Sync icon views (set in onCreateOptionsMenu after action view inflates) ──
    internal var syncDotView:  View?  = null
    internal var syncIconView: android.widget.ImageView? = null
    internal var syncSpinAnim: android.animation.ObjectAnimator? = null

    // ── Key1 (Schedule Next) status dot — set in onCreateOptionsMenu ──────────
    internal var schedNextDotView: View? = null

    /** True when the user manually hid the timer card via hold on key1.
     *  Prevents currentTask observer from re-showing the card until the user
     *  explicitly reopens it (hold key1 again). Cleared when task becomes null. */
    internal var isCardManuallyHidden: Boolean = false

    internal var currentTab = 0
    private val prefs by lazy { getSharedPreferences("eevdf_prefs", MODE_PRIVATE) }

    // ── Delegates (Phase 10) — constructed in onCreate right after setupViews(),
    // since all four need views that only exist once that call has run. ────────
    internal lateinit var displayScaleDelegate: DisplayScaleDelegate
    internal lateinit var timerCardDelegate:    TimerCardDelegate
    internal lateinit var menuSyncDelegate:     MenuSyncDelegate
    internal lateinit var observerDelegate:     ObserverDelegate

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

    // Load average ticker — foreground-only, every 60 s.  Re-publishes scheduler
    // stats so the "load: X.XX" figure in the stats bar decays live while the app
    // is visible.  The per-task EWMA is read lazily (integrated from the last
    // persisted update), so no DB writes happen here and nothing runs in the
    // background — exactly the requested behaviour.
    private val loadTickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val loadTickRunnable = object : Runnable {
        override fun run() {
            viewModel.refreshSchedule()
            loadTickHandler.postDelayed(this, 60_000L)
        }
    }

    /** Convenience: fire haptic feedback on [v] if enabled in prefs. */
    internal fun haptic(v: View) {
        if (!prefs.getBoolean(VibrationManager.KEY_HAPTIC, VibrationManager.DEFAULT_HAPTIC)) return
        v.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            @Suppress("DEPRECATION")
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
    internal var groupsMenuItem:       MenuItem? = null
    internal var globalRotateMenuItem: MenuItem? = null
    internal var allowEditMenuItem:    MenuItem? = null
    internal var autoScrollMenuItem:   MenuItem? = null

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

        // Delegates need views — construct them here, after setupViews(), before
        // anything below (setupObservers etc.) that calls into them.
        displayScaleDelegate = DisplayScaleDelegate(this)
        timerCardDelegate    = TimerCardDelegate(this)
        menuSyncDelegate     = MenuSyncDelegate(this)
        observerDelegate     = ObserverDelegate(this)

        // Restore the persisted manual-hide flag BEFORE observers fire, so the
        // first renderTimerCard() honours a card the user had closed by hand.
        // The persisted last-selected task is re-seated by the ViewModel's startup
        // recovery; whether its card actually shows is gated by this flag.
        isCardManuallyHidden = viewModel.getCardManuallyHidden()
        setupAdapters()
        setupRecyclerView()
        setupTabs()
        observerDelegate.setupObservers()
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
        timerCardDelegate.setupTimerCard()
        setupAlarmBanner()

        androidx.core.content.ContextCompat.registerReceiver(
            this, alarmStopReceiver,
            IntentFilter(AlarmActions.ACTION_STOP_ALARM),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModel.refreshSchedule()

        // Hardware-key "stop and start": AlarmActivity (shown over lock screen)
        // routed a restart request here.  Run it once the VM has settled.
        maybeHandleRestartAfterExpire(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleRestartAfterExpire(intent)
    }

    private fun maybeHandleRestartAfterExpire(intent: Intent?) {
        if (intent?.getBooleanExtra(
                com.eevdf.feature.alarm.AlarmActivity.EXTRA_RESTART_AFTER_EXPIRE, false
            ) == true
        ) {
            val taskName = intent.getStringExtra(
                com.eevdf.feature.alarm.AlarmActivity.EXTRA_TASK_NAME
            )
            // Clear the flag so a config change / re-create won't replay it.
            intent.removeExtra(
                com.eevdf.feature.alarm.AlarmActivity.EXTRA_RESTART_AFTER_EXPIRE
            )
            // Small delay lets the VM finish any startup alarm-state reconciliation
            // before we ask it to restart; the VM falls back to a DB lookup by name
            // if its in-memory restore-task is gone.
            cardTimer.postDelayed({ viewModel.restartAfterExpire(taskName) }, 120L)
        }
    }

    override fun onStart() {
        super.onStart()
        // Hover bubble: wire tap callback while Activity is visible.
        // Cleared in onStop() so BubbleOverlayService falls back to its own DB
        // path when the Activity is in the background — avoiding stale ViewModel
        // state and inactive LiveData observers causing wrong colour + no-op taps.
        BubbleEventBus.onBubbleTap = { viewModel.handleBubbleTap() }
        // Sync BubbleEventBus volatile fields immediately so the bubble dot
        // colour is correct if the service is already running (e.g. screen rotation
        // or returning from another app).
        val action = viewModel.timerCardAction.value
        val running = action is com.eevdf.feature.task.timer.TimerCardAction.Pause ||
            action is com.eevdf.feature.task.timer.TimerCardAction.Cancel
        val callSlot = AutoSwitchPrefs.getCallSlot(this)
        val callTask = when (callSlot) {
            "B"  -> viewModel.interruptTaskB.value
            else -> viewModel.interruptTask.value
        }
        BubbleEventBus.timerRunning    = running
        BubbleEventBus.anyTimerRunning = running
        BubbleEventBus.callTaskRunning = running &&
            callSlot != null && callTask != null &&
            viewModel.currentTask.value?.id == callTask.id
    }

    override fun onStop() {
        super.onStop()
        // Clear the tap callback — Activity is no longer visible.
        // BubbleOverlayService detects null and uses its direct DB path instead.
        BubbleEventBus.onBubbleTap = null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(alarmStopReceiver)
    }

    /**
     * Hardware-key handling for timer-expire actions.
     *
     * Only acts while a timer-expiry alarm is ringing (requirement #4); at any
     * other time the keys keep their normal system behaviour (volume change /
     * default handling).  The pressed key is mapped to its configured action via
     * [HardwareKeyPrefs]; an unbound key (NONE) is ignored and passed through.
     *
     * Note: KEYCODE_POWER is included for completeness, but on virtually all
     * Android builds the system consumes the power key before it reaches an
     * Activity's onKeyDown while the screen is on, so Volume Up / Volume Down are
     * the dependable bindings.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.isAlarmActive()) {
            val keyId = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP   -> HardwareKeyPrefs.KEY_VOLUME_UP
                KeyEvent.KEYCODE_VOLUME_DOWN -> HardwareKeyPrefs.KEY_VOLUME_DOWN
                KeyEvent.KEYCODE_POWER       -> HardwareKeyPrefs.KEY_POWER
                else                         -> null
            }
            if (keyId != null) {
                when (HardwareKeyPrefs.actionForKey(this, keyId)) {
                    HardwareKeyPrefs.ACTION_STOP -> {
                        viewModel.stopAlarmSound()
                        return true   // consume — suppress volume change while ringing
                    }
                    HardwareKeyPrefs.ACTION_RESTART -> {
                        viewModel.restartAfterExpire()
                        return true
                    }
                    else -> { /* NONE — fall through to default handling */ }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        quotaTickHandler.post(quotaTickRunnable)
        loadTickHandler.postDelayed(loadTickRunnable, 60_000L)
        viewModel.onSyncResume()
        // Reconcile ViewModel with DB in case CallSwitchService switched tasks
        // while the app was backgrounded or the process was dead.
        viewModel.syncFromDb()
        // Re-read UI customization prefs every time we come back to the activity
        // (user may have changed them in DisplaySettingsActivity and pressed Back)
        displayScaleDelegate.applyDisplayPrefs()
    }
    override fun onPause()  {
        super.onPause()
        quotaTickHandler.removeCallbacks(quotaTickRunnable)
        loadTickHandler.removeCallbacks(loadTickRunnable)
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
        if (DisplayPrefs.isAutoAdjustEnabled(this)) {
            displayScaleDelegate.applyDisplayPrefs()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (DisplayPrefs.isAutoAdjustEnabled(this)) {
            displayScaleDelegate.applyDisplayPrefs()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (DisplayPrefs.isAutoAdjustEnabled(this)) {
            displayScaleDelegate.applyDisplayPrefs()
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
        // Hold the stats bar → open the Task Statistics page
        statsBar.setOnLongClickListener {
            startActivity(com.eevdf.contract.nav.AppRoutes.stats(this))
            true
        }
        supportActionBar?.title = "EEVDF Task Scheduler"
    }

    private fun setupViews() {
        recyclerView      = findViewById(R.id.recyclerView)
        tabLayout         = findViewById(R.id.tabLayout)
        fabAdd            = findViewById(R.id.fabAdd)
        fabQuickAction    = findViewById(R.id.fabQuickAction)
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
        viewPhaseStatus   = findViewById(R.id.viewPhaseStatus)
        layoutTimerContent = findViewById(R.id.layoutTimerContent)
        layoutAlarmContent = findViewById(R.id.layoutAlarmContent)
        tvAlarmTaskName   = findViewById(R.id.tvAlarmTaskName)
        tvAlarmSubtitle   = findViewById(R.id.tvAlarmSubtitle)
        tvAlarmElapsed    = findViewById(R.id.tvAlarmElapsed)
        btnStopAlarm      = findViewById(R.id.btnStopAlarm)

        fabAdd.setOnClickListener {
            haptic(it)
            startActivity(Intent(this, AddTaskActivity::class.java))
        }

        // Quick Action: jump to the active INT task (A or B) then start timer.
        // A small post-delay lets the ViewModel settle currentTask before startTimer
        // is called — without it, startTimer() may see currentTask==null and no-op.
        fabQuickAction.setOnClickListener {
            haptic(it)
            viewModel.jumpToInterrupt()
            fabQuickAction.postDelayed({ viewModel.startTimer() }, 80L)
        }
    }

    private fun setupAlarmBanner() {
        btnStopAlarm.setOnClickListener { haptic(it); viewModel.stopAlarmSound() }
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
                updateScheduleRankBadge()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
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
    internal fun scrollToTask(taskId: String) {
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

    internal fun updateScheduleRankBadge() {
        val runningId = viewModel.currentTask.value?.id
        // Read rank from the list that matches the active tab — Queue tab uses
        // its own name-sorted order, Schedule tab uses EEVDF order. Completed
        // tab has no meaningful rank so the badge is hidden.
        val list = when (currentTab) {
            0    -> viewModel.flatActiveTasks.value
            1    -> viewModel.flatScheduleOrder.value
            else -> null
        }
        val number = if (runningId != null) {
            list?.find { it.task.id == runningId }?.queueNumber
        } else null
        if (!number.isNullOrEmpty()) {
            tvScheduleRank.text       = "#$number"
            tvScheduleRank.visibility = View.VISIBLE
        } else {
            tvScheduleRank.visibility = View.GONE
        }
    }

    internal fun updateEmptyView() {
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteTask(task) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = menuSyncDelegate.inflateMenu(menu)

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (menuSyncDelegate.handleItemSelected(item)) true else super.onOptionsItemSelected(item)
}
