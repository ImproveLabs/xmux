//! Bonjour advertisement: `_xmux._tcp` with the certificate fingerprint, protocol version and
//! cmux session in the TXT record, so headsets find the bridge on the local network and can
//! check the certificate they are about to pin.

use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceInfo};

pub const SERVICE_TYPE: &str = "_xmux._tcp.local.";

pub struct Advertisement {
    daemon: ServiceDaemon,
    fullname: String,
}

pub fn advertise(port: u16, fingerprint: &str, session: &str) -> Result<Advertisement> {
    let host = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "xmux-bridge".into());
    let short = host.trim_end_matches(".local").to_string();
    let daemon = ServiceDaemon::new()?;
    let properties = [
        ("fp", fingerprint),
        ("v", &xmux_proto::PROTOCOL_VERSION.to_string()),
        ("session", session),
    ];
    let info = ServiceInfo::new(
        SERVICE_TYPE,
        &short,
        &format!("{short}.local."),
        "",
        port,
        &properties[..],
    )?
    .enable_addr_auto();
    let fullname = info.get_fullname().to_string();
    daemon.register(info)?;
    Ok(Advertisement { daemon, fullname })
}

impl Drop for Advertisement {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}
