//! Paired headsets and pending pairing requests.
//!
//! A headset without a token connects with a six-digit code it shows to the user. The request
//! waits until someone types that code into `xmux-bridge pair` on the Mac, which proves the person
//! at the Mac sees the headset. Approval issues a random token; the bridge stores only its
//! SHA-256, the headset stores the token and sends it on every later connection.

use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::oneshot;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Device {
    /// Short stable id (the first 12 hex digits of the token's hash); keys idempotent input.
    pub id: String,
    pub name: String,
    pub token_sha256: String,
    pub paired_at: u64,
}

#[derive(Debug)]
pub enum Decision {
    Approved { device: Device, token: String },
    Rejected,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct PendingInfo {
    pub name: String,
    pub peer: String,
    pub waiting_s: u64,
}

struct Pending {
    id: u64,
    code: String,
    name: String,
    peer: String,
    since: std::time::Instant,
    reply: Option<oneshot::Sender<Decision>>,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum PairingError {
    #[error("no headset is waiting with code {0}")]
    UnknownCode(String),
    #[error("no paired device matches {0}")]
    UnknownDevice(String),
    #[error("saving devices: {0}")]
    Io(String),
}

pub struct Pairing {
    path: PathBuf,
    inner: Mutex<Inner>,
}

struct Inner {
    devices: Vec<Device>,
    pending: Vec<Pending>,
    next_id: u64,
}

fn sha256_hex(s: &str) -> String {
    crate::hex(&Sha256::digest(s.as_bytes()))
}

fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Six ASCII digits.
pub fn valid_code(code: &str) -> bool {
    code.len() == 6 && code.bytes().all(|b| b.is_ascii_digit())
}

impl Pairing {
    pub fn load(path: PathBuf) -> Pairing {
        let devices = std::fs::read(&path)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Pairing {
            path,
            inner: Mutex::new(Inner {
                devices,
                pending: Vec::new(),
                next_id: 1,
            }),
        }
    }

    pub fn devices(&self) -> Vec<Device> {
        self.inner.lock().unwrap().devices.clone()
    }

    /// The device a token belongs to.
    pub fn verify(&self, token: &str) -> Option<Device> {
        if token.is_empty() {
            return None;
        }
        let hash = sha256_hex(token);
        self.inner
            .lock()
            .unwrap()
            .devices
            .iter()
            .find(|d| d.token_sha256 == hash)
            .cloned()
    }

    /// Queues a request; the receiver resolves when the user approves or rejects it.
    pub fn request(
        &self,
        code: &str,
        name: &str,
        peer: &str,
    ) -> (u64, oneshot::Receiver<Decision>) {
        let (tx, rx) = oneshot::channel();
        let mut inner = self.inner.lock().unwrap();
        let id = inner.next_id;
        inner.next_id += 1;
        // A newer request with the same code replaces the older one (the headset reconnected).
        inner.pending.retain(|p| p.code != code);
        inner.pending.push(Pending {
            id,
            code: code.to_string(),
            name: name.to_string(),
            peer: peer.to_string(),
            since: std::time::Instant::now(),
            reply: Some(tx),
        });
        (id, rx)
    }

    pub fn cancel(&self, id: u64) {
        self.inner.lock().unwrap().pending.retain(|p| p.id != id);
    }

    pub fn pending(&self) -> Vec<PendingInfo> {
        self.inner
            .lock()
            .unwrap()
            .pending
            .iter()
            .map(|p| PendingInfo {
                name: p.name.clone(),
                peer: p.peer.clone(),
                waiting_s: p.since.elapsed().as_secs(),
            })
            .collect()
    }

