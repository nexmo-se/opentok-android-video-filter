package com.nexmo.videofilter

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Handler
import android.os.HandlerThread
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.util.*
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.opentok.android.BaseVideoCapturer
import com.opentok.android.BaseVideoCapturer.CaptureSwitch
import com.opentok.android.Publisher
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.experimental.and


@TargetApi(21)
@RequiresApi(21)
class FilterVideoCapturer(context1: Context) :
    BaseVideoCapturer(), CaptureSwitch {
    private enum class CameraState {
        CLOSED, CLOSING, SETUP, OPEN, CAPTURE, ERROR
    }

    var context: Context = context1

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var camera: CameraDevice? = null
    private var cameraThread: HandlerThread? = null
    private var cameraThreadHandler: Handler? = null
    private var cameraFrame: ImageReader? = null
    private var captureRequest: CaptureRequest? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraInfoCache? = null
    private lateinit var cameraState: CameraState
    private val display: Display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    private var displayOrientationCache: DisplayOrientationCache? = null
    private val reentrantLock: ReentrantLock
    private val condition: Condition
    private var cameraIndex = 0
    private val frameDimensions: Size
    private val desiredFps: Int
    private var camFps: Range<Int>? = null
    private val runtimeExceptionList: MutableList<RuntimeException>
    private var isPaused: Boolean
    private var executeAfterClosed: Runnable? = null
    private var executeAfterCameraOpened: Runnable? = null

    /* Observers/Notification callback objects */
    private val cameraObserver: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "CameraDevice onOpened")
            cameraState = CameraState.OPEN
            this@FilterVideoCapturer.camera = camera
            if (executeAfterCameraOpened != null) {
                executeAfterCameraOpened!!.run()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            try {
                Log.d(TAG, "CameraDevice onDisconnected")
                camera.close()
            } catch (e: NullPointerException) {
                // does nothing
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            try {
                Log.d(TAG, "CameraDevice onError")
                camera.close()
                // wait for condition variable
            } catch (e: NullPointerException) {
                // does nothing
            }
            postAsyncException(Camera2Exception("Camera Open Error: $error"))
        }

        override fun onClosed(camera: CameraDevice) {
            Log.d(TAG, "CameraDevice onClosed")
            super.onClosed(camera)
            cameraState = CameraState.CLOSED
            this@FilterVideoCapturer.camera = null
            if (executeAfterClosed != null) {
                executeAfterClosed!!.run()
            }
        }
    }

    private fun imageToByteBuffer(image: Image): ByteBuffer {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()

        val planes = image.planes
        val rowData = ByteArray(planes[0].rowStride)
        val bufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8
        val output = ByteBuffer.allocate(bufferSize)

        var channelOffset = 0
        var outputStride = 0

        for (planeIndex in 0 until 3) {
            if (planeIndex == 0) {
                channelOffset = 0
                outputStride = 1
            } else if (planeIndex == 1) {
                channelOffset = width * height + 1
                outputStride = 2
            } else if (planeIndex == 2) {
                channelOffset = width * height
                outputStride = 2
            }

            val buffer = planes[planeIndex].buffer
            val rowStride = planes[planeIndex].rowStride
            val pixelStride = planes[planeIndex].pixelStride

            val shift = if (planeIndex == 0) 0 else 1
            val widthShifted = width shr shift
            val heightShifted = height shr shift

            buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))

            for (row in 0 until heightShifted) {
                var length = 0

                if (pixelStride == 1 && outputStride == 1) {
                    length = widthShifted
                    buffer.get(output.array(), channelOffset, length)
                    channelOffset += length
                } else {
                    length = (widthShifted - 1) * pixelStride + 1
                    buffer.get(rowData, 0, length)

                    for (col in 0 until widthShifted) {
                        output.array()[channelOffset] = rowData[col * pixelStride]
                        channelOffset += outputStride
                    }
                }

                if (row < heightShifted - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }

        return output
    }

    private fun rotateYuv(yuvByteBuffer: ByteBuffer, width: Int, height: Int, rotation: Int): ByteBuffer {
        if (rotation == 0) {
            return yuvByteBuffer
        }

        if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
            Log.e(TAG, "Invalid rotation degree - $rotation")
            return yuvByteBuffer
        }

        val yuvBuffer = yuvByteBuffer.array()
        val rotatedBuffer = ByteArray(yuvBuffer.size)
        val frameSize = width * height
        val swap = rotation % 180 != 0
        val xflip = rotation % 270 != 0
        val yflip = rotation >= 180

        for (j in 0 until height) {
            for (i in 0 until width) {
                val yIn = j * width + i
                val uIn = frameSize + (j shr 1) * width + (i and 1.inv())
                val vIn = uIn + 1

                val wOut = if (swap) height else width
                val hOut = if (swap) width else height
                val iSwapped = if (swap) j else i
                val jSwapped = if (swap) i else j
                val iOut = if (xflip) wOut - iSwapped - 1 else iSwapped
                val jOut = if (yflip) hOut - jSwapped - 1 else jSwapped

                val yOut = jOut * wOut + iOut
                val uOut = frameSize + (jOut shr 1) * wOut + (iOut and 1.inv())
                val vOut = uOut + 1

                rotatedBuffer[yOut] = yuvBuffer[yIn].and(0xff.toByte())
                rotatedBuffer[uOut] = yuvBuffer[uIn].and(0xff.toByte())
                rotatedBuffer[vOut] = yuvBuffer[vIn].and(0xff.toByte())
            }
        }

        return ByteBuffer.allocateDirect(rotatedBuffer.size).put(rotatedBuffer)
    }

    private fun toRgb(yuvBytes: ByteBuffer, width: Int, height: Int): ByteArray {
        // Convert using RenderScript
        val rs = RenderScript.create(context)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val allocationRgb = Allocation.createFromBitmap(rs, bitmap)
        val allocationYuv = Allocation.createSized(rs, Element.U8(rs), yuvBytes.array().size)
        allocationYuv.copyFrom(yuvBytes.array())
        val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
        scriptYuvToRgb.setInput(allocationYuv)
        scriptYuvToRgb.forEach(allocationRgb)
        allocationRgb.copyTo(bitmap)

        // Get RGB Array
        val rgbBuffer = ByteArray(allocationRgb.bytesSize)
        allocationRgb.copyTo(rgbBuffer)

        // Cleanup
        bitmap.recycle()
        allocationYuv.destroy()
        allocationRgb.destroy()
        rs.destroy()

        return rgbBuffer
    }

    private fun applyFilter(rgbBuffer: ByteArray): ByteArray {
        // To Gray Scale
        val greyBuffer = ByteArray(rgbBuffer.size)
        for (i in 0 until rgbBuffer.size step 4) {
            val r = rgbBuffer[i]
            val g = rgbBuffer[i + 1]
            val b = rgbBuffer[i + 2]
            val a = rgbBuffer[i + 3]

            val gray = g

            greyBuffer[i] = gray
            greyBuffer[i + 1] = gray
            greyBuffer[i + 2] = gray
            greyBuffer[i + 3] = a
        }

        return greyBuffer
    }

    private val frameObserver =
        OnImageAvailableListener { reader ->
            val frame = reader.acquireNextImage()
            if (frame == null || frame.planes.isNotEmpty() && frame.planes[0]
                    .buffer == null
                || frame.planes.size > 1 && frame.planes[1]
                    .buffer == null
                || frame.planes.size > 2 && frame.planes[2]
                    .buffer == null
            ) {
                Log.d(
                    TAG,
                    "onImageAvailable frame provided has no image data"
                )
                return@OnImageAvailableListener
            }

            // Get Raw Image ByteBuffer
            val rawYuvBytes = imageToByteBuffer(frame)

            // Rotate
            val surfaceRotation = (context as Activity).windowManager.defaultDisplay.rotation
            val rotationDegree = when(surfaceRotation) {
                Surface.ROTATION_0 -> 270
                Surface.ROTATION_90 -> 0
                Surface.ROTATION_180 -> 90
                Surface.ROTATION_270 -> 180
                else -> 0
            }
            val isLandscape = surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270
            val yuvBytes = rotateYuv(rawYuvBytes, frame.width, frame.height, rotationDegree)
            val newWidth = if (isLandscape) frame.width else frame.height
            val newHeight = if (isLandscape) frame.height else frame.width

            // Convert to RGB
            val rgbBuffer = toRgb(yuvBytes, newWidth, newHeight)

            // Apply Filter
            val filteredBuffer = applyFilter(rgbBuffer)

            // Send to Opentok
            if (CameraState.CAPTURE == cameraState) {
                provideByteArrayFrame(filteredBuffer, ABGR, newWidth, newHeight, Surface.ROTATION_0, false)
            }

            frame.close()
        }

    private val captureSessionObserver: CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "CaptureSession onConfigured")
                try {
                    cameraState = CameraState.CAPTURE
                    captureSession = session
                    captureRequest = captureRequestBuilder!!.build()
                    captureSession!!.setRepeatingRequest(
                        captureRequest!!,
                        captureNotification,
                        cameraThreadHandler
                    )
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.d(TAG, "CaptureSession onFailed")
                cameraState = CameraState.ERROR
                postAsyncException(Camera2Exception("Camera session configuration failed"))
            }

            override fun onClosed(session: CameraCaptureSession) {
                Log.d(TAG, "CaptureSession onClosed")
                camera?.close()
            }
        }
    private val captureNotification: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession, request: CaptureRequest,
            timestamp: Long, frameNumber: Long
        ) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }
    }

    /* caching of camera characteristics & display orientation for performance */
    private class CameraInfoCache(info: CameraCharacteristics) {
        private val info: CameraCharacteristics? = null
        var isFrontFacing = false
        private var sensorOrientation = 0
        operator fun <T> get(key: CameraCharacteristics.Key<T>?): T? {
            return info!![key]
        }

        fun sensorOrientation(): Int {
            return sensorOrientation
        }

        init {
            var info = info
            info = info
            /* its actually faster to cache these results then to always look
               them up, and since they are queried every frame...
             */isFrontFacing = (info.get(CameraCharacteristics.LENS_FACING)
                    == CameraCharacteristics.LENS_FACING_FRONT)
            sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION)!!.toInt()
        }
    }

    private class DisplayOrientationCache(
        private val display: Display,
        private val handler: Handler?
    ) :
        Runnable {
        var orientation: Int
            private set

        override fun run() {
            orientation = rotationTable[display.rotation]
            handler!!.postDelayed(this, POLL_DELAY_MS.toLong())
        }

        companion object {
            private const val POLL_DELAY_MS = 750 /* 750 ms */
        }

        init {
            orientation = rotationTable[display.rotation]
            handler!!.postDelayed(this, POLL_DELAY_MS.toLong())
        }
    }

    /* custom exceptions */
    class Camera2Exception(message: String?) :
        RuntimeException(message)

    /*
     * Initializes the video capturer.
     */
    @Synchronized
    override fun init() {
        Log.d(TAG, "init enter")
        characteristics = null
        // start camera looper thread
        startCamThread()
        // start display orientation polling
        startDisplayOrientationCache()
        // open selected camera
        initCamera()
        Log.d(TAG, "init exit")
    }

    private fun startCameraCapture(): Int {
        Log.d(TAG, "doStartCapture enter")
        try {
            // create camera preview request
            if (isFrontCamera) {
                captureRequestBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequestBuilder!!.addTarget(cameraFrame!!.surface)
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    camFps
                )
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                )
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
                )
                camera!!.createCaptureSession(
                    Arrays.asList(cameraFrame!!.surface),
                    captureSessionObserver,
                    cameraThreadHandler
                )
            } else {
                captureRequestBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                captureRequestBuilder!!.addTarget(cameraFrame!!.surface)
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    camFps
                )
                captureRequestBuilder!!.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                camera!!.createCaptureSession(
                    Arrays.asList(cameraFrame!!.surface),
                    captureSessionObserver,
                    cameraThreadHandler
                )
            }
        } catch (e: CameraAccessException) {
            throw Camera2Exception(e.message)
        }
        Log.d(TAG, "doStartCapture exit")
        return 0
    }

    /*
     * Starts capturing video.
     */
    @Synchronized
    override fun startCapture(): Int {
        Log.d(
            TAG,
            "startCapture enter (cameraState: $cameraState)"
        )
        executeAfterCameraOpened = if (null != camera && CameraState.OPEN == cameraState) {
            return startCameraCapture()
        } else if (CameraState.SETUP == cameraState) {
            Log.d(
                TAG,
                "camera not yet ready, queuing the start until camera is opened"
            )
            Runnable { startCameraCapture() }
        } else {
            throw Camera2Exception("Start Capture called before init successfully completed")
        }
        Log.d(TAG, "startCapture exit")
        return 0
    }

    /*
     * Stops capturing video.
     */
    @Synchronized
    override fun stopCapture(): Int {
        Log.d(TAG, "stopCapture enter")
        if (null != camera && null != captureSession && CameraState.CLOSED != cameraState) {
            cameraState = CameraState.CLOSING
            try {
                captureSession!!.stopRepeating()
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
            captureSession!!.close()
            cameraFrame!!.close()
            camera?.close()
            characteristics = null
        }
        Log.d(TAG, "stopCapture exit")
        return 0
    }

    /*
     * Destroys the BaseVideoCapturer object.
     */
    @Synchronized
    override fun destroy() {
        Log.d(TAG, "destroy enter")
        /* stop display orientation polling */stopDisplayOrientationCache()
        /* stop camera message thread */stopCamThread()
        Log.d(TAG, "destroy exit")
    }

    /*
     * Whether video is being captured (true) or not (false).
     */
    override fun isCaptureStarted(): Boolean {
        return cameraState == CameraState.CAPTURE
    }

    /*
     * Returns the settings for the video capturer.
     */
    @Synchronized
    override fun getCaptureSettings(): CaptureSettings {
        //log.d("getCaptureSettings enter");
        val retObj = CaptureSettings()
        retObj.fps = desiredFps
        retObj.width = if (null != cameraFrame) cameraFrame!!.width else 0
        retObj.height = if (null != cameraFrame) cameraFrame!!.height else 0
        retObj.format = NV21
        retObj.expectedDelay = 0
        //retObj.mirrorInLocalRender = frameMirrorX;
        //log.d("getCaptureSettings exit");
        return retObj
    }

    /*
     * Call this method when the activity pauses. When you override this method, implement code
     * to respond to the activity being paused. For example, you may pause capturing audio or video.
     *
     * @see #onResume()
     */
    @Synchronized
    override fun onPause() {
        Log.d(TAG, "onPause")
        when (cameraState) {
            CameraState.CAPTURE -> {
                stopCapture()
                isPaused = true
            }
            CameraState.SETUP -> {
            }
            else -> {
            }
        }
    }

    /*
     * Call this method when the activity resumes. When you override this method, implement code
     * to respond to the activity being resumed. For example, you may resume capturing audio
     * or video.
     *
     * @see #onPause()
     */
    override fun onResume() {
        Log.d(TAG, "onResume")
        if (isPaused) {
            val resume = Runnable {
                initCamera()
                startCapture()
            }
            if (cameraState == CameraState.CLOSING) {
                executeAfterClosed = resume
            } else if (cameraState == CameraState.CLOSED) {
                resume.run()
            }
            isPaused = false
        } else {
            Log.d(
                TAG,
                "Capturer was not paused when onResume was called"
            )
        }
    }

    @Synchronized
    override fun cycleCamera() {
        try {
            val camLst = cameraManager.cameraIdList
            swapCamera((cameraIndex + 1) % camLst.size)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            throw Camera2Exception(e.message)
        }
    }

    override fun getCameraIndex(): Int {
        return cameraIndex
    }

    @Synchronized
    override fun swapCamera(cameraId: Int) {
        val oldState = cameraState
        when (oldState) {
            CameraState.CAPTURE -> stopCapture()
            CameraState.SETUP -> {
            }
            else -> {
            }
        }
        /* set camera ID */cameraIndex = cameraId
        executeAfterClosed = Runnable {
            when (oldState) {
                CameraState.CAPTURE -> {
                    initCamera()
                    startCapture()
                }
                CameraState.SETUP -> {
                }
                else -> {
                }
            }
        }
    }

    private val isFrontCamera: Boolean
        private get() = characteristics != null && characteristics!!.isFrontFacing

    private fun startCamThread() {
        cameraThread = HandlerThread("Camera2VideoCapturer-Camera-Thread")
        cameraThread!!.start()
        cameraThreadHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCamThread() {
        try {
            cameraThread!!.quitSafely()
            cameraThread!!.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            // does nothing
        } finally {
            cameraThread = null
            cameraThreadHandler = null
        }
    }

    @Throws(CameraAccessException::class)
    private fun selectCamera(lenseDirection: Int): String? {
        for (id in cameraManager.cameraIdList) {
            val info = cameraManager.getCameraCharacteristics(id)
            /* discard cameras that don't face the right direction */if (lenseDirection == info.get(
                    CameraCharacteristics.LENS_FACING
                )
            ) {
                Log.d(
                    TAG,
                    "selectCamera() Direction the camera faces relative to device screen: " + info.get(
                        CameraCharacteristics.LENS_FACING
                    )
                )
                return id
            }
        }
        return null
    }

    @Throws(CameraAccessException::class)
    private fun selectCameraFpsRange(camId: String, fps: Int): Range<Int>? {
        for (id in cameraManager.cameraIdList) {
            if (id == camId) {
                val info = cameraManager.getCameraCharacteristics(id)
                val fpsLst: MutableList<Range<Int>> =
                    ArrayList()
                Collections.addAll(
                    fpsLst,
                    *info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                )
                /* sort list by error from desired fps *
                 * Android seems to do a better job at color correction/avoid 'dark frames' issue by
                 * selecting camera settings with the smallest lower bound on allowed frame rate
                 * range. */return Collections.min(
                    fpsLst,
                    object : Comparator<Range<Int>> {
                        override fun compare(
                            lhs: Range<Int>,
                            rhs: Range<Int>
                        ): Int {
                            return calcError(lhs) - calcError(rhs)
                        }

                        private fun calcError(`val`: Range<Int>): Int {
                            return `val`.lower + Math.abs(`val`.upper - fps)
                        }
                    })
            }
        }
        return null
    }

    @Throws(CameraAccessException::class)
    private fun findCameraIndex(camId: String?): Int {
        val idList = cameraManager.cameraIdList
        for (ndx in idList.indices) {
            if (idList[ndx] == camId) {
                return ndx
            }
        }
        return -1
    }

    @Throws(CameraAccessException::class)
    private fun selectPreferredSize(
        camId: String,
        width: Int,
        height: Int,
        format: Int
    ): Size {
        val info = cameraManager.getCameraCharacteristics(camId)
        val dimMap =
            info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizeLst: MutableList<Size> = ArrayList()
        val formats = dimMap!!.outputFormats
        Collections.addAll(
            sizeLst,
            *dimMap.getOutputSizes(ImageFormat.YUV_420_888)
        )
        /* sort list by error from desired size */return Collections.min(
            sizeLst
        ) { lhs, rhs ->
            val lXerror = Math.abs(lhs.width - width)
            val lYerror = Math.abs(lhs.height - height)
            val rXerror = Math.abs(rhs.width - width)
            val rYerror = Math.abs(rhs.height - height)
            lXerror + lYerror - (rXerror + rYerror)
        }
    }

    /*
     * Set current camera orientation
     */
    private fun calculateCamRotation(): Int {
        return if (characteristics != null) {
            val cameraRotation = displayOrientationCache!!.orientation
            val cameraOrientation = characteristics!!.sensorOrientation()
            if (!characteristics!!.isFrontFacing) {
                Math.abs((cameraRotation - cameraOrientation) % 360)
            } else {
                (cameraRotation + cameraOrientation + 360) % 360
            }
        } else {
            0
        }
    }

    @SuppressLint("all")
    private fun initCamera() {
        Log.d(TAG, "initCamera()")
        try {
            cameraState = CameraState.SETUP
            // find desired camera & camera ouput size
            val cameraIdList = cameraManager.cameraIdList
            val camId = cameraIdList[cameraIndex]
            camFps = selectCameraFpsRange(camId, desiredFps)
            val preferredSize = selectPreferredSize(
                camId,
                frameDimensions.width,
                frameDimensions.height,
                PIXEL_FORMAT
            )
            cameraFrame = ImageReader.newInstance(
                preferredSize.width,
                preferredSize.height,
                PIXEL_FORMAT,
                3
            )
            cameraFrame?.setOnImageAvailableListener(frameObserver, cameraThreadHandler)
            characteristics = CameraInfoCache(cameraManager.getCameraCharacteristics(camId))
            cameraManager.openCamera(camId, cameraObserver, cameraThreadHandler)
        } catch (exp: CameraAccessException) {
            throw Camera2Exception(exp.message)
        }
    }

    private fun postAsyncException(exp: RuntimeException) {
        runtimeExceptionList.add(exp)
    }

    private fun startDisplayOrientationCache() {
        displayOrientationCache = DisplayOrientationCache(display, cameraThreadHandler)
    }

    private fun stopDisplayOrientationCache() {
        cameraThreadHandler!!.removeCallbacks(displayOrientationCache)
    }

    companion object {
        private const val PREFERRED_FACING_CAMERA = CameraMetadata.LENS_FACING_FRONT
        private const val PIXEL_FORMAT = ImageFormat.YUV_420_888
        private val TAG = FilterVideoCapturer::class.java.simpleName
        private val rotationTable: SparseIntArray = object : SparseIntArray() {
            init {
                append(Surface.ROTATION_0, 0)
                append(Surface.ROTATION_90, 90)
                append(Surface.ROTATION_180, 180)
                append(Surface.ROTATION_270, 270)
            }
        }
        private val resolutionTable: SparseArray<Size?> =
            object : SparseArray<Size?>() {
                init {
                    append(
                        Publisher.CameraCaptureResolution.LOW.ordinal,
                        Size(352, 288)
                    )
                    append(
                        Publisher.CameraCaptureResolution.MEDIUM.ordinal,
                        Size(640, 480)
                    )
                    append(
                        Publisher.CameraCaptureResolution.HIGH.ordinal,
                        Size(1280, 720)
                    )
                }
            }
        private val frameRateTable: SparseIntArray = object : SparseIntArray() {
            init {
                append(Publisher.CameraCaptureFrameRate.FPS_1.ordinal, 1)
                append(Publisher.CameraCaptureFrameRate.FPS_7.ordinal, 7)
                append(Publisher.CameraCaptureFrameRate.FPS_15.ordinal, 15)
                append(Publisher.CameraCaptureFrameRate.FPS_30.ordinal, 30)
            }
        }
    }

    /* Constructors etc... */
    init {
        camera = null
        cameraState = CameraState.CLOSED
        reentrantLock = ReentrantLock()
        condition = reentrantLock.newCondition()
        frameDimensions =
            resolutionTable[Publisher.CameraCaptureResolution.HIGH.ordinal]!!
        desiredFps =
            frameRateTable[Publisher.CameraCaptureFrameRate.FPS_30.ordinal]
        runtimeExceptionList = ArrayList()
        isPaused = false
        try {
            var camId =
                selectCamera(PREFERRED_FACING_CAMERA)
            /* if default camera facing direction is not found, use first camera */if (null == camId && 0 < cameraManager.cameraIdList.size) {
                camId = cameraManager.cameraIdList[0]
            }
            cameraIndex = findCameraIndex(camId)
        } catch (e: CameraAccessException) {
            throw Camera2Exception(e.message)
        }
    }
}