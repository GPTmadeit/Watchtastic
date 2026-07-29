# protobuf-javalite relies on reflection over generated fields.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-dontwarn com.google.protobuf.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.watchtastic.**$$serializer { *; }
-keepclassmembers class com.watchtastic.** {
    *** Companion;
}
-keepclasseswithmembers class com.watchtastic.** {
    kotlinx.serialization.KSerializer serializer(...);
}
