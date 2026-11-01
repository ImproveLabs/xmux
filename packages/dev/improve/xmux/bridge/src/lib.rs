//! Bridge library: mirrors a cmux session (topology and terminal renders) as xmux frames and
//! turns xmux input frames into cmux calls.
//!
//! The state machines (`terminal`, `topology`, `grid`) are pure and run the same on live cmux
//! items (`sdk`) and on recorded traces (`trace`, `replay`).

#![forbid(unsafe_code)]

pub mod backend;
pub mod cells;
pub mod conformance;
pub mod grid;
pub mod hub;
pub mod model;
pub mod protocol;
pub mod raw;
pub mod replay;
pub mod sandbox;
pub mod sdk;
pub mod terminal;
pub mod topology;
pub mod trace;
