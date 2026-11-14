# MIZAN R8 / ProGuard keep rules for release preparation

# Preserve Kotlinx Serialization @Serializable Navigation Compose routes and Vault DTOs
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.example.core.navigation.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.core.navigation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.example.data.security.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.data.security.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Preserve Room @Database implementation and entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
