//! `xmux-bridge service install|uninstall`: a per-user LaunchAgent (the cmux socket is
//! per-user), `~/Library/LaunchAgents/dev.xmux.bridge.plist`, that runs `xmux-bridge serve` at
//! login and restarts it if it exits.

use std::path::PathBuf;
use std::process::Command;

use anyhow::{Context, Result, bail};

pub const LABEL: &str = "dev.xmux.bridge";

fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn plist_path() -> PathBuf {
    home()
        .join("Library/LaunchAgents")
        .join(format!("{LABEL}.plist"))
}

fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

/// The plist for `serve_args` (arguments after the executable).
pub fn plist(exe: &str, serve_args: &[String], log: &str) -> String {
    let args: String = std::iter::once(exe.to_string())
        .chain(serve_args.iter().cloned())
        .map(|a| format!("    <string>{}</string>\n", escape(&a)))
        .collect();
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>{LABEL}</string>
  <key>ProgramArguments</key>
  <array>
{args}  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>{log}</string>
  <key>StandardErrorPath</key>
  <string>{log}</string>
</dict>
</plist>
"#,
        log = escape(log)
    )
}

fn uid() -> Result<String> {
    let out = Command::new("id")
        .arg("-u")
        .output()
        .context("running id -u")?;
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

pub fn install(serve_args: &[String]) -> Result<PathBuf> {
    if !cfg!(target_os = "macos") {
        bail!(
            "service install manages a macOS LaunchAgent; on other systems run `xmux-bridge serve` from your init system"
        );
    }
    let exe = std::env::current_exe()?.display().to_string();
    let log = home()
        .join("Library/Logs/xmux-bridge.log")
        .display()
        .to_string();
    let path = plist_path();
    std::fs::create_dir_all(path.parent().unwrap())?;
    let _ = uninstall_quiet();
    std::fs::write(&path, plist(&exe, serve_args, &log))?;
    let status = Command::new("launchctl")
        .args([
            "bootstrap",
            &format!("gui/{}", uid()?),
            &path.display().to_string(),
        ])
        .status()?;
    if !status.success() {
        bail!("launchctl bootstrap failed ({status})");
    }
    Ok(path)
}

fn uninstall_quiet() -> Result<()> {
    Command::new("launchctl")
        .args(["bootout", &format!("gui/{}/{LABEL}", uid()?)])
        .output()?;
    Ok(())
}

pub fn uninstall() -> Result<()> {
    uninstall_quiet()?;
    let path = plist_path();
    if path.exists() {
        std::fs::remove_file(&path)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn plist_runs_serve_with_escaped_arguments() {
        let p = super::plist(
            "/usr/local/bin/xmux-bridge",
            &["serve".into(), "--socket".into(), "/tmp/a&b.sock".into()],
            "/tmp/log",
        );
        assert!(p.contains("<string>/usr/local/bin/xmux-bridge</string>"));
        assert!(p.contains("<string>serve</string>"));
        assert!(p.contains("<string>/tmp/a&amp;b.sock</string>"));
        assert!(p.contains("<key>KeepAlive</key>"));
    }
}
