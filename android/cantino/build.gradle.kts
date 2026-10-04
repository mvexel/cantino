// Android library (AAR) wrapping the Rust core through JNI.
// Native libraries are prebuilt by ../scripts/build-android.sh into
// ../target/android/<ABI>/; this module only packages (and strips) them.
plugins {
    id("com.android.library")
    `maven-publish`
    // API reference: `./gradlew :cantino:dokkaGeneratePublicationHtml`
    // → cantino/build/dokka/html (public API only; explicit API mode).
    id("org.jetbrains.dokka")
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
    namespace = "lol.osm.cantino"
    compileSdk = 36
    // Used only to strip the prebuilt .so files; must be the SDK-managed NDK.
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Keeps the exception classes the JNI layer throws by name (and the
        // native bridge) in apps that minify; see the file.
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets {
        getByName("main").jniLibs.directories.add("../../target/android")
        // The instrumented test imports the same fixture as the Rust tests.
        getByName("androidTest").assets.directories.add("../../tests/fixtures")
        // The parity corpus (calls.json, expected.json, *.osm): single source of
        // truth shared with the Rust runner; ParityTest maps repository paths
        // (tests/fixtures/x, tests/parity/x) to these flat asset names.
        getByName("androidTest").assets.directories.add("../../tests/parity")
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
        val file = outputDir.file("lol/osm/cantino/GeneratedVersion.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Generated from Cargo.toml by :cantino:generateVersionSource. Do not edit.\n" +
                "package lol.osm.cantino\n\n" +
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
    // Area downloads (AreaManager). HTTP is HttpURLConnection.
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // Public API: AreaManager.state() returns a Flow; ProtomapsBuilds.latestUrl()
    // and AreaManager.load*() are suspend. Same version work-runtime-ktx 2.12.0 resolves.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JVM unit tests (src/test) for pure-Kotlin logic such as Bbox.around.
    testImplementation("junit:junit:4.13.2")

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
                groupId = "lol.osm"
                artifactId = "cantino"
                version = cantinoVersion
                pom {
                    name.set("Cantino")
                    description.set(
                        "Cantino, an offline OpenStreetMap SDK for Android: downloads OpenStreetMap " +
                            "data and an optional PMTiles basemap for an app-chosen area and queries it offline.",
                    )
                    url.set("https://github.com/mvexel/cantino")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("mvexel")
                            name.set("Martijn van Exel")
                            url.set("https://github.com/mvexel")
                        }
                    }
                    scm {
                        url.set("https://github.com/mvexel/cantino")
                        connection.set("scm:git:https://github.com/mvexel/cantino.git")
                        developerConnection.set("scm:git:ssh://git@github.com/mvexel/cantino.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(rootProject.layout.buildDirectory.dir("repo"))
            }
            // GitHub Pages layout, written by scripts/publish-pages.sh
            // (-PpagesRepo=<dir>/maven); never pushed by the build.
            providers.gradleProperty("pagesRepo").orNull?.let { dir ->
                maven {
                    name = "pages"
                    url = uri(dir)
                }
            }
        }
    }
}

dokka {
    moduleName.set("Cantino")
    moduleVersion.set(cantinoVersion)
    dokkaSourceSets.configureEach {
        // Explicit API mode makes the public surface deliberate; document only it.
        documentedVisibilities.set(setOf(org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Public))
        // Set to true to audit KDoc coverage: as of 0.1.0 the only undocumented
        // public symbols are the equals/hashCode/toString overrides of the
        // value classes, so it stays off to keep the build output clean.
        reportUndocumented.set(false)
        skipEmptyPackages.set(true)
        includes.from("dokka-module.md")
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl("https://github.com/mvexel/cantino/blob/v$cantinoVersion/android/cantino/src/main/java")
            remoteLineSuffix.set("#L")
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
            packageListUrl("https://kotlinlang.org/api/kotlinx.coroutines/package-list")
        }
    }
    pluginsConfiguration.html {
        footerMessage.set("Cantino ${cantinoVersion}. Map data © OpenStreetMap contributors (ODbL).")
    }
}
