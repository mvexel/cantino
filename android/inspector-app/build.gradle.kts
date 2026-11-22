// Cantino Field: an offline accessibility audit app built on the Inspector's
// query, tap and object-navigation mechanics. It keeps findings in an
// app-owned overlay and exports reviewed GeoJSON/CSV; it never edits the
// Cantino area or uploads to OSM. See docs/guide/field-app.md.
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
        versionCode = 2
        versionName = "0.2.0"
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
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
