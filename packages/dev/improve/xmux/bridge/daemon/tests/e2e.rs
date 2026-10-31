//! The daemon over real TLS on localhost, serving a replayed trace: pairing with a code, the
//! token on reconnect, refusals, the control socket, and rows that match cmux.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use serde_json::json;
use tokio::io::{ReadHalf, WriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::TlsConnector;
use tokio_rustls::client::TlsStream;
use xmux_bridge::conformance::{ExpectedFile, fixtures_dir};
use xmux_bridge::grid::Grid;
use xmux_bridge::hub::{Hub, HubConfig};
use xmux_bridge::replay::{Pace, ReplayCmux};
use xmux_bridge::trace::Trace;
use xmux_bridged::control::{self, ControlInfo};
use xmux_bridged::pairing::Pairing;
use xmux_bridged::server::{Server, read_frame, write_frame};
use xmux_bridged::state::StateDir;
use xmux_bridged::tls;
use xmux_proto::frame::Payload;
use xmux_proto::{AttachTerminal, Frame, Hello, PROTOCOL_VERSION};

/// Accepts any certificate and remembers its fingerprint, like a headset on first contact.
#[derive(Debug, Default)]
struct Pinning(Mutex<Option<String>>);

impl ServerCertVerifier for Pinning {
    fn verify_server_cert(
        &self,
        cert: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        *self.0.lock().unwrap() = Some(tls::fingerprint(cert));
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        m: &[u8],
        c: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            m,
            c,
            d,
            &tls::provider().signature_verification_algorithms,
        )
    }
    fn verify_tls13_signature(
        &self,
        m: &[u8],
        c: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            m,
            c,
            d,
            &tls::provider().signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        tls::provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

struct Bridge {
    addr: SocketAddr,
    fingerprint: String,
    pairing: Arc<Pairing>,
    control: std::path::PathBuf,
    expected: ExpectedFile,
}

async fn bridge() -> Bridge {
    let dir = std::env::temp_dir().join(format!(
        "xmux-e2e-{}",
        xmux_bridged::hex(&xmux_bridged::random_bytes(6))
    ));
    let state = StateDir::new(&dir).unwrap();
    let identity = tls::load_or_create(&state).unwrap();
    let fixtures = fixtures_dir();
    let trace = Trace::read(&fixtures.join("traces/styles.xmtrace")).unwrap();
    let expected =
        serde_json::from_slice(&std::fs::read(fixtures.join("expected/styles.grid.json")).unwrap())
            .unwrap();
    let hub = Hub::start(
        Arc::new(ReplayCmux::new(trace, Pace::Instant)),
        HubConfig::default(),
    );
    let pairing = Arc::new(Pairing::load(state.devices()));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let mut server = Server::new(hub.clone(), pairing.clone(), identity.config);
    server.pairing_timeout = Duration::from_secs(10);
    tokio::spawn(server.serve(listener));
    let control = state.control_socket();
    let info = Arc::new(ControlInfo {
        listen: addr.to_string(),
        fingerprint: identity.fingerprint.clone(),
    });
    {
        let (path, hub, pairing) = (control.clone(), hub, pairing.clone());
        tokio::spawn(async move { control::serve(&path, hub, pairing, info).await });
    }
    Bridge {
        addr,
        fingerprint: identity.fingerprint,
        pairing,
        control,
        expected,
    }
}

type Conn = (
    ReadHalf<TlsStream<TcpStream>>,
    WriteHalf<TlsStream<TcpStream>>,
);

async fn connect(addr: SocketAddr) -> (Conn, Arc<Pinning>) {
    let pin = Arc::new(Pinning::default());
    let config = rustls::ClientConfig::builder_with_provider(tls::provider())
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(pin.clone())
        .with_no_client_auth();
    let tcp = TcpStream::connect(addr).await.unwrap();
    let stream = TlsConnector::from(Arc::new(config))
        .connect(ServerName::try_from("xmux-bridge").unwrap(), tcp)
        .await
        .unwrap();
    (tokio::io::split(stream), pin)
}

fn hello(version: u32, token: &str, code: &str) -> Frame {
    Frame {
        payload: Some(Payload::Hello(Hello {
            client: "e2e".into(),
            protocol_version: version,
            token: token.into(),
            pairing_code: code.into(),
            device_name: "Test Quest".into(),
        })),
    }
}

async fn read(conn: &mut Conn) -> Frame {
    tokio::time::timeout(Duration::from_secs(10), read_frame(&mut conn.0))
        .await
        .expect("a frame")
        .expect("readable")
}

#[tokio::test(flavor = "multi_thread")]
async fn pairs_with_a_code_then_reconnects_with_the_token_and_streams_rows() {
    let b = bridge().await;
    let (mut conn, pin) = connect(b.addr).await;
    assert_eq!(
        pin.0.lock().unwrap().as_deref(),
        Some(b.fingerprint.as_str()),
        "the headset sees the advertised certificate"
    );
    write_frame(
        &mut conn.1,
        &hello(u32::from(PROTOCOL_VERSION), "", "314159"),
    )
    .await
    .unwrap();
    let Some(Payload::Welcome(w)) = read(&mut conn).await.payload else {
        panic!("no welcome")
    };
    assert!(!w.paired);
    assert!(w.token.is_empty());

    // The user types the code on the Mac.
    let control = b.control.clone();
    let approved = tokio::task::spawn_blocking(move || {
        control::call(&control, json!({"cmd": "approve", "code": "314159"}))
    })
    .await
    .unwrap()
    .unwrap();
    assert_eq!(approved["ok"], true, "{approved}");
    let Some(Payload::Welcome(w)) = read(&mut conn).await.payload else {
        panic!("no second welcome")
    };
    assert!(w.paired);
    let token = w.token.clone();
    assert_eq!(token.len(), 64);
    drop(conn);

    let (mut conn, _) = connect(b.addr).await;
    write_frame(&mut conn.1, &hello(u32::from(PROTOCOL_VERSION), &token, ""))
        .await
        .unwrap();
    let Some(Payload::Welcome(w)) = read(&mut conn).await.payload else {
        panic!("no welcome")
    };
    assert!(w.paired && w.token.is_empty());
    assert_eq!(w.session_name, "replay:styles");

    let want = &b.expected.terminals[0];
    let terminal = loop {
        if let Some(Payload::Topology(t)) = read(&mut conn).await.payload
            && let Some(t) = t.terminals.iter().find(|t| t.cmux_id == want.cmux_id)
        {
            break t.id;
        }
    };
    write_frame(
        &mut conn.1,
        &Frame {
            payload: Some(Payload::AttachTerminal(AttachTerminal { terminal })),
        },
    )
    .await
    .unwrap();
    let mut grid = Grid::new();
    while !(grid.is_synced() && grid.expected() == want.grid) {
        let frame = read(&mut conn).await;
        if matches!(&frame.payload, Some(Payload::Rows(r)) if r.terminal == terminal)
            || matches!(&frame.payload, Some(Payload::Cursor(c)) if c.terminal == terminal)
        {
            grid.apply(&frame);
        }
    }

    let control = b.control.clone();
    let status =
        tokio::task::spawn_blocking(move || control::call(&control, json!({"cmd": "status"})))
            .await
            .unwrap()
            .unwrap();
    assert_eq!(status["devices"][0]["name"], "Test Quest");
    assert_eq!(status["headsets"].as_array().unwrap().len(), 1);
    assert_eq!(status["fingerprint"], b.fingerprint.as_str());
}

#[tokio::test(flavor = "multi_thread")]
async fn refuses_other_protocol_versions_and_unpaired_headsets_without_a_code() {
    let b = bridge().await;
    let (mut conn, _) = connect(b.addr).await;
    write_frame(&mut conn.1, &hello(99, "", "123456"))
        .await
        .unwrap();
    let Some(Payload::Bye(bye)) = read(&mut conn).await.payload else {
        panic!("expected Bye")
    };
    assert!(bye.reason.contains("protocol 99"), "{}", bye.reason);

    let (mut conn, _) = connect(b.addr).await;
    write_frame(
        &mut conn.1,
        &hello(u32::from(PROTOCOL_VERSION), "forged-token", ""),
    )
    .await
    .unwrap();
    let Some(Payload::Bye(bye)) = read(&mut conn).await.payload else {
        panic!("expected Bye")
    };
    assert!(bye.reason.contains("pairing code"), "{}", bye.reason);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_rejected_request_gets_bye_and_a_departed_one_is_forgotten() {
    let b = bridge().await;
    let (mut conn, _) = connect(b.addr).await;
    write_frame(
        &mut conn.1,
        &hello(u32::from(PROTOCOL_VERSION), "", "271828"),
    )
    .await
    .unwrap();
    assert!(matches!(
        read(&mut conn).await.payload,
        Some(Payload::Welcome(_))
    ));
    assert_eq!(b.pairing.reject_all(), 1);
    assert!(matches!(
        read(&mut conn).await.payload,
        Some(Payload::Bye(_))
    ));

    let (mut conn, _) = connect(b.addr).await;
    write_frame(
        &mut conn.1,
        &hello(u32::from(PROTOCOL_VERSION), "", "161803"),
    )
    .await
    .unwrap();
    assert!(matches!(
        read(&mut conn).await.payload,
        Some(Payload::Welcome(_))
    ));
    assert_eq!(b.pairing.pending().len(), 1);
    drop(conn);
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while !b.pairing.pending().is_empty() {
        assert!(
            std::time::Instant::now() < deadline,
            "the departed request stayed pending"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}
