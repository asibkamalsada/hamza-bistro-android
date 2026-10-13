# For :core:shrink — the core through the release build's shrinker, for
# :core:shrunkTest to run. Given to it beside app/proguard-rules.pro and the
# rules the libraries bring, as the release build's is.

# What AGP's proguard-android-optimize.txt says that is not about Android.
-allowaccessmodification
-keepattributes AnnotationDefault,
                EnclosingMethod,
                InnerClasses,
                RuntimeVisibleAnnotations,
                RuntimeVisibleParameterAnnotations,
                RuntimeVisibleTypeAnnotations,
                Signature
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# A release build's stack traces have every class in the package without a
# name ("fh2.a", "ux2.getValue"): the same here.
-repackageclasses

# The way in, as the activity and the service are the release build's: kept
# by name, and all that is reached from it left to the shrinker.
-keep class de.hamzabistro.printstation.core.ShrunkKt {
    public static void main(java.lang.String[]);
}
