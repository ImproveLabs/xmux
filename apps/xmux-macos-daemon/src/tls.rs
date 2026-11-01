//! The bridge's TLS identity: a self-signed certificate made on first start and kept in the state
//! directory. Headsets pin its SHA-256 fingerprint (advertised over Bonjour and shown by
//! `xmux-bridge status`) when they pair.

use std::sync::Arc;

use anyhow::{Context, Result};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};

use crate::state::StateDir;

pub struct Identity {
    pub config: Arc<rustls::ServerConfig>,
    /// Lowercase hex SHA-256 of the certificate (DER).
    pub fingerprint: String,
}

pub fn provider() -> Arc<rustls::crypto::CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

pub fn fingerprint(cert_der: &[u8]) -> String {
    crate::hex(&Sha256::digest(cert_der))
}

pub fn load_or_create(state: &StateDir) -> Result<Identity> {
    if !state.cert().exists() || !state.key().exists() {
        let host = hostname::get()
            .ok()
            .and_then(|h| h.into_string().ok())
            .unwrap_or_else(|| "xmux-bridge".into());
        let certified = rcgen::generate_simple_self_signed(vec!["xmux-bridge".to_string(), host])
            .context("generating the bridge certificate")?;
        std::fs::write(state.cert(), certified.cert.der())?;
        std::fs::write(state.key(), certified.signing_key.serialize_der())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(state.key(), std::fs::Permissions::from_mode(0o600))?;
        }
    }
    let cert = std::fs::read(state.cert()).context("reading cert.der")?;
    let key = std::fs::read(state.key()).context("reading key.der")?;
    let config = rustls::ServerConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(cert.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
        )
        .context("loading the bridge certificate")?;
    Ok(Identity {
        config: Arc::new(config),
        fingerprint: fingerprint(&cert),
    })
}
