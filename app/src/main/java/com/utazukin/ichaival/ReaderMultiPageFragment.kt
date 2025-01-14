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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.Size
import android.view.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.ceil

enum class PageCompressFormat {
    JPEG,
    PNG;

    companion object {
        fun PageCompressFormat.toBitmapFormat() : Bitmap.CompressFormat {
            return if (this == JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        }

        fun fromString(format: String?, context: Context?) : PageCompressFormat {
            return when(format) {
                context?.getString(R.string.jpg_compress) -> JPEG
                else -> PNG
            }
        }
    }
}

class ReaderMultiPageFragment : Fragment(), PageFragment {
    private var listener: ReaderFragment.OnFragmentInteractionListener? = null
    private var page = 0
    private var otherPage = 0
    private var imagePath: String? = null
    private var otherImagePath: String? = null
    private var mainImage: View? = null
    private lateinit var pageNum: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var topLayout: RelativeLayout
    private lateinit var failedMessageText: TextView
    private var createViewCalled = false
    private val currentScaleType
        get() = (activity as? ReaderActivity)?.currentScaleType
    private var archiveId: String? = null
    private var rtol: Boolean = false
    private var failedMessage: String? = null
    private var mergeJob: Job? = null
    private var verboseFailMessages = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        arguments?.run {
            page = getInt(PAGE_NUM)
            otherPage = getInt(OTHER_PAGE_ID)
            archiveId = getString(ARCHIVE_ID)
        }

        setHasOptionsMenu(true)

        rtol = if (savedInstanceState == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.getBoolean(getString(R.string.rtol_pref_key), false) == !prefs.getBoolean(getString(R.string.dual_page_swap_key), false)
        } else savedInstanceState.getBoolean(RTOL)

        topLayout = view.findViewById(R.id.reader_layout)
        pageNum = view.findViewById(R.id.page_num)
        with (pageNum) {
            text = "${page + 1}-${otherPage + 1}"
            visibility = View.VISIBLE
        }

        progressBar = view.findViewById(R.id.progressBar)
        with(progressBar) {
            isIndeterminate = true
            visibility = View.VISIBLE
        }

        //Tapping the view will display the toolbar until the image is displayed.
        with(view) {
            setOnClickListener { listener?.onFragmentTap(TouchZone.Center) }
            setOnLongClickListener { listener?.onFragmentLongPress() == true }
        }

