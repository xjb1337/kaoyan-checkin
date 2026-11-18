# Keep AppWidget providers, BroadcastReceivers, Room entities/DAOs, and kotlinx.serialization metadata.
-keep class com.xjb.kaoyancheckin.CheckinWidget { *; }
-keep class com.xjb.kaoyancheckin.WidgetActionReceiver { *; }
-keep class com.xjb.kaoyancheckin.WidgetClockReceiver { *; }
-keep class com.xjb.kaoyancheckin.CheckinApplication { *; }
-keep class com.xjb.kaoyancheckin.MainActivity { *; }

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.xjb.kaoyancheckin.**$$serializer { *; }
-keepclassmembers class com.xjb.kaoyancheckin.** { *** Companion; }
-keepclasseswithmembers class com.xjb.kaoyancheckin.** { kotlinx.serialization.KSerializer serializer(...); }

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
