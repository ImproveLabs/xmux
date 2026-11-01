//! A throwaway cmux server for recordings and integration tests: its own session name, a
//! scratch home directory, `/bin/sh` as the shell and a minimal environment, so nothing from the
//! machine's real cmux sessions or dotfiles leaks in. Stopped and removed on drop.

use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

pub struct SandboxServer {
    pub bin: PathBuf,
    pub session: String,
    pub dir: PathBuf,
    pub socket: PathBuf,
    /// `cmux --version`, without the ghostty part.
    pub version: String,
    child: Child,
}

/// `cmux` on PATH, then `~/.cmux/bin/cmux`, unless `CMUX_BIN` says otherwise.
pub fn find_cmux() -> PathBuf {
    if let Some(bin) = std::env::var_os("CMUX_BIN") {
        return PathBuf::from(bin);
    }
    if let Some(path) = std::env::var_os("PATH")
        .into_iter()
        .flat_map(|p| std::env::split_paths(&p).collect::<Vec<_>>())
        .map(|d| d.join("cmux"))
        .find(|p| p.is_file())
    {
        return path;
    }
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_default();
    home.join(".cmux/bin/cmux")
}

impl SandboxServer {
    /// Starts `cmux server start --session <name>` and waits for its socket.
    pub fn start(bin: &Path, name: &str) -> std::io::Result<SandboxServer> {
        let session = format!("{name}-{}", std::process::id());
        let dir = std::env::temp_dir().join(&session);
        std::fs::create_dir_all(&dir)?;
        let version = String::from_utf8_lossy(&Command::new(bin).arg("--version").output()?.stdout)
            .trim()
            .split(';')
            .next()
            .unwrap_or_default()
            .to_string();
        let child = command(bin, &dir)
            .args(["server", "start", "--session", &session])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()?;
        let mut server = SandboxServer {
            bin: bin.to_path_buf(),
            session,
            dir,
            socket: PathBuf::new(),
            version,
            child,
        };
        let started = Instant::now();
        loop {
            let out = command(bin, &server.dir)
                .args(["server", "status", "--session", &server.session])
                .output()?;
            let text = String::from_utf8_lossy(&out.stdout);
            if text.contains("running")
                && let Some(line) = text.lines().find(|l| l.starts_with("socket"))
            {
                server.socket = PathBuf::from(line.trim_start_matches("socket").trim());
                return Ok(server);
            }
            if started.elapsed() > Duration::from_secs(20) {
                return Err(std::io::Error::other(format!(
                    "cmux server did not start: {text}"
                )));
            }
            std::thread::sleep(Duration::from_millis(200));
        }
    }

    pub fn dir_string(&self) -> String {
        self.dir.display().to_string()
    }
}

impl Drop for SandboxServer {
    fn drop(&mut self) {
        let _ = command(&self.bin, &self.dir)
            .args(["server", "stop", "--session", &self.session, "--force"])
            .output();
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn command(bin: &Path, dir: &Path) -> Command {
    let mut c = Command::new(bin);
    c.env_clear()
        .current_dir(dir)
        .env("PATH", "/usr/bin:/bin:/usr/sbin:/sbin")
        .env("HOME", dir)
        .env("SHELL", "/bin/sh")
        .env("TERM", "xterm-256color")
        .env("LANG", "en_US.UTF-8")
        .env("PS1", "$ ");
    if let Some(tmp) = std::env::var_os("TMPDIR") {
        c.env("TMPDIR", tmp);
    }
    c
}
