// Café reference app (TODO.md phase 6): first-run area download (OSM data via
// SliceOSM + PMTiles basemap extract), then an offline map of cafés with
// outdoor-seating and opening-hours filters and a raw-object inspector.
// Opening-hours interpretation lives here, in the app, never in the core.
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.mvexel.osmframework.cafe"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.mvexel.osmframework.cafe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Same ABIs the framework AAR ships: phones and x86_64 emulators.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets {
        // Style, glyphs and sprites only (no basemap archive: the basemap is
        // downloaded per area at runtime). Copied from the sample app's
        // generated assets by copyBasemapStyleAssets below.
        getByName("main").assets.directories.add("build/generated/basemap-style-assets")
    }
}

// Reuse the offline style/glyphs/sprites that scripts/basemap-assets.sh
// generates for the sample app, without its bundled slc.pmtiles.
val sampleAssets = layout.projectDirectory.dir("../sample-app/build-assets")
val copyBasemapStyleAssets by tasks.registering(Sync::class) {
    from(sampleAssets) {
        include("style.json", "glyphs/**", "sprites/**")
    }
    into(layout.buildDirectory.dir("generated/basemap-style-assets"))
    doFirst {
        check(sampleAssets.file("style.json").asFile.exists()) {
            "missing ${sampleAssets.file("style.json").asFile}; run scripts/basemap-assets.sh first"
        }
    }
}
tasks.named("preBuild") { dependsOn(copyBasemapStyleAssets) }

dependencies {
    implementation(project(":osm-framework"))
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    // AreaManager.state() is a Flow; the framework keeps coroutines as an
    // implementation dependency, so the app declares them itself.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
