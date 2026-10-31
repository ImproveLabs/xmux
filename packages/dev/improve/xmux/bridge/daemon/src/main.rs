//! `xmux-bridge`: serves a cmux session to xmux headsets.

use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{Context, Result, bail};
use clap::{Args, Parser, Subcommand};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use xmux_bridge::backend::Cmux;
use xmux_bridge::hub::{Hub, HubConfig};
use xmux_bridge::replay::{Pace, ReplayCmux};
use xmux_bridge::sdk::{SdkCmux, socket_for_session};
use xmux_bridge::trace::Trace;
use xmux_bridged::control::{self, ControlInfo};
use xmux_bridged::pairing::Pairing;
use xmux_bridged::server::Server;
use xmux_bridged::state::StateDir;
use xmux_bridged::{mdns, service, tls};

#[derive(Parser)]
#[command(
    name = "xmux-bridge",
    version,
    about = "Serve cmux screens to xmux headsets"
)]
struct Cli {
    /// Directory for the certificate, paired devices and the control socket.
    #[arg(long, global = true)]
    state_dir: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}

#[derive(Args, Clone, Debug)]
struct ServeArgs {
    /// cmux session to serve.
    #[arg(long, default_value = "main")]
    session: String,
    /// Exact cmux socket (overrides --session).
    #[arg(long)]
    socket: Option<PathBuf>,
    /// TCP port for headsets.
    #[arg(long, default_value_t = 7272)]
    port: u16,
    /// Address to listen on.
    #[arg(long, default_value = "0.0.0.0")]
    bind: IpAddr,
    /// Serve a recorded trace instead of a live session (demos and headset testing).
    #[arg(long)]
    replay: Option<PathBuf>,
    /// With --replay, restart each terminal's recording when it ends.
    #[arg(long = "loop", requires = "replay")]
    looping: bool,
    /// Do not advertise over Bonjour.
    #[arg(long)]
    no_mdns: bool,
}

#[derive(Subcommand)]
enum Command {
    /// Run the bridge in the foreground (what the LaunchAgent and `brew services` run).
    Serve(ServeArgs),
    /// Approve a headset that is waiting to pair, by the six-digit code it shows.
    Pair {
        /// The code shown on the headset (asked for when omitted).
        code: Option<String>,
        /// Reject every waiting headset instead.
        #[arg(long)]
        reject: bool,
    },
    /// Print what the running bridge is doing.
    Status {
        #[arg(long)]
        json: bool,
    },
    /// Forget a paired headset (by id or name); it has to pair again.
    Unpair { device: String },
    /// Install or remove the per-user LaunchAgent that runs `serve` at login.
    Service {
        #[arg(value_parser = ["install", "uninstall"])]
        action: String,
        #[command(flatten)]
        serve: ServeArgs,
    },
}

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();
    let cli = Cli::parse();
    let state_path = cli.state_dir.clone().unwrap_or_else(StateDir::default_path);
    match cli.command {
        Command::Serve(args) => {
            let state = StateDir::new(&state_path)?;
            tokio::runtime::Runtime::new()?.block_on(serve(args, state))
        }
        Command::Pair { code, reject } => pair(&StateDir::new(&state_path)?, code, reject),
        Command::Status { json } => status(&StateDir::new(&state_path)?, json),
        Command::Unpair { device } => {
            let r = control::call(
                &StateDir::new(&state_path)?.control_socket(),
                json!({"cmd": "revoke", "device": device}),
            )?;
            ok_or_bail(&r)?;
            println!(
                "Forgot {}.",
                r.pointer("/device/name")
                    .and_then(Value::as_str)
                    .unwrap_or("the headset")
            );
            Ok(())
        }
        Command::Service { action, serve } => {
            if action == "uninstall" {
                service::uninstall()?;
                println!("Removed the LaunchAgent.");
                return Ok(());
            }
            let socket = match serve.socket.clone() {
                Some(s) => s,
                None => socket_for_session(&serve.session)?,
            };
            let mut args = vec![
                "serve".to_string(),
                "--socket".into(),
                socket.display().to_string(),
                "--port".into(),
                serve.port.to_string(),
                "--bind".into(),
                serve.bind.to_string(),
            ];
            if serve.no_mdns {
                args.push("--no-mdns".into());
            }
            if let Some(dir) = &cli.state_dir {
                args.extend(["--state-dir".into(), dir.display().to_string()]);
            }
            let path = service::install(&args)?;
            println!(
                "Installed {} and started it. Logs: ~/Library/Logs/xmux-bridge.log",
                path.display()
            );
            Ok(())
        }
    }
}

