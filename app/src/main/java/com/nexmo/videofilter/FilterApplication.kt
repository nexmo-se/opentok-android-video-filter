package com.nexmo.videofilter

import android.app.Application
import com.opentok.android.Publisher
import com.opentok.android.Session

class FilterApplication : Application() {
    var mSession: Session? = null
    var mPublisher: Publisher? = null
}