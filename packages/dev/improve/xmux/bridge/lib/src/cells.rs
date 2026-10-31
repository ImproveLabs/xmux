//! cmux render runs -> wire runs with explicit cell boundaries, and wire runs -> cells.
//!
//! cmux runs carry text plus style; how many cells a grapheme takes is left to the reader. The
//! bridge settles that once: it segments graphemes (UAX #29), measures them (cmux's width hint,
//! else East Asian width) and regroups them into runs whose `width_hint` makes every cell
//! explicit (see `Run` in the schema). Clients then expand runs without any Unicode tables.

use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;
use xmux_proto::{Run, Underline};

use crate::model::{RenderRun, UnderlineStyle, cmux_attr, parse_color};

/// Attribute bits, identical to `dev.xmux.core.Attr` and `Run.attrs` on the wire.
pub mod attr {
    pub const BOLD: u32 = 1;
    pub const ITALIC: u32 = 2;
    pub const UNDERLINE: u32 = 4;
    pub const INVERSE: u32 = 8;
    pub const DIM: u32 = 16;
    pub const STRIKE: u32 = 32;
    pub const BLINK: u32 = 64;
    pub const INVISIBLE: u32 = 128;
    /// Continuation cell of a wide grapheme; never drawn.
    pub const WIDE_SPACER: u32 = 256;
}

/// `width_hint` values on the wire.
pub mod width {
    pub const NARROW_EACH: u32 = 0;
    pub const NARROW_CLUSTER: u32 = 1;
    pub const WIDE_EACH: u32 = 2;
    pub const WIDE_CLUSTER: u32 = 3;
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct Style {
    fg: u32,
    bg: u32,
    attrs: u32,
    underline: i32,
}

/// One grapheme with its style and width (1 or 2), before regrouping.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Grapheme {
    text: String,
    wide: bool,
    style: Style,
}

fn style_of(run: &RenderRun) -> Style {
    let a = run.attrs;
    let mut attrs = 0;
    for (cmux, xmux) in [
        (cmux_attr::BOLD, attr::BOLD),
        (cmux_attr::ITALIC, attr::ITALIC),
        (cmux_attr::STRIKETHROUGH, attr::STRIKE),
        (cmux_attr::INVERSE, attr::INVERSE),
        (cmux_attr::FAINT, attr::DIM),
        (cmux_attr::INVISIBLE, attr::INVISIBLE),
        (cmux_attr::BLINK, attr::BLINK),
    ] {
        if a & cmux != 0 {
            attrs |= xmux;
        }
    }
    let underline = match run.underline {
        None => Underline::None,
        Some(UnderlineStyle::Single) => Underline::Single,
        Some(UnderlineStyle::Double) => Underline::Double,
        Some(UnderlineStyle::Curly) => Underline::Curly,
        Some(UnderlineStyle::Dotted) => Underline::Dotted,
        Some(UnderlineStyle::Dashed) => Underline::Dashed,
    };
    if underline != Underline::None {
        attrs |= attr::UNDERLINE;
    }
    Style {
        fg: run.fg.as_deref().and_then(parse_color).unwrap_or(0),
        bg: run.bg.as_deref().and_then(parse_color).unwrap_or(0),
        attrs,
        underline: underline as i32,
    }
}

/// Segments cmux runs into graphemes, clipped to `cols` cells. Zero-width graphemes (a stray
/// combining mark, a joiner) join the previous grapheme.
fn graphemes(runs: &[RenderRun], cols: usize) -> Vec<Grapheme> {
    let mut out: Vec<Grapheme> = Vec::new();
    let mut used = 0usize;
    'runs: for run in runs {
        let style = style_of(run);
        for g in run.text.graphemes(true) {
            let measured = match run.width_hint {
                Some(2) => 2,
                Some(1) => 1,
                _ => UnicodeWidthStr::width(g),
            };
            if measured == 0 {
                match out.last_mut() {
                    Some(prev) => prev.text.push_str(g),
                    None => {
                        out.push(Grapheme {
                            text: g.to_string(),
                            wide: false,
                            style,
                        });
                        used += 1;
                    }
                }
                continue;
            }
            let wide = measured >= 2;
            let cells = if wide { 2 } else { 1 };
            if used + cells > cols {
                break 'runs;
            }
            out.push(Grapheme {
                text: g.to_string(),
                wide,
                style,
            });
            used += cells;
        }
    }
    out
}

