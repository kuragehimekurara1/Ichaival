/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2021 Utazukin
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

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.*
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ServerSearchResult(val results: List<String>?,
                         val totalSize: Int = 0,
                         val filter: CharSequence = "",
                         val onlyNew: Boolean = false)

class ArchiveListDataFactory(private val localSearch: Boolean, pageSize: Int = 50) {
    var isSearch = false
    var currentSource: ArchiveDataSourceBase? = null
        private set
    private var results: List<String>? = null
    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private var totalResultCount = 0
    private var onlyNew = false
    private var filter: CharSequence = ""
    private var needsInitialization = true
    val pager: Pager<Int, Archive>

    init {
        pager = Pager(PagingConfig(pageSize, jumpThreshold = pageSize * 2), 0, ::create)
    }

    private fun create(): PagingSource<Int, Archive> {
        val latestSource = createDataSource()
        currentSource = latestSource
        needsInitialization = false
        return latestSource
    }

    private fun createDataSource() : ArchiveDataSourceBase {
        return if (localSearch)
            ArchiveListDataSource(results, sortMethod, descending, isSearch, !needsInitialization)
        else
            ArchiveListServerSource(results, sortMethod, descending, isSearch, !needsInitialization, totalResultCount, onlyNew, filter)
    }

    fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        if (sortMethod != method || descending != desc || force) {
            sortMethod = method
            descending = desc

            if (!localSearch)
                results = null

            needsInitialization = true
            currentSource?.invalidate()
        }
    }

    fun updateSearchResults(searchResults: List<String>?) {
        results = searchResults
        needsInitialization = true
        currentSource?.invalidate()
    }

    fun updateSearchResults(searchResult: ServerSearchResult) {
        results = searchResult.results
        onlyNew = searchResult.onlyNew
        filter = searchResult.filter
        totalResultCount = searchResult.totalSize
        needsInitialization = true
        currentSource?.invalidate()
    }

    fun reset() {
        needsInitialization = true
        currentSource?.invalidate()
    }
}

