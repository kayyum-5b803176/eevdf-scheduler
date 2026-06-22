package com.eevdf.app.feature.task

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
import com.eevdf.app.feature.autoswitch.AutoSwitchPrefs
import com.eevdf.app.feature.autoswitch.BubbleEventBus
import com.eevdf.app.feature.autoswitch.BubbleOverlayService
import com.eevdf.app.feature.autoswitch.CallEvents
import com.eevdf.app.feature.settings.UiCustomizationPrefs
import com.eevdf.app.feature.settings.QuickActionPrefs
import com.eevdf.app.feature.settings.HardwareKeyPrefs
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.app.R
import com.eevdf.app.feature.task.adapter.TaskAdapter
import com.eevdf.app.feature.task.notice.NoticePhase
import com.eevdf.data.task.Task
import com.eevdf.app.feature.task.timer.TimerCardAction
import com.eevdf.app.feature.task.timer.IntButtonState
import com.eevdf.app.feature.task.timer.NextButtonState
import com.eevdf.app.feature.task.TaskViewModel
import com.google.android.material.button.MaterialButton
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import com.eevdf.data.sync.SyncState
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.eevdf.app.feature.settings.VibrationManager
import com.eevdf.app.feature.alarm.AlarmStopReceiver
import com.eevdf.app.feature.notification.NotificationHelper
import com.eevdf.app.feature.stats.StatsActivity
import com.eevdf.app.feature.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var recyclerView:        RecyclerView
    private lateinit var activeAdapter:       TaskAdapter
    private lateinit var scheduleAdapter:     TaskAdapter
    private lateinit var completedAdapter:    TaskAdapter
    private lateinit var tabLayout:           TabLayout
    private lateinit var fabAdd:              FloatingActionButton
    private lateinit var fabQuickAction:      FloatingActionButton
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
    private lateinit var viewPhaseStatus:     View

    // ── UI Customization: card content container for height scaling ───────────
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
        // Restore the persisted manual-hide flag BEFORE observers fire, so the
        // first renderTimerCard() honours a card the user had closed by hand.
        // The persisted last-selected task is re-seated by the ViewModel's startup
        // recovery; whether its card actually shows is gated by this flag.
        isCardManuallyHidden = viewModel.getCardManuallyHidden()
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
                com.eevdf.app.feature.alarm.AlarmActivity.EXTRA_RESTART_AFTER_EXPIRE, false
            ) == true
        ) {
            val taskName = intent.getStringExtra(
                com.eevdf.app.feature.alarm.AlarmActivity.EXTRA_TASK_NAME
            )
            // Clear the flag so a config change / re-create won't replay it.
            intent.removeExtra(
                com.eevdf.app.feature.alarm.AlarmActivity.EXTRA_RESTART_AFTER_EXPIRE
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
        val running = action is TimerCardAction.Pause || action is TimerCardAction.Cancel
        val callTaskId = AutoSwitchPrefs.getCallTaskId(this)
        BubbleEventBus.timerRunning    = running
        BubbleEventBus.anyTimerRunning = running
        BubbleEventBus.callTaskRunning = running &&
            callTaskId != null &&
            viewModel.currentTask.value?.id == callTaskId
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
        // (user may have changed them in UiCustomizationActivity and pressed Back)
        applyDisplayPrefs()
    }
    override fun onPause()  {
        super.onPause()
        quotaTickHandler.removeCallbacks(quotaTickRunnable)
        loadTickHandler.removeCallbacks(loadTickRunnable)
    }

    /**
     * Reads card height scale and auto-adjust preference from [UiCustomizationPrefs]
     * and pushes them to all three adapters and the fixed timer / alarm cards.
     *
     * Called on [onResume] so changes made in [UiCustomizationActivity] are picked
     * up immediately when the user navigates back.
     *
     * Simple-mode note: when simple mode is enabled the RecyclerView item animator
     * is set to null.  In simple mode [setRunningTask] uses notifyItemChanged on
     * two cards (old running → collapse rows, new running → expand rows), and the
     * DefaultItemAnimator runs a ~250 ms crossfade on each change.  That animation
     * delays both the row-visibility update AND the auto-scroll jump, causing the
     * visual stutter reported by the user.  Removing the animator makes every card
     * change and every scroll instant.  When simple mode is off the animator is
     * restored so normal-mode list updates keep their default feel.
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

        // Simple mode: kill the item animator so notifyItemChanged-driven row
        // visibility changes and auto-scroll jumps are both instantaneous.
        // Non-simple mode: restore a fresh DefaultItemAnimator so normal
        // add/remove/change animations work as expected.
        recyclerView.itemAnimator = if (simpleMode) null
                                    else            androidx.recyclerview.widget.DefaultItemAnimator()

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

        // ── FAB hiding on float / mini profiles ───────────────────────────────
        // When auto-adjust is on and the window matches FLOAT or MINI, both FABs
        // are hidden — the window is too small for them to be useful and they
        // overlap content in compact mode.  NORMAL profile and uncalibrated
        // windows always show the FABs (subject to their own pref gates).
        val isCompactProfile = autoAdjust && matched != null &&
            UiCustomizationPrefs.isCompactProfile(matched)
        applyFabVisibility(isCompactProfile)

        // Scale the fixed cards (timer + alarm) to match task cards
        applyCardScaleToView(layoutTimerContent, scale, density)
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

    /**
     * Controls visibility of both FABs in one place so they always stay in sync.
     *
     * Rules:
     *  • [fabAdd] — visible when Allow Edit is enabled AND not in a compact
     *    (FLOAT / MINI) calibration profile.
     *  • [fabQuickAction] — visible when Quick Action pref is on AND not in a
     *    compact profile. Independent of Allow Edit.
     *
     * [suppressForCompactProfile] is true when auto-adjust is on and the current
     * window matches a FLOAT or MINI calibration profile — both FABs are hidden
     * in that case regardless of other prefs, because the window is too small.
     *
     * Called from [updateCompactMode] (which runs inside [applyDisplayPrefs] on
     * every [onResume] and every relevant configuration / window change).
     */
    private fun applyFabVisibility(suppressForCompactProfile: Boolean) {
        val editEnabled  = viewModel.allowEditEnabled.value ?: false
        val quickEnabled = QuickActionPrefs.isQuickActionEnabled(this)

        fabAdd.visibility =
            if (!suppressForCompactProfile && editEnabled) View.VISIBLE else View.GONE

        fabQuickAction.visibility =
            if (!suppressForCompactProfile && quickEnabled) View.VISIBLE else View.GONE

        // Keep RecyclerView bottom padding in sync with fabAdd presence so
        // smoothScrollToPosition doesn't overscroll when the FAB is hidden.
        val fabVisible = fabAdd.visibility == View.VISIBLE
        val fabPadPx   = if (fabVisible) (80 * resources.displayMetrics.density).toInt() else 0
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop,
            recyclerView.paddingRight,
            fabPadPx
        )
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
        // Hold the stats bar → open the Task Statistics page
        statsBar.setOnLongClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
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
        // Stop is now wired through the unified btnStartPause click handler in
        // setupTimerCard(). When timerCardAction == Expired, clicking btnStartPause
        // calls viewModel.stopAlarmSound(). Nothing to do here; kept so the
        // onCreate call site compiles without further changes.
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

    /**
     * Renders the merged timer card from a single [TimerCardAction].
     *
     * KEY DESIGN: this function NEVER changes the visibility of any child layout
     * or removes views from the hierarchy. Doing so causes the CardView to
     * remeasure, which shifts the RecyclerView below it — the "layout jump" bug.
     *
     * Instead, both the countdown state and the expired/alarm state use the EXACT
     * same view hierarchy. Only text content, colors, and enabled/alpha states are
     * updated in-place:
     *
     *   tvCurrentTaskName  — task name (both states, same text)
     *   tvTimerPriority    — priority line  OR  "Time slice complete…" subtitle
     *   tvTimerDisplay     — countdown  OR  elapsed overrun counter
     *   btnScheduleNext    — INVISIBLE (not GONE) in expired state to hold layout height
     *   btnStartPause      — Start/Pause/Cancel  OR  Stop (full-width either way)
     *   btnInt             — INVISIBLE (not GONE) in expired state
     *
     * Card visibility rule:
     *   Hidden  → card GONE (no task selected, nothing to show)
     *   Expired → always VISIBLE regardless of manual-hide (alarm must be seen)
     *   others  → VISIBLE unless the user manually closed it (isCardManuallyHidden)
     */
    private fun renderTimerCard(action: TimerCardAction) {
        val expiredRed = android.graphics.Color.parseColor("#B71C1C")
        val normalBg   = ContextCompat.getColor(this, R.color.colorPrimary)

        when (action) {
            is TimerCardAction.Hidden -> {
                cardTimer.visibility = View.GONE
                return
            }

            is TimerCardAction.Expired -> {
                // Alarm view: card always visible, red tint, shared views carry alarm data.
                cardTimer.setCardBackgroundColor(expiredRed)
                cardTimer.visibility = View.VISIBLE

                // tvCurrentTaskName: same task name — the currentTask observer already
                // wrote it; nothing to do unless the alarm fired before currentTask
                // was set (timer expiry path nulls currentTask), in which case write it.
                if (viewModel.currentTask.value == null) {
                    tvCurrentTaskName.text = action.taskName
                }
                // Re-use the priority line as the subtitle.
                tvTimerPriority.text  = "Time slice complete · tap Stop to dismiss"
                tvTimerPriority.setTextColor(android.graphics.Color.parseColor("#FFCDD2"))

                // Re-use the big number for the elapsed overrun counter.
                tvTimerDisplay.text = NotificationHelper.formatElapsed(action.elapsedSeconds)

                // Side buttons disappear visually but hold their space (INVISIBLE ≠ GONE).
                btnScheduleNext.visibility = View.INVISIBLE
                btnInt.visibility          = View.INVISIBLE

                // btnStartPause becomes "Stop" — same view, full width already.
                btnStartPause.text      = action.label          // "Stop"
                btnStartPause.icon      = null
                btnStartPause.isEnabled = true
                btnStartPause.backgroundTintList =
                    ColorStateList.valueOf(android.graphics.Color.parseColor("#FFFFFF"))
                btnStartPause.setTextColor(expiredRed)
                btnStartPause.jumpDrawablesToCurrentState()
            }

            else -> {
                // Normal countdown states: Start / Pause / Cancel / Unavailable.
                cardTimer.setCardBackgroundColor(normalBg)
                cardTimer.visibility =
                    if (isCardManuallyHidden) View.GONE else View.VISIBLE

                // Restore the priority subtitle text color to the normal faded-white.
                tvTimerPriority.setTextColor(android.graphics.Color.parseColor("#AAFFFFFF"))
                // Content is written by the currentTask observer — nothing to do here.

                // Restore side buttons.
                btnScheduleNext.visibility = View.VISIBLE
                btnInt.visibility          = View.VISIBLE

                // Restore btnStartPause text color to white (it was red in expired state).
                btnStartPause.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                btnStartPause.text      = action.label
                btnStartPause.icon      = null
                btnStartPause.isEnabled = action.enabled
                btnStartPause.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, action.colorRes))
                btnStartPause.jumpDrawablesToCurrentState()
            }
        }
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
            when (viewModel.timerCardAction.value) {
                TimerCardAction.Start          -> viewModel.startTimer()
                TimerCardAction.Pause          -> viewModel.pauseTimer()
                TimerCardAction.Cancel         -> viewModel.cancelNotice()
                TimerCardAction.Unavailable    -> Unit   // disabled — no-op
                TimerCardAction.Hidden         -> Unit   // card not shown — no-op
                is TimerCardAction.Expired     -> viewModel.stopAlarmSound()  // Stop
                null                           -> Unit   // not yet derived — no-op
            }
        }

        // Hold Start/Pause → pause the running task, then close the card by
        // DESELECTING the task (not the UI-only hide used by the key1 hold).
        // pauseAndDeselect() pauses (crediting the partial session, preserving
        // progress) then clears currentTask; the currentTask observer closes the
        // card and clears the running highlight. Reselecting the task resumes it.
        btnStartPause.setOnLongClickListener {
            if (viewModel.currentTask.value == null) return@setOnLongClickListener false
            haptic(it)
            viewModel.pauseAndDeselect()
            true
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
                CallEvents.Type.CALL_STARTED -> {
                    // CallSwitchService has already written the DB switch and
                    // started the bubble. We call handleCallStarted here only
                    // to keep the ViewModel in-memory state (savedTaskBeforeCall,
                    // wasTimerRunning) in sync so CALL_ENDED can restore correctly
                    // if the Activity is alive for the whole call.
                    viewModel.handleCallStarted(callTaskId)
                }
                CallEvents.Type.CALL_ENDED -> viewModel.handleCallEnded()
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
                com.eevdf.data.task.TaskDisplayItem(it, 0)
            })
            updateEmptyView()
        }

        viewModel.currentTask.observe(this) { task ->
            if (task != null) {
                // Content only — card VISIBILITY is owned solely by the
                // timerCardAction observer below (single source of truth).
                tvCurrentTaskName.text = task.name
                tvTimerPriority.text = "Priority ${task.priority} · ${task.category}"
                tvTimerDisplay.text = task.remainingDisplay
                activeAdapter.setRunningTask(task.id)
                scheduleAdapter.setRunningTask(task.id)
                if (viewModel.autoScrollEnabled.value == true) scrollToTask(task.id)
                // Auto mode: onTimerFinished queued the next task — start it immediately
                if (viewModel.consumePendingAutoStart()) viewModel.startTimer()
            } else {
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

        // ── Merged timer card — SINGLE source of truth ────────────────────────
        //
        // One observer on timerCardAction drives the ENTIRE card: visibility,
        // which child layout is shown (countdown vs expired/alarm), the card
        // tint, the button, and the alarm fields. The ViewModel has already
        // combined currentTask + noticePhase + timerRunning + alarm state into
        // this one atomic value.
        //
        // Bug 3 fix: mutual exclusivity between the (former) two cards is no
        // longer enforced by hand-toggling cardTimer.visibility from a separate
        // alarm observer. There is one card and one observer; impossible to show
        // both the countdown and the alarm at once.
        viewModel.timerCardAction.observe(this) { action ->
            renderTimerCard(action)

            // Dot reflects timer state only when the card is manually hidden.
            // When card is visible the card itself shows the state — dot stays grey.
            updateScheduleNextDot()

            // Keep the hover bubble dot in sync via the in-process volatile bus.
            val isRunning = action is TimerCardAction.Pause || action is TimerCardAction.Cancel
            val callTaskId = AutoSwitchPrefs.getCallTaskId(this)
            BubbleEventBus.timerRunning    = isRunning
            BubbleEventBus.anyTimerRunning = isRunning
            BubbleEventBus.callTaskRunning = isRunning &&
                callTaskId != null &&
                viewModel.currentTask.value?.id == callTaskId
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
            tvStats.text    = "Active: ${stats.activeTasks}  |  Done: ${stats.completedTasks}"
            tvFairness.text = "Fairness: ${"%.0f".format(stats.fairnessScore * 100)}%  |  load: ${"%.2f".format(stats.systemLoad)}"
        }

        viewModel.toastMessage.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.clearToast()
            }
        }

        // The elapsed overrun counter ticks once per second via its own LiveData.
        // In the unified layout tvTimerDisplay is the big-number view for both the
        // countdown AND the elapsed overrun — update it here on every tick so the
        // counter animates smoothly without waiting for the next timerCardAction emit.
        viewModel.alarmElapsedSeconds.observe(this) { elapsed ->
            if (viewModel.timerCardAction.value is TimerCardAction.Expired) {
                tvTimerDisplay.text = NotificationHelper.formatElapsed(elapsed)
            }
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
            // FAB visibility and RecyclerView bottom padding are both managed by
            // applyFabVisibility so the compact-profile gate is applied consistently.
            val autoAdj = UiCustomizationPrefs.isAutoAdjustEnabled(this)
            val widthDp = resources.configuration.screenWidthDp
            val heightDp = resources.configuration.screenHeightDp
            val matched = UiCustomizationPrefs.matchProfile(this, widthDp, heightDp)
            val suppress = autoAdj && matched != null &&
                UiCustomizationPrefs.isCompactProfile(matched)
            applyFabVisibility(suppress)
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
            //   • Alarm ringing → no-op (the expired card must stay visible)
            //   • Card visible  → hide it; persist; dot switches to colored state
            //   • Card hidden + active task → show it; persist; dot reverts to grey
            //   • Card hidden + no task     → no-op
            view.setOnLongClickListener {
                haptic(view)
                val action = viewModel.timerCardAction.value
                when {
                    action is TimerCardAction.Expired -> Unit   // can't hide a ringing alarm
                    cardTimer.visibility == View.VISIBLE -> {
                        isCardManuallyHidden = true
                        cardTimer.visibility = View.GONE
                        viewModel.setCardManuallyHidden(true)
                    }
                    viewModel.currentTask.value != null -> {
                        isCardManuallyHidden = false
                        cardTimer.visibility = View.VISIBLE
                        viewModel.setCardManuallyHidden(false)
                    }
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
            // Card is hidden — show actual timer state so user knows what's running.
            // Hidden (no task) and Unavailable have no meaningful colour → grey.
            // Expired never reaches here (the alarm forces the card visible).
            val action = viewModel.timerCardAction.value
            when (action) {
                null,
                is TimerCardAction.Hidden,
                is TimerCardAction.Unavailable -> grey
                else -> ContextCompat.getColor(this, action.colorRes)
            }
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
