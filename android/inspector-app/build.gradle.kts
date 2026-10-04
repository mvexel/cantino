// Inspector: the example for OSM developers and mappers. Downloads a full
// import (OSM data via SliceOSM + PMTiles basemap extract), then shows what is
// in the data offline: a tag query bar with validator-style checks,
// tap-anywhere inspection and an object navigator. Structure and plumbing
// follow the café app (android/cafe-app); see docs/guide/inspector-app.md.
plugins {
    id("com.android.application")
}

android {
    namespace = "lol.osm.cantino.inspector"
    compileSdk = 36
    defaultConfig {
        applicationId = "lol.osm.cantino.inspector"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
        // Same ABIs the framework AAR ships: phones and x86_64 emulators.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets {
        // Style, glyphs and sprites only (the basemap is downloaded per area),
        // copied from the sample app's generated assets by copyBasemapStyleAssets.
        getByName("main").assets.directories.add("build/generated/basemap-style-assets")
    }
}

// Reuse the offline style/glyphs/sprites that scripts/basemap-assets.sh
// generates for the sample app, without its bundled slc.pmtiles (as the café app does).
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
    implementation(project(":cantino"))
    implementation("org.maplibre.gl:android-sdk:13.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
