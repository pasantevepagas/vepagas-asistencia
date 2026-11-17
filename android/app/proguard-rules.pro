# kotlinx.serialization: conservar serializers de los modelos
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class com.vepagas.asistencia.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vepagas.asistencia.**$$serializer { *; }

# MSAL (usa reflexión/Gson internamente)
-keep class com.microsoft.identity.** { *; }
-dontwarn com.microsoft.identity.**
-dontwarn com.microsoft.device.display.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn com.google.crypto.tink.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
-dontwarn com.nimbusds.**