async fn serve(args: ServeArgs, state: StateDir) -> Result<()> {
    let identity = tls::load_or_create(&state)?;
    let cmux: Arc<dyn Cmux> = match &args.replay {
        Some(trace) => Arc::new(
            ReplayCmux::new(
                Trace::read(trace).with_context(|| format!("reading {}", trace.display()))?,
                Pace::Recorded,
            )
            .looping(args.looping),
        ),
        None => Arc::new(
            SdkCmux::connect(&args.session, args.socket.as_deref()).with_context(|| {
                format!(
                    "connecting to cmux session {:?} (is cmux running?)",
                    args.session
                )
            })?,
        ),
    };
    let hub = Hub::start(cmux, HubConfig::default());
    let pairing = Arc::new(Pairing::load(state.devices()));
    let listener = TcpListener::bind((args.bind, args.port))
        .await
        .with_context(|| format!("listening on port {}", args.port))?;
    let addr = listener.local_addr()?;
    println!(
        "xmux-bridge {} serving cmux session {:?} on {addr}",
        env!("CARGO_PKG_VERSION"),
        hub.session_name()
    );
    println!("certificate fingerprint {}", identity.fingerprint);
    println!("pair a headset: open XMUX on it, then run `xmux-bridge pair`");
    let _advertisement = if args.no_mdns {
        None
    } else {
        match mdns::advertise(addr.port(), &identity.fingerprint, hub.session_name()) {
            Ok(a) => Some(a),
            Err(error) => {
                tracing::warn!(%error, "Bonjour advertisement failed; headsets can still connect by address");
                None
            }
        }
    };
    let info = Arc::new(ControlInfo {
        listen: addr.to_string(),
        fingerprint: identity.fingerprint.clone(),
    });
    let control_path = state.control_socket();
    let control = {
        let (hub, pairing, path) = (hub.clone(), pairing.clone(), control_path.clone());
        tokio::spawn(async move { control::serve(&path, hub, pairing, info).await })
    };
    let server = Server::new(hub, pairing, identity.config);
    let result = tokio::select! {
        r = server.serve(listener) => r,
        r = control => r.map_err(anyhow::Error::from).and_then(|r| r),
        _ = tokio::signal::ctrl_c() => Ok(()),
    };
    let _ = std::fs::remove_file(&control_path);
    result
}

fn ok_or_bail(response: &Value) -> Result<()> {
    if response.get("ok") == Some(&Value::Bool(true)) {
        Ok(())
    } else {
        bail!(
            "{}",
            response
                .get("error")
                .and_then(Value::as_str)
                .unwrap_or("the bridge refused")
        )
    }
}

fn pair(state: &StateDir, code: Option<String>, reject: bool) -> Result<()> {
    let socket = state.control_socket();
    if reject {
        let r = control::call(&socket, json!({"cmd": "reject"}))?;
        println!(
            "Rejected {} waiting headset(s).",
            r.get("rejected").and_then(Value::as_u64).unwrap_or(0)
        );
        return Ok(());
    }
    let status = control::call(&socket, json!({"cmd": "status"}))?;
    let pending = status
        .get("pending")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    if pending.is_empty() && code.is_none() {
        bail!("no headset is waiting; open XMUX on the headset and choose this Mac first");
    }
    for p in &pending {
        println!(
            "waiting: {} from {}",
            p.get("name").and_then(Value::as_str).unwrap_or("headset"),
            p.get("peer").and_then(Value::as_str).unwrap_or("?")
        );
    }
    let code = match code {
        Some(c) => c,
        None => {
            use std::io::Write;
            print!("Code shown on the headset: ");
            std::io::stdout().flush()?;
            let mut line = String::new();
            std::io::stdin().read_line(&mut line)?;
            line.trim().replace(' ', "")
        }
    };
    let r = control::call(&socket, json!({"cmd": "approve", "code": code}))?;
    ok_or_bail(&r)?;
    println!(
        "Paired {}.",
        r.pointer("/device/name")
            .and_then(Value::as_str)
            .unwrap_or("the headset")
    );
    Ok(())
}

fn status(state: &StateDir, as_json: bool) -> Result<()> {
    let r = control::call(&state.control_socket(), json!({"cmd": "status"}))?;
    if as_json {
        println!("{}", serde_json::to_string_pretty(&r)?);
        return Ok(());
    }
    let s = |p: &str| {
        r.pointer(p)
            .and_then(Value::as_str)
            .unwrap_or("?")
            .to_string()
    };
    println!("session      {}", s("/session"));
    println!("listening    {}", s("/listen"));
    println!("fingerprint  {}", s("/fingerprint"));
    let list = |p: &str| {
        r.pointer(p)
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default()
    };
    println!("headsets     {}", list("/headsets").len());
    println!(
        "terminals    {} attached, {} screens",
        r.pointer("/attached_terminals")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        r.pointer("/screens").and_then(Value::as_u64).unwrap_or(0)
    );
    for d in list("/devices") {
        println!(
            "paired       {} ({})",
            d.get("name").and_then(Value::as_str).unwrap_or("?"),
            d.get("id").and_then(Value::as_str).unwrap_or("?")
        );
    }
    for p in list("/pending") {
        println!(
            "waiting      {} from {}",
            p.get("name").and_then(Value::as_str).unwrap_or("?"),
            p.get("peer").and_then(Value::as_str).unwrap_or("?")
        );
    }
    Ok(())
}
