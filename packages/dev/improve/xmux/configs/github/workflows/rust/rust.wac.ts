import { NormalJob, Step, Workflow } from "@github-actions-workflow-ts/lib";
import {
  bootstrapSteps,
  onChanges,
  type Paths,
  toolingPaths,
  vpRun,
} from "@-/dev.improve.xmux.configs.github.workflows._steps";

const paths: Paths = [
  "packages/dev/improve/xmux/bridge/**",
  "Cargo.toml",
  "Cargo.lock",
  "rust-toolchain.toml",
  ".cargo/**",
  "packages/dev/improve/xmux/schema/**",
  "packages/dev/improve/xmux/testing/fixtures/frames/**",
  ...toolingPaths,
  ".github/workflows/rust.yml",
];

/** rustup installs the toolchain and components named in rust-toolchain.toml. */
const rustupToolchainStep = new Step({
  name: "Install Rust toolchain components",
  run: "rustup component add cargo rustc rust-std rustfmt clippy",
});

const rustCacheStep = new Step({
  name: "Cache cargo",
  uses: "Swatinem/rust-cache@v2",
});

// The bridge targets macOS (cmux-tui daemon, Bonjour); macos-latest is arm64.
const test = new NormalJob("test", { "runs-on": "macos-latest" }).addSteps([
  ...bootstrapSteps,
  rustupToolchainStep,
  rustCacheStep,
  vpRun("bridge:check"),
  vpRun("bridge:test"),
]);

// The protocol crate is pure Rust and must stay portable.
const checkLinux = new NormalJob("check-linux", { "runs-on": "ubuntu-latest" }).addSteps([
  ...bootstrapSteps,
  rustupToolchainStep,
  rustCacheStep,
  new Step({
    name: "cargo check -p xmux-proto",
    run: "cargo check -p xmux-proto",
  }),
]);

export const rustWorkflow = new Workflow("rust", {
  name: "rust",
  on: onChanges(paths),
}).addJobs([test, checkLinux]);
