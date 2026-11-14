package com.example.data.di

import com.example.data.repository.DataStoreUserPreferencesRepository
import com.example.data.repository.DefaultFoundationRepository
import com.example.data.repository.DisabledRemoteDatasetUpdater
import com.example.data.repository.RoomAccountabilityMetadataRepository
import com.example.data.repository.RoomCommitmentRepository
import com.example.data.repository.RoomDailyUsageRepository
import com.example.data.repository.RoomDomainFilterRepository
import com.example.data.repository.RoomRemovalRequestRepository
import com.example.data.repository.RoomSecurityEventRepository
import com.example.data.security.AndroidKeystorePinCredentialCipher
import com.example.data.security.LocalAccountabilityPinRepository
import com.example.data.security.PinCredentialCipher
import com.example.domain.pin.AccountabilityPinRepository
import com.example.domain.pin.Pbkdf2PinHasher
import com.example.domain.pin.PinHasher
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.DomainDatasetSignatureVerifier
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.FoundationRepository
import com.example.domain.repository.NoTrustedKeySignatureVerifier
import com.example.domain.repository.RemovalRequestRepository
import com.example.domain.repository.RemoteDatasetUpdater
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.repository.UserPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

  @Binds
  @Singleton
  abstract fun bindFoundationRepository(impl: DefaultFoundationRepository): FoundationRepository

  @Binds
  @Singleton
  abstract fun bindCommitmentRepository(impl: RoomCommitmentRepository): CommitmentRepository

  @Binds
  @Singleton
  abstract fun bindDomainFilterRepository(
    impl: RoomDomainFilterRepository,
  ): DomainFilterRepository

  @Binds
  @Singleton
  abstract fun bindRemoteDatasetUpdater(
    impl: DisabledRemoteDatasetUpdater,
  ): RemoteDatasetUpdater

  @Binds
  @Singleton
  abstract fun bindDailyUsageRepository(impl: RoomDailyUsageRepository): DailyUsageRepository

  @Binds
  @Singleton
  abstract fun bindSecurityEventRepository(
    impl: RoomSecurityEventRepository,
  ): SecurityEventRepository

  @Binds
  @Singleton
  abstract fun bindAccountabilityMetadataRepository(
    impl: RoomAccountabilityMetadataRepository,
  ): AccountabilityMetadataRepository

  @Binds
  @Singleton
  abstract fun bindRemovalRequestRepository(
    impl: RoomRemovalRequestRepository,
  ): RemovalRequestRepository

  @Binds
  @Singleton
  abstract fun bindPinHasher(impl: Pbkdf2PinHasher): PinHasher

  @Binds
  @Singleton
  abstract fun bindPinCredentialCipher(
    impl: AndroidKeystorePinCredentialCipher,
  ): PinCredentialCipher

  @Binds
  @Singleton
  abstract fun bindAccountabilityPinRepository(
    impl: LocalAccountabilityPinRepository,
  ): AccountabilityPinRepository

  @Binds
  @Singleton
  abstract fun bindUserPreferencesRepository(
    impl: DataStoreUserPreferencesRepository,
  ): UserPreferencesRepository

  companion object {
    @Provides
    @Singleton
    fun provideSignatureVerifier(): DomainDatasetSignatureVerifier = NoTrustedKeySignatureVerifier()
  }
}