/// Converts one cmux row into wire runs with explicit cells, at most `cols` cells wide.
pub fn wire_runs(runs: &[RenderRun], cols: usize) -> Vec<Run> {
    let mut out: Vec<Run> = Vec::new();
    for g in graphemes(runs, cols) {
        let single = g.text.chars().count() == 1;
        let hint = match (single, g.wide) {
            (true, false) => width::NARROW_EACH,
            (true, true) => width::WIDE_EACH,
            (false, false) => width::NARROW_CLUSTER,
            (false, true) => width::WIDE_CLUSTER,
        };
        let joinable = single
            && out.last().is_some_and(|last| {
                last.width_hint == hint
                    && last.fg == g.style.fg
                    && last.bg == g.style.bg
                    && last.attrs == g.style.attrs
                    && last.underline == g.style.underline
            });
        if joinable {
            out.last_mut().unwrap().text.push_str(&g.text);
        } else {
            out.push(Run {
                text: g.text,
                fg: g.style.fg,
                bg: g.style.bg,
                attrs: g.style.attrs,
                underline: g.style.underline,
                width_hint: hint,
            });
        }
    }
    out
}

/// One cell of a client grid.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct Cell {
    pub text: String,
    pub fg: u32,
    pub bg: u32,
    pub attrs: u32,
}

impl Cell {
    pub fn blank() -> Cell {
        Cell {
            text: " ".to_string(),
            fg: 0,
            bg: 0,
            attrs: 0,
        }
    }
}

