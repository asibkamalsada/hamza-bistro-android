# OkHttp, coroutines and kotlinx.serialization bring their own rules.

# Debug and verbose logging never ship: stripped from the release build.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
