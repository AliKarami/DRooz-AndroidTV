# kotlinx.serialization ships consumer rules for @Serializable classes; these cover the app's own models
# (watch state, cookies, persisted home page, recent searches) if a generated serializer is only reached reflectively.
-keepclassmembers @kotlinx.serialization.Serializable class com.alikarami.drooz.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.alikarami.drooz.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Readable crash stack traces (the mapping file stays out of the APK).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Type-safe navigation looks route argument types up by class name (seen: "Cannot find class with name
# ...site.model.TitleKind" at launch). Keep the names of the app's serializable classes and enums.
-keepnames @kotlinx.serialization.Serializable class com.alikarami.drooz.**
-keepnames enum com.alikarami.drooz.** { *; }