abstract class ArchiveDataSourceBase(protected val sortMethod: SortMethod,
                                     protected val descending: Boolean,
                                     protected val isSearch: Boolean,
                                     private var initialized: Boolean) : PagingSource<Int, Archive>() {
    protected val database by lazy { DatabaseReader.database }
    abstract val searchResults: List<String>?
    abstract val totalSize: Int
    override val jumpingSupported = true

    override fun getRefreshKey(state: PagingState<Int, Archive>) : Int? {
        return if (!initialized) {
            initialized = true
            null
        }
        else
            state.anchorPosition
    }

    protected suspend fun getArchives(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> = withContext(Dispatchers.IO) {
        if (isSearch && ids == null)
            emptyList<Archive>()

        when (sortMethod) {
            SortMethod.Alpha -> if (descending) database.getTitleDescending(ids, offset, limit) else database.getTitleAscending(ids, offset, limit)
            SortMethod.Date -> if (descending) database.getDateDescending(ids, offset, limit) else database.getDateAscending(ids, offset, limit)
        }
    }

    protected fun getSubList(startIndex: Int, endIndex: Int, list: List<String>) = if (endIndex < startIndex) list else list.subList(startIndex, endIndex)
}

class ArchiveListServerSource(results: List<String>?,
                              sortMethod: SortMethod,
                              descending: Boolean,
                              isSearch: Boolean,
                              initialized: Boolean,
                              override val totalSize: Int,
                              private val onlyNew: Boolean,
                              private val filter: CharSequence) : ArchiveDataSourceBase(sortMethod, descending, isSearch, initialized) {

    private val totalResults = mutableListOf<String>()
    override val searchResults: List<String>? = if (filter.isBlank() && !isSearch && !onlyNew) null else totalResults

    init {
        if (results != null)
            totalResults.addAll(results)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        return if (isSearch && filter.isBlank()) //Search mode with no search.  Display none.
            LoadResult.Page(emptyList(), null, null)
        else if (filter.isBlank() && !onlyNew) { //This isn't search mode.  Display all.
            when (params) {
                is LoadParams.Refresh -> {
                    val start = params.key?.let { if (it > 0) it else 0 } ?: 0
                    loadFull(start, params.loadSize)
                }
                is LoadParams.Append -> {
                    val start = if (params.key > 0) params.key else 0
                    loadFull(start, params.loadSize)
                }
                is LoadParams.Prepend -> loadFull(params.key, params.loadSize, true)
            }
        } else {
            when (params) {
                is LoadParams.Refresh -> {
                    val start = params.key?.let { if (it > 0) it else 0 } ?: 0
                    loadSearch(start, params.loadSize, refresh = true)
                }
                is LoadParams.Append -> {
                    val start = if (params.key > 0) params.key else 0
                    loadSearch(start, params.loadSize)
                }
                is LoadParams.Prepend -> loadSearch(params.key, params.loadSize, true)
            }
        }
    }

    private suspend fun loadFull(start: Int, loadSize: Int, prepend: Boolean = false) : LoadResult<Int, Archive> {
        val offset = if (prepend) start - loadSize else start
        val archives = getArchives(null, offset, loadSize)
        val prev = if (start > 0) start - 1 else null
        val size = withContext(Dispatchers.IO) { database.archiveDao().getArchiveCount() }
        val next = if (start + loadSize + 1 < size) start + loadSize + 1 else null
        val after = if (next != null) size - next else 0
        return LoadResult.Page(archives, prev, next, prev ?: 0, after)
    }

    private suspend fun loadSearch(start: Int, loadSize: Int, prepend: Boolean = false, refresh: Boolean = false) : LoadResult<Int, Archive> {
        val prev = if (start > 0) start - 1 else null
        var endIndex = if (prepend) max(start - loadSize, 0) else min(start + loadSize, totalSize)
        return if (endIndex <= totalResults.size) {
            val next = if (totalResults.size < totalSize) totalResults.size + 1 else null
            val after = if (next != null) totalSize - next else 0
            val ids = totalResults.subList(start, endIndex)
            val archives = getArchives(ids)
            LoadResult.Page(archives, prev, next, prev ?: 0, after)
        } else {
            DatabaseReader.refreshListener?.isRefreshing(true)

            loadResults(endIndex)
            if (!prepend)
                endIndex = min(start + loadSize, totalResults.size)

            val next = if (totalResults.size < totalSize) totalResults.size + 1 else null
            val after = if (next != null) totalSize - next else 0
            val ids = totalResults.subList(start, endIndex)
            val archives = getArchives(ids)
            DatabaseReader.refreshListener?.isRefreshing(false)
            val before = if (refresh && prev != null) start - 1 else prev
            LoadResult.Page(archives, prev, next, before ?: 0, after)
        }
    }

    private suspend fun loadResults(endIndex: Int, prepend: Boolean = false) = coroutineScope {
        val remaining = if (prepend) totalResults.size - endIndex else endIndex - totalResults.size
        val currentSize = totalResults.size
        val pages = floor(remaining.toFloat() / ServerManager.pageSize).toInt()
        val jobs = mutableListOf<Deferred<ServerSearchResult>>()
        for (i in 0 until pages) {
            val start = if (prepend) currentSize - i * ServerManager.pageSize else currentSize + i * ServerManager.pageSize
            val job = async(Dispatchers.IO, CoroutineStart.LAZY) { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, start, false) }
            jobs.add(job)
        }

        val start = if (prepend) currentSize - pages * ServerManager.pageSize else currentSize + pages * ServerManager.pageSize
        val job = async(Dispatchers.IO, CoroutineStart.LAZY) { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, start, false) }
        jobs.add(job)

        if (prepend)
            totalResults.addAll(0, jobs.awaitAll().mapNotNull { it.results }.flatten())
        else
            totalResults.addAll(jobs.awaitAll().mapNotNull { it.results }.flatten())
    }
}

class ArchiveListDataSource(results: List<String>?,
                            sortMethod: SortMethod,
                            descending: Boolean,
                            isSearch: Boolean,
                            initialized: Boolean) : ArchiveDataSourceBase(sortMethod, descending, isSearch, initialized) {
    override val searchResults = results
    override val totalSize: Int = results?.size ?: 0

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        return when (params) {
            is LoadParams.Append, is LoadParams.Refresh -> {
                val start = params.key?.let { if (it > 0) it else 0 } ?: 0
                val prev = if (start > 0) start - 1 else null
                val ids = searchResults?.let {
                    val endIndex = min(start + params.loadSize, it.size)
                    getSubList(start, endIndex, it)
                }
                val archives = if (ids != null) getArchives(ids) else getArchives(null, start, params.loadSize)
                val size = searchResults?.size ?: withContext(Dispatchers.IO) { database.archiveDao().getArchiveCount() }
                val next = if (start + params.loadSize < size) start + params.loadSize + 1 else null
                LoadResult.Page(archives, prev, next, prev ?: 0, if (next != null) size - next else 0)
            }
            is LoadParams.Prepend -> {
                val start = params.key
                val prev = if (start > 0) start - 1 else null
                val endIndex = max(start - params.loadSize, 0)
                val ids = searchResults?.let {
                    getSubList(endIndex, start - 1, it)
                }
                val archives = if (ids != null) getArchives(ids) else getArchives(null, endIndex, params.loadSize)
                val size = searchResults?.size ?: withContext(Dispatchers.IO) { database.archiveDao().getArchiveCount() }
                val next = if (start + params.loadSize < size) start + params.loadSize + 1 else null
                LoadResult.Page(archives, prev, next, prev ?: 0, if (next != null) size - next else 0)
            }
        }
    }
}