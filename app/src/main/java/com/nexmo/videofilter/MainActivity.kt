package com.nexmo.videofilter

import android.Manifest
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.opentok.android.*
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions

class MainActivity : AppCompatActivity(), Session.SessionListener, PublisherKit.PublisherListener {
    companion object {
        const val TAG = "MainActivity"
        const val API_KEY = "46789364"
        const val SESSION_ID = "2_MX40Njc4OTM2NH5-MTYxMjkzNzczNzg5OX5oZkVQdXFpZkVEZ3FqWkh2SUp1M0JNL0N-fg"
        const val TOKEN = "T1==cGFydG5lcl9pZD00Njc4OTM2NCZzaWc9N2IxMTY3Mzk0Y2M2ZDcxMjU0NzFjZDNkNWVjYTllYjk1NjgzZTY4MjpzZXNzaW9uX2lkPTJfTVg0ME5qYzRPVE0yTkg1LU1UWXhNamt6Tnpjek56ZzVPWDVvWmtWUWRYRnBaa1ZFWjNGcVdraDJTVXAxTTBKTkwwTi1mZyZjcmVhdGVfdGltZT0xNjEyOTM3NzM4Jm5vbmNlPTAuODE1MTE0MTU3MjY4NDA4OSZyb2xlPXB1Ymxpc2hlciZleHBpcmVfdGltZT0xNjEzMDI0MTM4JmluaXRpYWxfbGF5b3V0X2NsYXNzX2xpc3Q9"

        const val RC_VIDEO_APP_PERM = 124
    }

    private lateinit var mPublisherViewContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions()
    }

    override fun onDestroy() {
        with(application as FilterApplication) {
            mPublisherViewContainer.removeView(mPublisher?.view)
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    @AfterPermissionGranted(RC_VIDEO_APP_PERM)
    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (EasyPermissions.hasPermissions(this, *perms)) {
            Log.d(TAG, "Permission already given")
            onPermissionReady()
        } else {
            Log.d(TAG, "Requesting Permission")
            EasyPermissions.requestPermissions(
                this,
                "This app needs the following permissions to function",
                RC_VIDEO_APP_PERM,
                *perms
            )
        }
    }

    private fun onPermissionReady() {
        mPublisherViewContainer = findViewById(R.id.publisher_container)

        with(application as FilterApplication) {
            if (mSession == null) {
                // Start New Session
                mSession = Session.Builder(this, API_KEY, SESSION_ID).build()
                mSession?.setSessionListener(this@MainActivity)
                mSession?.connect(TOKEN)
            } else {
                // Reuse Existing Session, update Activity Context
                (mPublisher?.capturer as FilterVideoCapturer).context = this@MainActivity

                // Set View
                mPublisherViewContainer.addView(mPublisher?.view)
                if (mPublisher?.view is GLSurfaceView) {
                    (mPublisher?.view as GLSurfaceView).setZOrderOnTop(true)
                } else {
                    // nothing here, somehow needed for the if statement
                }
            }
        }
    }

    override fun onStreamDropped(p0: Session?, p1: Stream?) {
        Log.d(TAG, "On Stream Dropped")
    }

    override fun onStreamReceived(p0: Session?, p1: Stream?) {
        Log.d(TAG, "On Stream Received")
    }

    override fun onConnected(p0: Session?) {
        Log.d(TAG, "On Connected")

        with(application as FilterApplication) {
            mPublisher = Publisher.Builder(this)
                .capturer(FilterVideoCapturer(this@MainActivity))
                .build()
            mPublisher?.setPublisherListener(this@MainActivity)

            // Set View
            mPublisherViewContainer.addView(mPublisher?.view)
            if (mPublisher?.view is GLSurfaceView) {
                (mPublisher?.view as GLSurfaceView).setZOrderOnTop(true)
            }

            mSession?.publish(mPublisher)
        }
    }

    override fun onDisconnected(p0: Session?) {
        Log.d(TAG, "On Disconnected")
    }

    override fun onError(p0: Session?, p1: OpentokError?) {
        Log.e(TAG, "On Error (session) - ${p1?.message ?: "null message"}")
    }

    override fun onStreamCreated(p0: PublisherKit?, p1: Stream?) {
        Log.d(TAG, "On Stream Created")
    }

    override fun onStreamDestroyed(p0: PublisherKit?, p1: Stream?) {
        Log.d(TAG, "On Stream Destroyed")
    }

    override fun onError(p0: PublisherKit?, p1: OpentokError?) {
        Log.e(TAG, "On Error (publisher) - ${p1?.message ?: "null message"}")
    }
}
