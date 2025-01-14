/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.viewModelScope
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object ReaderTabHolder {
    private var initialized = false
    private var tabCount = 0
    private val scope by lazy { MainScope() }

    private val removeListeners = mutableSetOf<TabRemovedListener>()

    private val addListeners = mutableSetOf<TabAddedListener>()

    private val clearListeners = mutableSetOf<TabsClearedListener>()

    suspend fun updatePageIfTabbed(id: String, page: Int) : Boolean = DatabaseReader.updateBookmark(id, page)

    fun registerRemoveListener(listener: TabRemovedListener) = removeListeners.add(listener)

    fun unregisterRemoveListener(listener: TabRemovedListener) = removeListeners.remove(listener)

    fun registerAddListener(listener: TabAddedListener) = addListeners.add(listener)

    fun unregisterAddListener(listener: TabAddedListener) = addListeners.remove(listener)

    fun registerClearListener(listener: TabsClearedListener) = clearListeners.add(listener)

    fun unregisterClearListener(listener: TabsClearedListener) = clearListeners.remove(listener)

    suspend fun addTab(archive: Archive, page: Int) {
        if (!isTabbed(archive.id)) {
            val tab = ReaderTab(archive.id, archive.title, tabCount, page)
            archive.currentPage = page
            if (page > 0)
                WebHandler.updateProgress(archive.id, page)
            DatabaseReader.addBookmark(tab)
            updateAddListeners(archive.id)
        }
    }

    fun insertTab(tab: ReaderTab) {
        scope.launch { DatabaseReader.insertBookmark(tab) }
        updateAddListeners(tab.id)
    }

    suspend fun addTabs(archives: List<Archive>) {
        val ids = buildList(archives.size) {
            for (archive in archives) {
                if (!isTabbed(archive.id)) {
                    val tab = ReaderTab(archive.id, archive.title, tabCount, 0)
                    archive.currentPage = 0
                    DatabaseReader.addBookmark(tab)
                    add(archive.id)
                }
            }
        }

        updateAddListeners(ids)
    }

    fun addReaderTabs(tabs: List<ReaderTab>) {
        scope.launch(Dispatchers.IO) {
            for (tab in tabs)
                DatabaseReader.addBookmark(tab)
        }
        updateAddListeners(tabs.map { it.id })
    }

    fun createTab(id: String, title: String, page: Int) = ReaderTab(id, title, tabCount, page)

    suspend fun addTab(id: String, page: Int) {
        DatabaseReader.getArchive(id)?.let { addTab(it, page) }
    }

    fun initialize(context: FragmentActivity) {
        if (!initialized) {
            val viewModel = ViewModelProviders.of(context)[ReaderTabViewModel::class.java]
            viewModel.viewModelScope.launch { viewModel.bookmarks.collectLatest { tabCount = DatabaseReader.database.archiveDao().getBookmarkCount() } }
            initialized = true
        }
    }

    suspend fun isTabbed(id: String) = DatabaseReader.isBookmarked(id)

    fun removeTab(id: String) {
        scope.launch {
            if (DatabaseReader.removeBookmark(id)) {
                updateRemoveListeners(id)
            }
        }
    }

    fun resetServerProgress(id: String) = scope.launch {
        WebHandler.updateProgress(id, 0)
    }

    fun removeAll() {
        scope.launch(Dispatchers.IO) { DatabaseReader.clearBookmarks() }
        updateClearListeners()
    }

    fun resetServerProgress(tabs: List<ReaderTab>) = scope.launch(Dispatchers.IO) {
        for (tab in tabs)
            WebHandler.updateProgress(tab.id, 0)
    }

    private fun updateRemoveListeners(id: String) {
        for (listener in removeListeners)
            listener.onTabRemoved(id)
    }

    private fun updateAddListeners(id: String){
        for (listener in addListeners)
            listener.onTabAdded(id)
    }

    private fun updateAddListeners(ids: List<String>) {
        for (listener in addListeners)
            listener.onTabsAdded(ids)
    }

    private fun updateClearListeners() {
        for (listener in clearListeners)
            listener.onTabsCleared()
    }
}

@Entity
data class ReaderTab(
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo var index: Int,
    @ColumnInfo(name = "currentPage") var page: Int)


