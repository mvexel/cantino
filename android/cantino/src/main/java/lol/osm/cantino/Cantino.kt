package lol.osm.cantino

/**
 * Facts about the Cantino build itself.
 *
 * Entry points are elsewhere: [OsmStore] reads an offline area,
 * [AreaManager] downloads and publishes one.
 */
public object Cantino {
    /**
     * Version of Cantino (Kotlin adapter and the bundled Rust core
     * share one version). Generated at build time from the Rust crate's
     * `Cargo.toml`, the same value as the Maven publication's version.
     * Semantic versioning; before 1.0 a minor bump may break the API.
     */
    public const val VERSION: String = GENERATED_VERSION
}
