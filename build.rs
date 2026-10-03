use std::{env, path::PathBuf};
fn main() {
    println!("cargo:rerun-if-env-changed=OSMX_LIB_DIR");
    let directory = env::var_os("OSMX_LIB_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap())
                .join("vendor/OSMExpress/build-linux/artifacts")
        });
    if !directory.is_dir() {
        panic!(
            "Build the OSMExpress mobile library first (scripts/build-native.sh), or set OSMX_LIB_DIR to its library directory"
        );
    }
    println!("cargo:rustc-link-search=native={}", directory.display());
    println!("cargo:rustc-link-lib=dylib=osmx-mobile");
    let target = env::var("TARGET").unwrap();
    if target.contains("linux") && !target.contains("android") {
        // Development executables find the built library without shell setup.
        // Phone packaging supplies its native libraries through the app bundle.
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", directory.display());
    }
}
