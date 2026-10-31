//! `xmux-view`: developer tools around traces and frame streams.
//!
//! - `record`: runs scenarios in a fresh, sandboxed cmux session and writes
//!   `testing/fixtures/traces/<name>.xmtrace` plus `expected/<name>.grid.json`.
//! - `replay`: plays a trace through the bridge's state machines and prints the final grids.
//! - `frames`: decodes a `.xmframes` stream and prints one line per frame.

mod record;

use std::path::PathBuf;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use xmux_bridge::conformance;

#[derive(Parser)]
#[command(name = "xmux-view", version, about = "xmux trace and frame tools")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Record scenarios against a sandboxed cmux server (all scenarios when none are named).
    Record {
        /// cmux executable (default: `cmux` on PATH, then ~/.cmux/bin/cmux).
        #[arg(long)]
        cmux: Option<PathBuf>,
        /// Fixtures directory holding `traces/` and `expected/`.
        #[arg(long)]
        fixtures: Option<PathBuf>,
        scenarios: Vec<String>,
    },
    /// Replay a trace and print each terminal's final grid.
    Replay { trace: PathBuf },
    /// Print every frame of a `.xmframes` file.
    Frames { file: PathBuf },
    /// Print the first session-events item of a live session, raw and as the bridge decodes it.
    Probe {
        #[arg(long, default_value = "main")]
        session: String,
    },
}

fn main() -> Result<()> {
    match Cli::parse().command {
        Command::Record {
            cmux,
            fixtures,
            scenarios,
        } => {
            let fixtures = fixtures.unwrap_or_else(conformance::fixtures_dir);
            record::run(cmux, &fixtures, &scenarios)
        }
        Command::Replay { trace } => {
            let trace = xmux_bridge::trace::Trace::read(&trace).context("reading trace")?;
            for (i, terminal) in conformance::replay(&trace).terminals.iter().enumerate() {
                println!("# terminal {} ({})", i + 1, terminal.cmux_id);
                let g = &terminal.grid;
                println!(
                    "# {}x{} cursor {},{} visible={}",
                    g.cols, g.rows, g.cursor.row, g.cursor.col, g.cursor.visible
                );
                for line in &g.lines {
                    println!("|{line}|");
                }
            }
            Ok(())
        }
        Command::Probe { session } => {
            let socket = xmux_bridge::sdk::socket_for_session(&session)?;
            let mut stream =
                xmux_bridge::raw::RawStream::open(&socket, "session.events", Default::default())?;
            if let xmux_bridge::backend::Poll::Item(raw) =
                stream.poll(std::time::Duration::from_secs(5))?
            {
                match xmux_bridge::protocol::decode_session(&raw) {
                    Some(item) => println!("{}", serde_json::to_string_pretty(&item)?),
                    None => {
                        println!("undecodable item:");
                        println!("{}", serde_json::to_string_pretty(&raw)?);
                    }
                }
            }
            Ok(())
        }
        Command::Frames { file } => {
            let bytes = std::fs::read(&file)?;
            for (header, frame) in conformance::decode_stream(&bytes)? {
                let kind = frame
                    .payload
                    .as_ref()
                    .map(|p| format!("{p:?}"))
                    .unwrap_or_default();
                let short: String = kind.chars().take(160).collect();
                println!("id={} seq={} {short}", header.id, header.sequence);
            }
            Ok(())
        }
    }
}
