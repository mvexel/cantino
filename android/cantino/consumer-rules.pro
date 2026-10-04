# R8/ProGuard rules applied to apps that depend on the Cantino AAR.
#
# The Rust JNI layer (src/android.rs) throws the typed exceptions by JNI class
# name ("io/github/mvexel/cantino/CantinoException$InvalidArgument", ...) via
# ThrowNew, which calls the (String) constructor. Nothing in Kotlin references
# those constructors reflectively, so an app's R8 could rename or strip them;
# the JNI lookup would then fail with NoClassDefFoundError instead of the
# intended exception. Keep the names and the (String) constructors.
-keep class io.github.mvexel.cantino.CantinoException { *; }
-keep class io.github.mvexel.cantino.CantinoException$* {
    <init>(java.lang.String);
}
# The native methods are bound by name (Java_io_github_mvexel_cantino_NativeBridge_*).
-keepclasseswithmembernames class io.github.mvexel.cantino.NativeBridge {
    native <methods>;
}
