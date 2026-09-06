package com.eevdf.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Scheduler service bindings.
 *
 * The scheduling *algorithms* live in the pure JVM core module
 * (`com.eevdf.capabilities.taskscheduling.*`: `EevdfScheduler`, `CpuShares`, `TimerEngine`,
 * `RtPolicy`) and must stay free of Android/Hilt — that purity is the project's
 * architectural guard rail. They are stateless Kotlin `object`s, so they are not
 * themselves injected.
 *
 * Instead, the data layer exposes thin, injectable, `@Singleton` service classes
 * that delegate 1:1 to those objects, turning global singletons into
 * constructor-injectable dependencies without altering any math:
 *
 *   • [com.eevdf.capabilities.taskscheduling.EevdfSchedulerService]  → EEVDF facade
 *   • [com.eevdf.capabilities.taskscheduling.RtSchedulerService]     → SCHED_FIFO/RT windows
 *   • [com.eevdf.capabilities.taskscheduling.LoadAverageService]     → EWMA load average
 *
 * All three declare `@Inject` constructors, so Hilt binds them with no manual
 * `@Provides`. Consumers (the ViewModel scheduler/interrupt delegates, services)
 * should inject these instead of referencing the `object`s directly. This module
 * is kept as the documented home for that wiring and as the place to add
 * `@Binds` interface bindings if the scheduler services ever gain abstractions.
 */
@Module
@InstallIn(SingletonComponent::class)
object SchedulerModule
