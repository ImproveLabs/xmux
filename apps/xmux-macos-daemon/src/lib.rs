//! The `xmux-bridge` daemon: serves the hub (`xmux_bridge::hub`) to headsets over TLS, pairs new
//! headsets with a six-digit code typed on the Mac, advertises itself over Bonjour, and answers
//! the CLI on a local control socket.

pub mod control;
pub mod mdns;
pub mod pairing;
pub mod server;
pub mod service;
pub mod state;
pub mod tls;

/// Lowercase hex of `bytes`.
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// `n` random bytes from the OS.
pub fn random_bytes(n: usize) -> Vec<u8> {
    let mut buf = vec![0u8; n];
    getrandom::fill(&mut buf).expect("OS randomness");
    buf
}
