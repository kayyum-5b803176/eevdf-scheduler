package com.eevdf.scheduler.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.viewpager2.widget.ViewPager2
import com.eevdf.scheduler.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        val toolbar   = findViewById<Toolbar>(R.id.statsToolbar)
        val tabLayout = findViewById<TabLayout>(R.id.statsTabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.statsViewPager)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        viewPager.adapter = StatsPagerAdapter(this)
        viewPager.offscreenPageLimit = 2   // keep all 3 fragments alive

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0    -> "Overview"
                1    -> "Charts"
                else -> "Calendar"
            }
        }.attach()
    }
}
