//! Where the bridge keeps its certificate, paired devices and control socket:
//! `~/Library/Application Support/dev.xmux.bridge` on macOS, `$XDG_CONFIG_HOME/xmux-bridge`
//! (or `~/.config/xmux-bridge`) elsewhere. `XMUX_BRIDGE_STATE` overrides both.

use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct StateDir(PathBuf);

impl StateDir {
    pub fn new(dir: impl Into<PathBuf>) -> std::io::Result<StateDir> {
        let dir = dir.into();
        std::fs::create_dir_all(&dir)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700))?;
        }
        Ok(StateDir(dir))
    }

    pub fn default_path() -> PathBuf {
        if let Some(dir) = std::env::var_os("XMUX_BRIDGE_STATE") {
            return PathBuf::from(dir);
        }
        let home = std::env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."));
        if cfg!(target_os = "macos") {
            home.join("Library/Application Support/dev.xmux.bridge")
        } else {
            std::env::var_os("XDG_CONFIG_HOME")
                .map(PathBuf::from)
                .unwrap_or_else(|| home.join(".config"))
                .join("xmux-bridge")
        }
    }

    pub fn path(&self) -> &Path {
        &self.0
    }

    pub fn cert(&self) -> PathBuf {
        self.0.join("cert.der")
    }

    pub fn key(&self) -> PathBuf {
        self.0.join("key.der")
    }

    pub fn devices(&self) -> PathBuf {
        self.0.join("devices.json")
    }

    /// `control.sock` in the state directory, or a short name in the temp directory when that
    /// path would exceed the Unix socket limit (about 100 bytes).
    pub fn control_socket(&self) -> PathBuf {
        let path = self.0.join("control.sock");
        if path.as_os_str().len() < 100 {
            return path;
        }
        use sha2::{Digest, Sha256};
        let hash = crate::hex(&Sha256::digest(self.0.as_os_str().as_encoded_bytes()));
        std::env::temp_dir().join(format!("xmux-bridge-{}.sock", &hash[..12]))
    }
}
