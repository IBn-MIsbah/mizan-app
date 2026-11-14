package com.example.core.di

import com.example.core.common.DefaultDispatcherProvider
import com.example.core.common.DispatcherProvider
import com.example.core.common.IdGenerator
import com.example.core.common.UuidGenerator
import com.example.core.time.SystemTimeProvider
import com.example.domain.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

  @Binds
  @Singleton
  abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

  @Binds
  @Singleton
  abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

  @Binds
  @Singleton
  abstract fun bindIdGenerator(impl: UuidGenerator): IdGenerator
}
