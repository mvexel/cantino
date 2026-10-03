// Android library (AAR) wrapping the Rust core through JNI.
// Native libraries are prebuilt by ../scripts/build-android.sh into
// ../target/android/<ABI>/; this module only packages them.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.mvexel.osmframework"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("main").jniLibs.directories.add("../../target/android")
        // The instrumented test imports the same fixture as the Rust tests.
        getByName("androidTest").assets.directories.add("../../tests/fixtures")
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
