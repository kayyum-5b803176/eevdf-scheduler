package com.eevdf.capabilities.addtaskscreen

import android.view.View
import com.google.android.material.tabs.TabLayout

/**
 * The edit screen's tab bar. It is PERMANENT and owned here, by nobody else:
 *
 *  - **Config** is always there — the bar exists from the moment the screen does, whatever the row is.
 *  - **ACL** is present only when the row has a LINK that gives it one (an axis door, or a hardlink placement). That is a fact
 *    about the row, known when the screen opens and set once by whichever ACL page applies ([setAclAvailable]) — never decided
 *    by data that is still loading, so it cannot flicker in and out, and it can never move you off the page you are on.
 *
 * Whoever shows the ACL page registers [onPage] to be told which page to show.
 */
internal class EditTabs(private val tabs: TabLayout) {

    /** Show the ACL page (true) or the Config page (false). */
    var onPage: (acl: Boolean) -> Unit = {}

    private var configTab: TabLayout.Tab = tabs.newTab().setText("Config")
    private var aclTab: TabLayout.Tab? = null

    init {
        tabs.visibility = View.VISIBLE
        tabs.addTab(configTab, true)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = onPage(tab == aclTab)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /** Add or remove the ACL tab. Removing it while it is open returns to Config first. Calling it again with the same value does nothing. */
    fun setAclAvailable(available: Boolean) {
        val existing = aclTab
        if (available && existing == null) {
            val tab = tabs.newTab().setText("ACL")
            aclTab = tab
            tabs.addTab(tab)
        } else if (!available && existing != null) {
            if (tabs.selectedTabPosition == existing.position) tabs.selectTab(configTab)      // it is open: go back to Config first
            tabs.removeTab(existing)
            aclTab = null
            onPage(false)
        }
    }
}