/// Expands wire runs into exactly `cols` cells the way a client does: clip overflow, pad with
/// blanks, a spacer cell after every wide cell.
pub fn expand(runs: &[Run], cols: usize) -> Vec<Cell> {
    let mut out: Vec<Cell> = Vec::with_capacity(cols);
    let push = |out: &mut Vec<Cell>, text: String, wide: bool, run: &Run| {
        let cells = if wide { 2 } else { 1 };
        if out.len() + cells > cols {
            return false;
        }
        out.push(Cell {
            text,
            fg: run.fg,
            bg: run.bg,
            attrs: run.attrs,
        });
        if wide {
            out.push(Cell {
                text: String::new(),
                fg: run.fg,
                bg: run.bg,
                attrs: run.attrs | attr::WIDE_SPACER,
            });
        }
        true
    };
    'runs: for run in runs {
        match run.width_hint {
            width::NARROW_CLUSTER | width::WIDE_CLUSTER => {
                if !push(
                    &mut out,
                    run.text.clone(),
                    run.width_hint == width::WIDE_CLUSTER,
                    run,
                ) {
                    break 'runs;
                }
            }
            hint => {
                for ch in run.text.chars() {
                    if !push(&mut out, ch.to_string(), hint == width::WIDE_EACH, run) {
                        break 'runs;
                    }
                }
            }
        }
    }
    while out.len() < cols {
        out.push(Cell::blank());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(text: &str) -> RenderRun {
        RenderRun {
            text: text.into(),
            fg: Some("#00ff00".into()),
            ..Default::default()
        }
    }

    fn texts(cells: &[Cell]) -> Vec<&str> {
        cells.iter().map(|c| c.text.as_str()).collect()
    }

    #[test]
    fn ascii_is_one_narrow_run_and_pads() {
        let runs = wire_runs(&[run("ab")], 4);
        assert_eq!(runs.len(), 1);
        assert_eq!(runs[0].width_hint, width::NARROW_EACH);
        assert_eq!(runs[0].fg, 0xFF00FF00);
        assert_eq!(texts(&expand(&runs, 4)), ["a", "b", " ", " "]);
    }

    #[test]
    fn wide_code_points_group_and_expand_to_lead_plus_spacer() {
        let runs = wire_runs(&[run("a你好b")], 10);
        let hints: Vec<_> = runs
            .iter()
            .map(|r| (r.text.as_str(), r.width_hint))
            .collect();
        assert_eq!(hints, [("a", 0), ("你好", 2), ("b", 0)]);
        let cells = expand(&runs, 6);
        assert_eq!(texts(&cells), ["a", "你", "", "好", "", "b"]);
        assert_ne!(cells[2].attrs & attr::WIDE_SPACER, 0);
    }

    #[test]
    fn multi_code_point_graphemes_become_cluster_runs() {
        // e + combining acute, a family emoji (ZWJ sequence), a flag.
        let runs = wire_runs(&[run("e\u{301}x👨‍👩‍👧🇨🇦")], 10);
        let hints: Vec<_> = runs
            .iter()
            .map(|r| (r.text.as_str(), r.width_hint))
            .collect();
        assert_eq!(hints, [("e\u{301}", 1), ("x", 0), ("👨‍👩‍👧", 3), ("🇨🇦", 3)]);
        assert_eq!(
            texts(&expand(&runs, 7)),
            ["e\u{301}", "x", "👨‍👩‍👧", "", "🇨🇦", "", " "]
        );
    }

    #[test]
    fn a_leading_zero_width_mark_takes_a_cell() {
        let runs = wire_runs(&[run("\u{301}a")], 3);
        assert_eq!(texts(&expand(&runs, 3)), ["\u{301}", "a", " "]);
    }

    #[test]
    fn cmux_width_hint_wins_over_measurement() {
        let mut r = run("ab");
        r.width_hint = Some(2);
        let runs = wire_runs(&[r], 4);
        assert_eq!(runs[0].width_hint, width::WIDE_EACH);
        assert_eq!(texts(&expand(&runs, 4)), ["a", "", "b", ""]);
    }

    #[test]
    fn clips_to_columns_without_splitting_a_wide_cell() {
        let runs = wire_runs(&[run("ab你")], 3);
        assert_eq!(texts(&expand(&runs, 3)), ["a", "b", " "]);
        let runs = wire_runs(&[run("abcdef")], 4);
        assert_eq!(runs[0].text, "abcd");
    }

    #[test]
    fn styles_map_cmux_bits_to_xmux_bits() {
        let r = RenderRun {
            text: "s".into(),
            fg: None,
            bg: Some("#102030".into()),
            attrs: cmux_attr::BOLD
                | cmux_attr::STRIKETHROUGH
                | cmux_attr::FAINT
                | cmux_attr::INVISIBLE
                | cmux_attr::BLINK
                | cmux_attr::INVERSE
                | cmux_attr::ITALIC,
            underline: Some(UnderlineStyle::Curly),
            width_hint: None,
        };
        let w = &wire_runs(&[r], 1)[0];
        assert_eq!(w.fg, 0);
        assert_eq!(w.bg, 0xFF102030);
        assert_eq!(
            w.attrs,
            attr::BOLD
                | attr::STRIKE
                | attr::DIM
                | attr::INVISIBLE
                | attr::BLINK
                | attr::INVERSE
                | attr::ITALIC
                | attr::UNDERLINE
        );
        assert_eq!(w.underline, Underline::Curly as i32);
    }

    #[test]
    fn style_changes_split_runs_and_equal_styles_merge() {
        let mut bold = run("b");
        bold.attrs = cmux_attr::BOLD;
        let runs = wire_runs(&[run("a"), run("a"), bold, run("c")], 10);
        let texts: Vec<_> = runs.iter().map(|r| r.text.as_str()).collect();
        assert_eq!(texts, ["aa", "b", "c"]);
    }
}
