package com.nexmo.videofilter

import android.os.Handler
import android.util.Log
import android.view.Surface
import com.opentok.android.BaseVideoCapturer
import java.util.*

class FilterVideoCapturer(width: Int, height: Int) : BaseVideoCapturer() {
    companion object {
        const val TAG = "FilterVideoCapturer"
        const val FPS = 15
    }

    private var mCaptureSettings: CaptureSettings
    private var mCapturerHasStarted = false
    private var mCapturerIsPaused = false
    private var mWidth = width
    private var mHeight = height

    private val mFrameProducerIntervalMillis: Long = 1000L / FPS
    private val mFrameProducerHandler: Handler = Handler()


    private var mFrameProducer: Runnable = Runnable {
        val random = Random()
        val buffer = ByteArray(mWidth * mHeight * 4)

        for (i in 0 until mWidth * mHeight * 4 step 4) {
            val randoms = ByteArray(4)
            random.nextBytes(randoms)
            buffer[i] = randoms[0]
            buffer[i + 1] = randoms[1]
            buffer[i + 2] = randoms[2]
            buffer[i + 3] = randoms[3]
        }

        provideByteArrayFrame(buffer, ARGB, mWidth, mHeight, Surface.ROTATION_0, false)

        if (mCapturerHasStarted && !mCapturerIsPaused) {
            runPostDelayed()
        }
    }

    init {
        mCaptureSettings = CaptureSettings()
        mCaptureSettings.height = mHeight
        mCaptureSettings.width = mWidth
        mCaptureSettings.format = ARGB
        mCaptureSettings.fps = FPS
        mCaptureSettings.expectedDelay = 0
    }

    private fun runPostDelayed() {
        mFrameProducerHandler.postDelayed(mFrameProducer, mFrameProducerIntervalMillis)
    }


    override fun onResume() {
        Log.d(TAG, "onResume")
        mCapturerIsPaused = false
        runPostDelayed()
    }

    override fun stopCapture(): Int {
        Log.d(TAG, "stopCapture")
        mCapturerHasStarted = false
        mFrameProducerHandler.removeCallbacks(mFrameProducer)
        return 0
    }

    override fun init() {
        Log.d(TAG, "init")
        mCapturerHasStarted = false
        mCapturerIsPaused = false

        mCaptureSettings = CaptureSettings()
        mCaptureSettings.height = mHeight
        mCaptureSettings.width = mWidth
        mCaptureSettings.format = ARGB
        mCaptureSettings.fps = FPS
        mCaptureSettings.expectedDelay = 0
    }

    override fun getCaptureSettings(): CaptureSettings {
        Log.d(TAG, "getCaptureSettings")
        return mCaptureSettings
    }

    override fun startCapture(): Int {
        Log.d(TAG, "startCapture")
        mCapturerHasStarted = true
        mFrameProducer.run()
        return 0
    }

    override fun destroy() {
        Log.d(TAG, "destroy")
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        mCapturerIsPaused = true
    }

    override fun isCaptureStarted(): Boolean {
        Log.d(TAG, "isCaptureStarted")
        return mCapturerHasStarted
    }

}