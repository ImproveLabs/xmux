//! The control socket (`control.sock` in the state directory, mode 0600): how `xmux-bridge pair`,
//! `status` and `unpair` talk to a running `xmux-bridge serve`. One JSON request per line, one
//! JSON response per line.

use std::io::{BufRead, BufReader, Write};
use std::path::Path;
use std::sync::Arc;

use anyhow::{Context, Result};
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
use tokio::net::UnixListener;
use xmux_bridge::hub::Hub;

use crate::pairing::Pairing;

pub struct ControlInfo {
    pub listen: String,
    pub fingerprint: String,
}

pub async fn serve(
    path: &Path,
    hub: Hub,
    pairing: Arc<Pairing>,
    info: Arc<ControlInfo>,
) -> Result<()> {
    let _ = std::fs::remove_file(path);
    let listener =
        UnixListener::bind(path).with_context(|| format!("binding {}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    }
    loop {
        let (stream, _) = listener.accept().await?;
        let (hub, pairing, info) = (hub.clone(), pairing.clone(), info.clone());
        tokio::spawn(async move {
            let (rd, mut wr) = stream.into_split();
            let mut lines = tokio::io::BufReader::new(rd).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let response = match serde_json::from_str::<Value>(&line) {
                    Ok(request) => handle(&request, &hub, &pairing, &info).await,
                    Err(e) => json!({"ok": false, "error": format!("bad request: {e}")}),
                };
                let mut out = response.to_string();
                out.push('\n');
                if wr.write_all(out.as_bytes()).await.is_err() {
                    break;
                }
            }
        });
    }
}

async fn handle(request: &Value, hub: &Hub, pairing: &Pairing, info: &ControlInfo) -> Value {
    match request.get("cmd").and_then(Value::as_str) {
        Some("status") => {
            let status = hub.status().await;
            json!({
                "ok": true,
                "session": status.session,
                "listen": info.listen,
                "fingerprint": info.fingerprint,
                "headsets": status.clients,
                "attached_terminals": status.attached_terminals,
                "screens": status.screens,
                "devices": pairing.devices().iter().map(|d| json!({"id": d.id, "name": d.name, "paired_at": d.paired_at})).collect::<Vec<_>>(),
                "pending": pairing.pending(),
            })
        }
        Some("approve") => {
            let code = request
                .get("code")
                .and_then(Value::as_str)
                .unwrap_or_default();
            match pairing.approve(code) {
                Ok(device) => json!({"ok": true, "device": {"id": device.id, "name": device.name}}),
                Err(e) => json!({"ok": false, "error": e.to_string()}),
            }
        }
        Some("reject") => json!({"ok": true, "rejected": pairing.reject_all()}),
        Some("revoke") => {
            let device = request
                .get("device")
                .and_then(Value::as_str)
                .unwrap_or_default();
            match pairing.revoke(device) {
                Ok(d) => json!({"ok": true, "device": {"id": d.id, "name": d.name}}),
                Err(e) => json!({"ok": false, "error": e.to_string()}),
            }
        }
        other => json!({"ok": false, "error": format!("unknown command {other:?}")}),
    }
}

/// Sends one request to a running bridge.
pub fn call(path: &Path, request: Value) -> Result<Value> {
    let mut stream = std::os::unix::net::UnixStream::connect(path)
        .with_context(|| format!("no bridge is running (nothing at {})", path.display()))?;
    let mut line = request.to_string();
    line.push('\n');
    stream.write_all(line.as_bytes())?;
    let mut response = String::new();
    BufReader::new(stream).read_line(&mut response)?;
    Ok(serde_json::from_str(&response)?)
}