    /// Approves the waiting headset showing `code`: stores it as a device and hands it a token.
    pub fn approve(&self, code: &str) -> Result<Device, PairingError> {
        let mut inner = self.inner.lock().unwrap();
        let index = inner
            .pending
            .iter()
            .position(|p| p.code == code)
            .ok_or_else(|| PairingError::UnknownCode(code.to_string()))?;
        let mut pending = inner.pending.remove(index);
        let token = crate::hex(&crate::random_bytes(32));
        let token_sha256 = sha256_hex(&token);
        let device = Device {
            id: token_sha256[..12].to_string(),
            name: if pending.name.is_empty() {
                "headset".into()
            } else {
                pending.name.clone()
            },
            token_sha256,
            paired_at: now(),
        };
        inner.devices.push(device.clone());
        save(&self.path, &inner.devices)?;
        if let Some(reply) = pending.reply.take() {
            let _ = reply.send(Decision::Approved {
                device: device.clone(),
                token,
            });
        }
        Ok(device)
    }

    /// Rejects every waiting request.
    pub fn reject_all(&self) -> usize {
        let mut inner = self.inner.lock().unwrap();
        let n = inner.pending.len();
        for mut p in inner.pending.drain(..) {
            if let Some(reply) = p.reply.take() {
                let _ = reply.send(Decision::Rejected);
            }
        }
        n
    }

    /// Forgets a device by id or name; its token stops working.
    pub fn revoke(&self, id_or_name: &str) -> Result<Device, PairingError> {
        let mut inner = self.inner.lock().unwrap();
        let index = inner
            .devices
            .iter()
            .position(|d| d.id == id_or_name || d.name == id_or_name)
            .ok_or_else(|| PairingError::UnknownDevice(id_or_name.to_string()))?;
        let device = inner.devices.remove(index);
        save(&self.path, &inner.devices)?;
        Ok(device)
    }
}

fn save(path: &std::path::Path, devices: &[Device]) -> Result<(), PairingError> {
    let json = serde_json::to_vec_pretty(devices).map_err(|e| PairingError::Io(e.to_string()))?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, json).map_err(|e| PairingError::Io(e.to_string()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
    }
    std::fs::rename(&tmp, path).map_err(|e| PairingError::Io(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pairing() -> (Pairing, PathBuf) {
        let dir = std::env::temp_dir().join(format!(
            "xmux-pairing-{}-{}",
            std::process::id(),
            crate::hex(&crate::random_bytes(4))
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("devices.json");
        (Pairing::load(path.clone()), path)
    }

    #[test]
    fn codes_are_six_digits() {
        assert!(valid_code("012345"));
        assert!(!valid_code("12345"));
        assert!(!valid_code("12345a"));
    }

    #[test]
    fn approving_a_code_issues_a_token_that_verifies_and_survives_a_reload() {
        let (p, path) = pairing();
        let (_, mut rx) = p.request("424242", "Quest 3", "10.0.0.7:5000");
        assert_eq!(p.pending().len(), 1);
        assert_eq!(
            p.approve("111111"),
            Err(PairingError::UnknownCode("111111".into()))
        );
        let device = p.approve("424242").unwrap();
        let Ok(Decision::Approved { token, .. }) = rx.try_recv() else {
            panic!("not approved")
        };
        assert_eq!(p.verify(&token), Some(device.clone()));
        assert_eq!(p.verify("not-a-token"), None);
        assert!(p.pending().is_empty());
        let reloaded = Pairing::load(path);
        assert_eq!(
            reloaded.verify(&token).map(|d| d.id),
            Some(device.id.clone())
        );
        assert!(
            !std::fs::read_to_string(reloaded.path.clone())
                .unwrap()
                .contains(&token),
            "only the hash is stored"
        );
        reloaded.revoke("Quest 3").unwrap();
        assert_eq!(reloaded.verify(&token), None);
    }

    #[test]
    fn rejection_and_cancellation_clear_requests() {
        let (p, _) = pairing();
        let (id, _rx) = p.request("000001", "a", "x");
        p.cancel(id);
        assert!(p.pending().is_empty());
        let (_, mut rx) = p.request("000002", "b", "y");
        assert_eq!(p.reject_all(), 1);
        assert!(matches!(rx.try_recv(), Ok(Decision::Rejected)));
    }
}
