import { NormalJob, Step, Workflow } from "@github-actions-workflow-ts/lib";
import { bootstrapSteps } from "@-/dev.improve.xmux.configs.github.workflows._steps";

/** `v0.2.0` -> `0.2.0` in shell steps. */
const VERSION = "${GITHUB_REF_NAME#v}";

// Keep the release a draft until every binary and all five libraries have uploaded.
const prepare = new NormalJob("prepare", {
  "runs-on": "ubuntu-latest",
  permissions: { contents: "write" },
}).addSteps([
  new Step({ name: "Checkout", uses: "actions/checkout@v5" }),
  new Step({
    name: "Create the draft preview release",
    env: { GH_TOKEN: "${{ github.token }}" },
    run: [
      'if ! gh release view "$GITHUB_REF_NAME" >/dev/null 2>&1; then',
      '  gh release create "$GITHUB_REF_NAME" --verify-tag --draft --prerelease --title "XMUX $GITHUB_REF_NAME" --notes-file packages/dev/improve/xmux/packaging/release-notes.md',
      "fi",
    ].join("\n"),
  }),
]);

const bridge = new NormalJob("bridge", {
  "runs-on": "macos-latest",
  needs: ["prepare"],
  permissions: { contents: "write" },
}).addSteps([
  ...bootstrapSteps,
  new Step({
    name: "Install Rust toolchain components",
    run: "rustup component add cargo rustc rust-std rustfmt clippy",
  }),
  new Step({
    name: "Check the tag matches the Cargo version",
    run: [
      'cargo_version="$(cargo metadata --no-deps --format-version 1 | jq -r \'.packages[] | select(.name == "xmux-bridged") | .version\')"',
      `test "$cargo_version" = "${VERSION}" || { echo "tag $GITHUB_REF_NAME, Cargo $cargo_version"; exit 1; }`,
    ].join("\n"),
  }),
  new Step({
    name: "Build a universal macOS binary",
    run: [
      "rustup target add aarch64-apple-darwin x86_64-apple-darwin",
      "cargo build --release --locked -p xmux-bridged --target aarch64-apple-darwin",
      "cargo build --release --locked -p xmux-bridged --target x86_64-apple-darwin",
      "mkdir -p dist",
      "lipo -create -output dist/xmux-bridge target/aarch64-apple-darwin/release/xmux-bridge target/x86_64-apple-darwin/release/xmux-bridge",
      "tar -C dist -czf dist/xmux-bridge-macos-universal.tar.gz xmux-bridge",
      "rm dist/xmux-bridge",
      "(cd dist && shasum -a 256 xmux-bridge-macos-universal.tar.gz > xmux-bridge-macos-universal.tar.gz.sha256)",
    ].join("\n"),
  }),
  new Step({
    name: "Render the Homebrew formula",
    run: [
      'sha="$(cut -d " " -f 1 dist/xmux-bridge-macos-universal.tar.gz.sha256)"',
      `sed -e "s/@VERSION@/${VERSION}/" -e "s/@SHA256@/$sha/" packages/dev/improve/xmux/packaging/homebrew/xmux-bridge.rb.in > dist/xmux-bridge.rb`,
    ].join("\n"),
  }),
  new Step({
    name: "Attach to the release",
    uses: "softprops/action-gh-release@v2",
    with: { files: "dist/*", draft: true, prerelease: true },
  }),
]);

const android = new NormalJob("android", {
  "runs-on": "ubuntu-latest",
  needs: ["prepare"],
  permissions: { contents: "write", packages: "write" },
}).addSteps([
  ...bootstrapSteps,
  new Step({ name: "Cache Gradle", uses: "gradle/actions/setup-gradle@v4" }),
  new Step({
    name: "Build the store APK",
    env: {
      XMUX_KEYSTORE_BASE64: "${{ secrets.XMUX_KEYSTORE_BASE64 }}",
      XMUX_KEYSTORE_PASSWORD: "${{ secrets.XMUX_KEYSTORE_PASSWORD }}",
      XMUX_KEY_ALIAS: "${{ secrets.XMUX_KEY_ALIAS }}",
      XMUX_KEY_PASSWORD: "${{ secrets.XMUX_KEY_PASSWORD }}",
    },
    run: [
      "# Without the upload keystore secret the APK is signed with the debug key (fine for testers).",
      'if [ -n "$XMUX_KEYSTORE_BASE64" ]; then',
      '  echo "$XMUX_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/upload.jks"',
      '  export XMUX_KEYSTORE="$RUNNER_TEMP/upload.jks"',
      "fi",
      `vpx _gradle :app:assembleRelease -PVERSION_NAME=${VERSION} -PVERSION_CODE=\${{ github.run_number }}`,
      "mkdir -p dist",
      `cp packages/dev/improve/xmux/android/app/build/outputs/apk/release/app-release.apk "dist/xmux-quest-${VERSION}.apk"`,
      `(cd dist && sha256sum "xmux-quest-${VERSION}.apk" > "xmux-quest-${VERSION}.apk.sha256")`,
    ].join("\n"),
  }),
  new Step({
    name: "Publish the libraries to GitHub Packages",
    env: {
      GITHUB_TOKEN: "${{ github.token }}",
    },
    run: `vpx _gradle publishAllPublicationsToGitHubPackagesRepository -PVERSION_NAME=${VERSION}`,
  }),
  new Step({
    name: "Attach to the release",
    uses: "softprops/action-gh-release@v2",
    with: { files: "dist/*", draft: true, prerelease: true },
  }),
]);

const publish = new NormalJob("publish", {
  "runs-on": "ubuntu-latest",
  needs: ["bridge", "android"],
  permissions: { contents: "write" },
}).addStep(
  new Step({
    name: "Publish the completed preview release",
    env: { GH_TOKEN: "${{ github.token }}", GH_REPO: "${{ github.repository }}" },
    run: 'gh release edit "$GITHUB_REF_NAME" --draft=false',
  }),
);

/** On a `v*` tag: macOS bridge and Quest APK downloads, plus libraries in GitHub Packages. */
export const releaseWorkflow = new Workflow("release", {
  name: "release",
  on: { push: { tags: ["v*"] } },
}).addJobs([prepare, bridge, android, publish]);
