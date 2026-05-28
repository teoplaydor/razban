# libbox is a gomobile-generated JNI binding — Go calls back into these Java
# classes by reflection-free JNI, but R8 must not rename/strip the binding
# surface or the reverse-bound interfaces (PlatformInterface, etc.).
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }

# Our bg classes implement libbox interfaces and are referenced from native.
-keep class com.razban.app.bg.** { *; }

# Kotlin coroutines internals occasionally tripped by aggressive shrinking.
-dontwarn kotlinx.coroutines.**