        failedMessageText = view.findViewById(R.id.failed_message)
        failedMessageText.setOnClickListener { listener?.onImageLoadError() }
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                    if (e != null)
                        listener?.onFragmentTap(getTouchZone(e.x, view))
                    return true
                }
            })

        failedMessageText.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }

        imagePath?.let { displayImage(it, otherImagePath) }

        createViewCalled = true
        return view
    }


    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)
        if (menuVisible)
            failedMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }.also { failedMessage = null }
    }

    override fun onStop() {
        super.onStop()
        mergeJob?.cancel()
        mergeJob = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.swap_merged_page -> {
                topLayout.removeView(mainImage)
                (mainImage as? SubsamplingScaleImageView)?.recycle()
                mainImage = null
                pageNum.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true

                rtol = !rtol
                imagePath?.let { displayImage(it, otherImagePath) }
                true
            }
            R.id.split_merged_page -> {
                topLayout.removeView(mainImage)
                (mainImage as? SubsamplingScaleImageView)?.recycle()
                mainImage = null
                pageNum.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                imagePath?.let { displaySingleImage(it, otherPage, true) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageTapEvents(view: View) {
        when (view) {
            is SubsamplingScaleImageView -> {
                val gestureDetector =
                    GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                            if (view.isReady && e != null)
                                listener?.onFragmentTap(getTouchZone(e.x, view))
                            return true
                        }
                    })

                view.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
            }
            is PhotoView -> view.setOnViewTapListener { _, x, _ -> listener?.onFragmentTap(getTouchZone(x, view)) }
        }

        view.setOnLongClickListener { listener?.onFragmentLongPress() == true }
    }

    private suspend fun displaySingleImageMain(image: String, failPage: Int) = withContext(Dispatchers.Main) { displaySingleImage(image, failPage) }

    private fun displaySingleImage(image: String, failPage: Int, split: Boolean = false) {
        with(activity as ReaderActivity) { onMergeFailed(page, failPage, split) }
        pageNum.text = (page + 1).toString()

        progressBar.isIndeterminate = false
        lifecycleScope.launch {
            val imageFile = withContext(Dispatchers.IO) {
                var target: Target<File>? = null
                try {
                    target = downloadImageWithProgress(requireActivity(), image) {
                        progressBar.progress = it
                    }
                    target.get()
                } catch (e: Exception) {
                    null
                } finally {
                    activity?.let { Glide.with(it).clear(target) }
                }
            }

            if (imageFile == null) {
                failedMessageText.visibility = View.VISIBLE
                pageNum.visibility = View.GONE
                progressBar.visibility = View.GONE
                return@launch
            }

            val format = getImageFormat(imageFile)
            mainImage = if (format == ImageFormat.GIF) {
                PhotoView(activity).also {
                    initializeView(it)
                    Glide.with(requireActivity())
                        .load(imageFile)
                        .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                        .addListener(getListener())
                        .into(it)
                }
            } else {
                SubsamplingScaleImageView(activity).also {
                    initializeView(it)

                    it.setMaxTileSize(getMaxTextureSize())
                    it.setMinimumTileDpi(160)

                    if (format != null) {
                        it.setBitmapDecoderClass(ImageDecoder::class.java)
                        it.setRegionDecoderClass(ImageRegionDecoder::class.java)
                    }

                    it.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            pageNum.visibility = View.GONE
                            progressBar.visibility = View.GONE
                            view?.run {
                                setOnClickListener(null)
                                setOnLongClickListener(null)
                            }
                            updateScaleType(it, currentScaleType)
                        }
                        override fun onImageLoadError(e: Exception?) {
                            failedMessageText.visibility = View.VISIBLE
                            pageNum.visibility = View.GONE
                            progressBar.visibility = View.GONE
                            it.visibility = View.GONE
                        }
                    })

                    it.setImage(ImageSource.uri(imageFile.absolutePath))
                }
            }.also { setupImageTapEvents(it) }
        }
    }

    private fun createImageView(mergedPath: String, useNewDecoder: Boolean = true) {
        mainImage = SubsamplingScaleImageView(activity).apply {
            if (useNewDecoder) {
                setBitmapDecoderClass(ImageDecoder::class.java)
                setRegionDecoderClass(ImageRegionDecoder::class.java)
            }
            setOnImageEventListener(object: SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    pageNum.visibility = View.GONE
                    progressBar.visibility = View.GONE

                    view?.run {
                        setOnClickListener(null)
                        setOnLongClickListener(null)
                    }
                }
                override fun onImageLoadError(e: Exception?) {
                    topLayout.removeView(mainImage)
                    mainImage = null
                    recycle()
                    if (activity != null)
                        imagePath?.let { displaySingleImage(it, page) }
                }
            })
            initializeView(this)
            setMaxTileSize(getMaxTextureSize())
            setMinimumTileDpi(160)
            setImage(ImageSource.uri(mergedPath))
            setupImageTapEvents(this)
        }
    }

    private fun displayImage(image: String, otherImage: String?) {
        imagePath = image
        otherImagePath = otherImage

        if (otherImage == null) {
            displaySingleImage(image, page)
            return
        }

        mergeJob = lifecycleScope.launch(Dispatchers.Default) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val compressString = prefs.getString(getString(R.string.compression_type_pref), getString(R.string.jpg_compress))
            val compressType = PageCompressFormat.fromString(compressString, requireContext())
            val mergedPath = DualPageHelper.getMergedPage(requireContext().cacheDir, archiveId!!, page, otherPage, rtol, compressType)
            if (mergedPath != null) {
                withContext(Dispatchers.Main) { createImageView(mergedPath) }
                return@launch
            }

            withContext(Dispatchers.Main) { progressBar.isIndeterminate = false }

            var targetProgess = 0
            var otherProgress = 0
            val target = downloadImageWithProgress(requireActivity(), image) {
                targetProgess = it / 2
                progressBar.progress = ((targetProgess + otherProgress) * 0.9f).toInt()
            }
            val otherTarget = downloadImageWithProgress(requireActivity(), otherImage) {
                otherProgress = it / 2
                progressBar.progress = ((targetProgess + otherProgress) * 0.9f).toInt()
            }

            try {
                val dtarget = async { tryOrNull { target.get() } }
                val dotherTarget = async { tryOrNull { otherTarget.get() } }

                val imgFile = dtarget.await()
                if (imgFile == null) {
                    displaySingleImageMain(image, page)
                    return@launch
                }
                val img = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imgFile.absolutePath, img)
                if (img.outMimeType == null || ImageFormat.fromMimeType(img.outMimeType) == ImageFormat.GIF) {
                    dotherTarget.cancel()
                    displaySingleImageMain(image, page)
                    return@launch
                }

                val otherImgFile = dotherTarget.await()
                if (otherImgFile == null) {
                    displaySingleImageMain(image, otherPage)
                    return@launch
                }
                val otherImg = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(otherImgFile.absolutePath, otherImg)
                if (img.outMimeType == null || ImageFormat.fromMimeType(otherImg.outMimeType) == ImageFormat.GIF) {
                    displaySingleImageMain(image, otherPage)
                    return@launch
                }

                if (img.outWidth > img.outHeight || otherImg.outWidth > otherImg.outHeight) {
                    val otherImageFail = otherImg.outWidth > otherImg.outHeight
                    displaySingleImageMain(image, if (otherImageFail) otherPage else page)
                } else {
                    //Scale one of the images to match the smaller one if their heights differ too much.
                    val firstImg: Size
                    val secondImg: Size
                    when {
                        img.outHeight - otherImg.outHeight < -100 -> {
                            val ar = otherImg.outWidth / otherImg.outHeight.toFloat()
                            val width = ceil(img.outHeight * ar).toInt()
                            secondImg = Size(width, img.outHeight)
                            firstImg = img.outSize
                        }
                        otherImg.outHeight - img.outHeight < -100 -> {
                            val ar = img.outWidth / img.outHeight.toFloat()
                            val width = ceil(otherImg.outHeight * ar).toInt()
                            firstImg = Size(width, otherImg.outHeight)
                            secondImg = otherImg.outSize
                        }
                        else -> {
                            firstImg = img.outSize
                            secondImg = otherImg.outSize
                        }
                    }

                    val merged = try {
                        val mergeInfo = MergeInfo(firstImg, secondImg, imgFile, otherImgFile, page, otherPage, compressType, archiveId!!, !rtol)
                        DualPageHelper.mergeBitmaps(mergeInfo, requireContext().cacheDir, Glide.get(requireContext()).bitmapPool) { progressBar.progress = it }
                    } catch (e: Exception) { null }
                    catch (e: OutOfMemoryError) {
                        failedMessage = "Failed to merge pages: Out of Memory"
                        null
                    }
                    yield()
                    withContext(Dispatchers.Main) {
                        if (merged == null) {
                            progressBar.isIndeterminate = true
                            displaySingleImage(image, page)
                        } else {
                            progressBar.progress = 100
                            createImageView(merged)
                        }
                    }
                }
            } finally {
                target.cancel(false)
                otherTarget.cancel(false)
                activity?.let {
                    with(Glide.with(it)) {
                        clear(target)
                        clear(otherTarget)
                    }
                }
            }
        }
    }

    private fun initializeView(view: View) {
        view.background = ContextCompat.getDrawable(requireActivity(), android.R.color.black)
        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        view.layoutParams = layoutParams
        topLayout.addView(view)
        pageNum.bringToFront()
        progressBar.bringToFront()
    }

    private fun <T> getListener(clearOnReady: Boolean = true) : RequestListener<T> {
        return object: RequestListener<T> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<T>?,
                isFirstResource: Boolean
            ): Boolean {
                return false
            }

            override fun onResourceReady(
                resource: T?,
                model: Any?,
                target: Target<T>?,
                dataSource: DataSource?,
                isFirstResource: Boolean
            ): Boolean {
                if (clearOnReady) {
                    pageNum.visibility = View.GONE
                    view?.setOnClickListener(null)
                    view?.setOnLongClickListener(null)
                }
                progressBar.visibility = View.GONE
                return false
            }
        }
    }

    override fun reloadImage() {
        imagePath?.let { displayImage(it, otherImagePath) }
    }

    private fun updateScaleType(newScale: ScaleType) = updateScaleType(mainImage, newScale)

    private fun updateScaleType(imageView: View?, scaleType: ScaleType?, useOppositeOrientation: Boolean = false) {
        when (imageView) {
            is SubsamplingScaleImageView -> {
                when (scaleType) {
                    ScaleType.FitPage, null -> {
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                        imageView.resetScaleAndCenter()
                    }
                    ScaleType.FitHeight -> {
                        val vPadding = imageView.paddingBottom - imageView.paddingTop
                        val viewHeight = if (useOppositeOrientation) imageView.width else imageView.height
                        val minScale = (viewHeight - vPadding) / imageView.sHeight.toFloat()
                        imageView.minScale = minScale
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        imageView.setScaleAndCenter(minScale, PointF(0f, 0f))
                    }
                    ScaleType.FitWidth -> {
                        val hPadding = imageView.paddingLeft - imageView.paddingRight
                        val viewWidth = if (useOppositeOrientation) imageView.height else imageView.width
                        val minScale = (viewWidth - hPadding) / imageView.sWidth.toFloat()
                        imageView.minScale = minScale
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        imageView.setScaleAndCenter(minScale, PointF(0f, 0f))
                    }
                }
            }
            is PhotoView -> {
                //TODO
            }
        }
    }

    private fun getTouchZone(x: Float, view: View) : TouchZone {
        val location = x / view.width

        if (location <= 0.4)
            return TouchZone.Left

        if (location >= 0.6)
            return TouchZone.Right

        return TouchZone.Center
    }

    override fun onDetach() {
        super.onDetach()
        createViewCalled = false
        (activity as? ReaderActivity)?.unregisterPage(this)
        (mainImage as? SubsamplingScaleImageView)?.recycle()
    }

    override fun onScaleTypeChange(scaleType: ScaleType) = updateScaleType(scaleType)

    override fun onArchiveLoad(archive: Archive) {
        arguments?.run {
            val page = getInt(PAGE_NUM)
            val otherPage = getInt(OTHER_PAGE_ID)
            lifecycleScope.launch {
                val image = withContext(Dispatchers.IO) { archive.getPageImage(requireContext(), page) }
                val otherImage = withContext(Dispatchers.IO) { archive.getPageImage(requireContext(), otherPage) }
                if (image != null) {
                    if (createViewCalled)
                        displayImage(image, otherImage)
                    else {
                        imagePath = image
                        otherImagePath = otherImage
                    }
                } else
                    failedMessageText.visibility = View.VISIBLE
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (context as ReaderActivity).let {
            listener = it

            it.registerPage(this)
            it.archive?.let { a -> onArchiveLoad(a) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        with(outState) {
            putInt(PAGE_NUM, page)
            putString(PAGE_PATH, imagePath)
            putInt(OTHER_PAGE_ID, otherPage)
            putBoolean(RTOL, rtol)
            if (otherImagePath != null)
                putString(OTHER_PAGE_PATH, otherImagePath)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.run {
            page = getInt(PAGE_NUM)
            imagePath = getString(PAGE_PATH)
            otherPage = getInt(OTHER_PAGE_ID)
            otherImagePath = getString(OTHER_PAGE_PATH)
        }
    }

    companion object {
        private const val PAGE_NUM = "page"
        private const val OTHER_PAGE_ID = "other_page"
        private const val ARCHIVE_ID = "id"
        private const val PAGE_PATH = "pagePath"
        private const val OTHER_PAGE_PATH = "otherPagePath"
        private const val RTOL = "rtol"

        @JvmStatic
        fun createInstance(page: Int, otherPage: Int, archiveId: String?) =
            ReaderMultiPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(PAGE_NUM, page)
                    putInt(OTHER_PAGE_ID, otherPage)
                    putString(ARCHIVE_ID, archiveId)
                }
            }
    }
}