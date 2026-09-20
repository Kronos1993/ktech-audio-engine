package com.kronos.ktech.audioengine.di

import org.koin.core.module.Module

/**
 * The library's only public DI entry point. **Requires Koin** — this module registers a
 * platform-specific singleton [com.kronos.ktech.audioengine.PlayerEngine] instance; there is no
 * DI-framework-agnostic factory alternative. See this module's README for the exact Koin setup
 * required per platform (Android needs `androidContext()`, via `koin-android`).
 */
expect val playerEngineModule: Module
