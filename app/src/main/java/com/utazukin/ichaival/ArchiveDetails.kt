/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2019 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.utazukin.ichaival.ArchiveDetailsFragment.TagInteractionListener
import com.utazukin.ichaival.ThumbRecyclerViewAdapter.ThumbInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SEARCH_REQUEST = 1
const val BOOKMARK_REQUEST = 2

class ArchiveDetails : BaseActivity(), TagInteractionListener, ThumbInteractionListener {
    private var archiveId: String? = null
    private var pageCount = -1
    private lateinit var pager: ViewPager
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_details)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        intent.extras?.run {
            archiveId = getString("id")
            setUpDetailView()
        }

        launch {
            val archive = withContext(Dispatchers.Default) {
                val a = DatabaseReader.getArchive(archiveId!!, filesDir)
                a?.extract()
                a
            }
            archive?.run {
                pageCount = numPages
                if (pager.currentItem == 1)
                    supportActionBar?.subtitle = "$pageCount pages"
            }
        }
    }

    private fun setUpDetailView() {
        pager = findViewById(R.id.details_pager)
        pager.adapter = DetailsPagerAdapter(supportFragmentManager)
        pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            }

            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> supportActionBar?.run {
                        title = getString(R.string.details_title)
                        subtitle = null
                        toolbar.layoutTransition = LayoutTransition()
                    }
                    1 -> supportActionBar?.run {
                        title = getString(R.string.thumbs_title)
                        subtitle = if (pageCount >= 0) "$pageCount pages" else null
                    }
                }
            }

        })

        val tabLayout: TabLayout = findViewById(R.id.details_tabs)
        tabLayout.setupWithViewPager(pager)
    }

    override fun onTagInteraction(tag: String) {
        val intent = Intent(this, ArchiveSearch::class.java)
        val bundle = Bundle()
        bundle.putString(TAG_SEARCH, tag)
        intent.putExtras(bundle)
        startActivityForResult(intent, SEARCH_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SEARCH_REQUEST || requestCode == BOOKMARK_REQUEST) {
            if (resultCode == Activity.RESULT_OK)
                finish()
        }
    }

    override fun onThumbSelection(page: Int) {
        startReaderActivityForResult(page)
    }

    fun startReaderActivityForResult(page: Int = -1) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", archiveId)

        if (page >= 0)
            bundle.putInt("page", page)

        intent.putExtras(bundle)
        startActivityForResult(intent, BOOKMARK_REQUEST)
    }

    override fun onTabInteraction(tab: ReaderTab, longPress: Boolean) {
        if (!longPress || tab.id != archiveId) {
            super.onTabInteraction(tab, longPress)
            finish()
        }
    }

    override fun addIntentFlags(intent: Intent, id: String) {
        super.addIntentFlags(intent, id)
        if (id != archiveId)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    inner class DetailsPagerAdapter(manager: FragmentManager) : FragmentPagerAdapter(manager) {
        override fun getItem(position: Int): Fragment {
            return when(position) {
                0 -> ArchiveDetailsFragment.createInstance(archiveId!!)
                1 -> GalleryPreviewFragment.createInstance(archiveId!!)
                else -> throw IllegalArgumentException("position")
            }
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return when(position){
                0 -> getString(R.string.details_title)
                1 -> getString(R.string.thumbs_title)
                else -> null
            }
        }

        override fun getCount(): Int = 2
    }
}
