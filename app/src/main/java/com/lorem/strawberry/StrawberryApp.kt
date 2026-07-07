package com.lorem.strawberry

import android.app.Application
import com.lorem.strawberry.di.EngineRegistry
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StrawberryApp : Application() {

    // Eagerly created so engines exist before the first screen needs them
    @Inject
    lateinit var engineRegistry: EngineRegistry
}
