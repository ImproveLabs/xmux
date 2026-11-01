//! Generates the Rust types for the wire schema (`packages/dev/improve/xmux/schema/proto/xmux.proto`)
//! at build time with protox (a pure-Rust protobuf compiler) and prost-build. No external
//! `protoc` is needed.

use std::path::{Path, PathBuf};

const SCHEMA_DIR: &str = "packages/dev/improve/xmux/schema/proto";

fn main() {
    let schema_dir = find_schema_dir();
    let schema = schema_dir.join("xmux.proto");
    println!("cargo:rerun-if-changed={}", schema.display());
    let descriptors =
        protox::compile([schema.as_path()], [schema_dir.as_path()]).expect("compile xmux.proto");
    prost_build::Config::new()
        .compile_fds(descriptors)
        .expect("generate Rust code for xmux.proto");
}

/// The schema directory, found by walking up from this crate. Not `XMUX_REPO_ROOT`: that comes
/// from the repository's `.cargo/config.toml`, which `cargo install --git` does not read.
fn find_schema_dir() -> PathBuf {
    let manifest_dir = PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap());
    manifest_dir
        .ancestors()
        .map(|dir| dir.join(SCHEMA_DIR))
        .find(|dir| Path::new(dir).join("xmux.proto").is_file())
        .unwrap_or_else(|| {
            panic!(
                "{SCHEMA_DIR}/xmux.proto not found above {}",
                manifest_dir.display()
            )
        })
}
