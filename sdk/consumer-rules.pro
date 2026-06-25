# Triggerbee SDK consumer ProGuard rules.
# Applied to every consumer that depends on the AAR.

# kotlinx.serialization keeps reflection on generated $serializer classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.triggerbee.sdk.**$$serializer { *; }
-keepclassmembers class com.triggerbee.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class com.triggerbee.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
