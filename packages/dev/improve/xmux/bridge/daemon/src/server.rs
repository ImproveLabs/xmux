//! The headset listener: TLS over TCP, one connection per headset. A connection starts with
//! `Hello`; after the pairing check it joins the hub and frames flow both ways until either side
//! closes.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use xmux_bridge::hub::Hub;
use xmux_proto::frame::Payload;
use xmux_proto::{
    Bye, Frame, HEADER_LEN, Header, Hello, PROTOCOL_VERSION, Welcome, decode_frame, encode_frame,
};

use crate::pairing::{Decision, Device, Pairing, valid_code};

/// Largest frame a headset may send.
const MAX_BODY: u32 = 4 << 20;

#[derive(Clone)]
pub struct Server {
    pub hub: Hub,
    pub pairing: Arc<Pairing>,
    pub tls: Arc<rustls::ServerConfig>,
    pub name: String,
    pub hello_timeout: Duration,
    pub pairing_timeout: Duration,
    pub client_queue: usize,
}

impl Server {
    pub fn new(hub: Hub, pairing: Arc<Pairing>, tls: Arc<rustls::ServerConfig>) -> Server {
        Server {
            hub,
            pairing,
            tls,
            name: format!("xmux-bridge/{}", env!("CARGO_PKG_VERSION")),
            hello_timeout: Duration::from_secs(10),
            pairing_timeout: Duration::from_secs(300),
            client_queue: 8192,
        }
    }

    /// Accepts headsets until the listener fails.
    pub async fn serve(self, listener: TcpListener) -> Result<()> {
        let acceptor = TlsAcceptor::from(self.tls.clone());
        loop {
            let (tcp, peer) = listener.accept().await?;
            let _ = tcp.set_nodelay(true);
            let server = self.clone();
            let acceptor = acceptor.clone();
            tokio::spawn(async move {
                match acceptor.accept(tcp).await {
                    Ok(tls) => {
                        if let Err(error) = server.connection(tls, peer).await {
                            tracing::info!(%peer, %error, "headset connection ended");
                        }
                    }
                    Err(error) => tracing::info!(%peer, %error, "TLS handshake failed"),
                }
            });
        }
    }

    async fn connection<S: AsyncRead + AsyncWrite + Unpin + Send + 'static>(
        &self,
        stream: S,
        peer: SocketAddr,
    ) -> Result<()> {
        let (mut rd, mut wr) = tokio::io::split(stream);
        let hello = match tokio::time::timeout(self.hello_timeout, read_frame(&mut rd)).await {
            Ok(Ok(Frame {
                payload: Some(Payload::Hello(h)),
            })) => h,
            Ok(Ok(_)) => bail!("the first frame was not Hello"),
            Ok(Err(e)) => return Err(e),
            Err(_) => bail!("no Hello within {:?}", self.hello_timeout),
        };
        if hello.protocol_version != u32::from(PROTOCOL_VERSION) {
            let reason = format!(
                "protocol {} is not supported; this bridge speaks {PROTOCOL_VERSION}",
                hello.protocol_version
            );
            write_frame(&mut wr, &bye(&reason)).await?;
            bail!(reason);
        }
        let device = match self.pairing.verify(&hello.token) {
            Some(device) => {
                write_frame(&mut wr, &self.welcome(true, "")).await?;
                device
            }
            None => self.pair(&mut rd, &mut wr, &hello, peer).await?,
        };
        tracing::info!(%peer, device = %device.name, "headset online");

        let (sender, mut outgoing) = self.hub.join(&device.id, self.client_queue).split();
        let mut writer = tokio::spawn(async move {
            while let Some(wire) = outgoing.recv().await {
                if wr.write_all(&wire).await.is_err() {
                    break;
                }
            }
            let _ = wr.shutdown().await;
        });
        let result = tokio::select! {
            r = async {
                loop {
                    let frame = read_frame(&mut rd).await?;
                    match frame.payload {
                        Some(Payload::Bye(_)) => return Ok::<(), anyhow::Error>(()),
                        Some(_) => sender.send(frame),
                        None => {}
                    }
                }
            } => r,
            // The hub dropped this headset (it fell behind); close so it reconnects and resyncs.
            _ = &mut writer => Ok(()),
        };
        drop(sender);
        writer.abort();
        result
    }

    async fn pair<R, W>(
        &self,
        rd: &mut R,
        wr: &mut W,
        hello: &Hello,
        peer: SocketAddr,
    ) -> Result<Device>
    where
        R: AsyncRead + Unpin,
        W: AsyncWrite + Unpin,
    {
        if !valid_code(&hello.pairing_code) {
            write_frame(
                wr,
                &bye("not paired: connect with a six-digit pairing code"),
            )
            .await?;
            bail!("unpaired headset without a pairing code");
        }
        let (id, mut decision) =
            self.pairing
                .request(&hello.pairing_code, &hello.device_name, &peer.to_string());
        write_frame(wr, &self.welcome(false, "")).await?;
        tracing::info!(%peer, name = %hello.device_name, "pairing requested; run `xmux-bridge pair`");
        let deadline = tokio::time::sleep(self.pairing_timeout);
        tokio::pin!(deadline);
        loop {
            tokio::select! {
                d = &mut decision => match d {
                    Ok(Decision::Approved { device, token }) => {
                        write_frame(wr, &self.welcome(true, &token)).await?;
                        return Ok(device);
                    }
                    Ok(Decision::Rejected) | Err(_) => {
                        write_frame(wr, &bye("pairing rejected")).await?;
                        bail!("pairing rejected");
                    }
                },
                frame = read_frame(rd) => {
                    if frame.is_err() {
                        self.pairing.cancel(id);
                        bail!("headset left before pairing finished");
                    }
                }
                _ = &mut deadline => {
                    self.pairing.cancel(id);
                    write_frame(wr, &bye("pairing timed out")).await?;
                    bail!("pairing timed out");
                }
            }
        }
    }

    fn welcome(&self, paired: bool, token: &str) -> Frame {
        Frame {
            payload: Some(Payload::Welcome(Welcome {
                server: self.name.clone(),
                protocol_version: u32::from(PROTOCOL_VERSION),
                session_name: self.hub.session_name().to_string(),
                paired,
                token: token.to_string(),
            })),
        }
    }
}

fn bye(reason: &str) -> Frame {
    Frame {
        payload: Some(Payload::Bye(Bye {
            reason: reason.to_string(),
        })),
    }
}

pub async fn read_frame<R: AsyncRead + Unpin>(rd: &mut R) -> Result<Frame> {
    let mut header = [0u8; HEADER_LEN];
    rd.read_exact(&mut header)
        .await
        .context("reading a frame header")?;
    let parsed = Header::parse(&header)?;
    if parsed.body_len > MAX_BODY {
        bail!("frame of {} bytes is too large", parsed.body_len);
    }
    let mut buf = Vec::with_capacity(HEADER_LEN + parsed.body_len as usize);
    buf.extend_from_slice(&header);
    buf.resize(HEADER_LEN + parsed.body_len as usize, 0);
    rd.read_exact(&mut buf[HEADER_LEN..])
        .await
        .context("reading a frame body")?;
    Ok(decode_frame(&buf)?.1)
}

pub async fn write_frame<W: AsyncWrite + Unpin>(wr: &mut W, frame: &Frame) -> Result<()> {
    let (id, seq) = xmux_bridge::conformance::frame_route(frame);
    wr.write_all(&encode_frame(frame, id, seq)).await?;
    wr.flush().await?;
    Ok(())
}
