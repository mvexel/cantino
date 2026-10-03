// Android library (AAR) wrapping the Rust core through JNI.
// Native libraries are prebuilt by ../scripts/build-android.sh into
// ../target/android/<ABI>/; this module only packages (and strips) them.
plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "io.github.mvexel.osmframework"
    compileSdk = 36
    // Used only to strip the prebuilt .so files; must be the SDK-managed NDK.
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("main").jniLibs.directories.add("../../target/android")
        // The instrumented test imports the same fixture as the Rust tests.
        getByName("androidTest").assets.directories.add("../../tests/fixtures")
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}

// `./gradlew :osm-framework:publishReleasePublicationToLocalRepository` writes
// a Maven layout to android/build/repo for apps to consume.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.mvexel"
                artifactId = "osm-framework"
                version = "0.1.0"
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(rootProject.layout.buildDirectory.dir("repo"))
            }
        }
    }
}
