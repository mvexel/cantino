// Android library (AAR) wrapping the Rust core through JNI.
// Native libraries are prebuilt by ../scripts/build-android.sh into
// ../target/android/<ABI>/; this module only packages (and strips) them.
plugins {
    id("com.android.library")
    `maven-publish`
}

// One version for the whole framework: the Rust crate's (../../Cargo.toml).
// The AAR publication and Cantino.VERSION both read it from there, so the
// three cannot drift apart.
val cantinoVersion: String = Regex("""(?m)^version = "([^"]+)"""")
    .find(rootProject.file("../Cargo.toml").readText())
    ?.groupValues?.get(1)
    ?: error("no package version in Cargo.toml")
version = cantinoVersion

// Explicit API mode: every declaration must say whether it is public API, and
// public ones need explicit types. The public surface is a decision, not a default.
kotlin {
    explicitApi()
}

android {
    namespace = "io.github.mvexel.cantino"
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

// Generates `internal const val GENERATED_VERSION` for Cantino.VERSION.
abstract class GenerateVersionSource : DefaultTask() {
    @get:Input abstract val frameworkVersion: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.file("io/github/mvexel/cantino/GeneratedVersion.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Generated from Cargo.toml by :cantino:generateVersionSource. Do not edit.\n" +
                "package io.github.mvexel.cantino\n\n" +
                "internal const val GENERATED_VERSION: String = \"${frameworkVersion.get()}\"\n",
        )
    }
}

val generateVersionSource by tasks.registering(GenerateVersionSource::class) {
    frameworkVersion.set(cantinoVersion)
    outputDir.set(layout.buildDirectory.dir("generated/source/cantinoVersion"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateVersionSource, GenerateVersionSource::outputDir)
    }
}

dependencies {
    // Area downloads (AreaManager). work-runtime-ktx brings kotlinx-coroutines,
    // used for CoroutineWorker and the state Flow. HTTP is HttpURLConnection.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Fake SliceOSM server for AreaManagerTest; test APK only, not in the AAR.
    // 5.5.0 pulls okhttp-android that requires compileSdk 37; stay on 5.4.0.
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}

// `./gradlew :cantino:publishReleasePublicationToLocalRepository` writes
// a Maven layout to android/build/repo for apps to consume.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.mvexel"
                artifactId = "cantino"
                version = cantinoVersion
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
