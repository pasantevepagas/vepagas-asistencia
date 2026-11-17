# kotlinx.serialization: conservar serializers de los DTO
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class com.vepagas.asistencia.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vepagas.asistencia.**$$serializer { *; }

# Retrofit (interfaces con anotaciones)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.vepagas.asistencia.datos.ApiAsistencia { *; }

# Credential Manager / Google Identity
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
