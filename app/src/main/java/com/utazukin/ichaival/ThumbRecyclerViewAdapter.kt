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

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThumbRecyclerViewAdapter(
    fragment: Fragment,
    private val archive: Archive)
    : RecyclerView.Adapter<ThumbRecyclerViewAdapter.ViewHolder>() {

    private val listener = fragment as? ThumbInteractionListener ?: fragment.activity as? ThumbInteractionListener
    private val glide = Glide.with(fragment.requireActivity())
    private val scope = fragment.lifecycleScope
    private val context = fragment.requireContext()
    private val defaultHeight = fragment.resources.getDimension(R.dimen.thumb_preview_size).toInt()

    private val onClickListener = View.OnClickListener { v ->
        val item = v.getTag(R.id.small_thumb) as Int
        listener?.onThumbSelection(item)
    }
    var maxThumbnails = 10
    val hasMorePreviews: Boolean
        get() = maxThumbnails < archive.numPages
    private val imageLoadingJobs: MutableMap<ViewHolder, Job> = mutableMapOf()

    fun increasePreviewCount() {
        if (maxThumbnails < archive.numPages) {
            val currentCount = itemCount
            maxThumbnails = archive.numPages
            notifyItemRangeInserted(currentCount + 1, maxThumbnails)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.thumb_layout, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = if (archive.numPages > maxThumbnails) maxThumbnails else archive.numPages

    override fun onBindViewHolder(holder: ViewHolder, page: Int) {
        holder.pageNumView.text = (page + 1).toString()

        with(holder.thumbView) {
            setTag(R.id.small_thumb, page)
            setOnClickListener(onClickListener)
        }

        val job = scope.launch {
            val image = withContext(Dispatchers.Default) { archive.getThumb(context, page) }

            glide.load(image)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(DrawableTransitionOptions.withCrossFade())
                .addListener(object: RequestListener<Drawable>{
                    override fun onLoadFailed(e: GlideException?,
                                              model: Any?,
                                              target: Target<Drawable>?,
                                              isFirstResource: Boolean): Boolean {
                        return false
                    }

                    override fun onResourceReady(resource: Drawable?,
                                                 model: Any?,
                                                 target: Target<Drawable>?,
                                                 dataSource: DataSource?,
                                                 isFirstResource: Boolean): Boolean {
                        with(holder.thumbView) {
                            updateLayoutParams { height = RelativeLayout.LayoutParams.WRAP_CONTENT }
                            adjustViewBounds = true
                        }
                        return false
                    }

                })
                .into(holder.thumbView)
        }
        imageLoadingJobs[holder] = job
    }

    override fun onViewRecycled(holder: ViewHolder) {
        imageLoadingJobs.remove(holder)?.cancel()

        with(holder.thumbView) {
            setImageDrawable(null)
            glide.clear(this)
            adjustViewBounds = false
            updateLayoutParams { height = defaultHeight }
        }

        super.onViewRecycled(holder)
    }

    interface ThumbInteractionListener {
        fun onThumbSelection(page: Int)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbView: ImageView = view.findViewById(R.id.small_thumb)
        val pageNumView: TextView = view.findViewById(R.id.page_num)
    }
}