# OkHttp (DingTalk Stream WebSocket)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepattributes *Annotation*
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Gson
-keep class org.fossify.commons.models.SimpleContact { *; }
-keep class org.fossify.messages.models.Attachment { *; }
-keep class org.fossify.messages.models.MessageAttachment { *; }

# ez-vcard's parameter registries use reflection to enumerate public constants
# and create runtime values for unknown TYPE/MEDIATYPE parameters.
-keepclassmembers,allowobfuscation class ezvcard.parameter.* extends ezvcard.parameter.VCardParameter {
    public static final <fields>;
    <init>(java.lang.String);
}

-keepclassmembers,allowobfuscation class ezvcard.parameter.* extends ezvcard.parameter.MediaTypeParameter {
    <init>(java.lang.String, java.lang.String, java.lang.String);
}

# The app is an independently signed GPL fork. Strip upstream branding warnings
# and their hard-coded payload from minified production builds.
-assumenosideeffects class org.fossify.commons.extensions.ActivityKt {
    public static final void showModdedAppWarning(org.fossify.commons.activities.BaseSimpleActivity);
    public static final void showSideloadingDialog(android.app.Activity);
}

# Commons also ships a Compose variant of the same check. The Messages UI does
# not use it, but Compose keep rules can otherwise retain its warning payload in
# the release DEX. Treat both entry points as side-effect free so R8 discards the
# unused check and its embedded message.
-assumenosideeffects class org.fossify.commons.compose.extensions.ActivityExtensionsKt {
    public static final void fakeVersionCheck(android.content.Context, kotlin.jvm.functions.Function0);
}

-assumenosideeffects class org.fossify.commons.compose.extensions.ComposeActivityExtensionsKt {
    public static final void FakeVersionCheck(androidx.compose.runtime.Composer, int);
}
