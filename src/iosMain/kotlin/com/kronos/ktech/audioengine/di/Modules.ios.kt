package com.kronos.ktech.audioengine.di

import com.kronos.ktech.audioengine.PlayerEngine
import org.koin.dsl.module

actual val playerEngineModule = module {
    single { PlayerEngine() }
}
