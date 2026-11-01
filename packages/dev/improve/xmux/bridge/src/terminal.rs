//! One cmux terminal as the bridge mirrors it: render items in, xmux `Rows`/`Cursor` frames out.
//!
//! Sequencing: every `Rows` frame of a terminal carries `(generation, sequence)`. A snapshot, a
//! reset or a resize starts a new generation whose first frame (sequence 1) holds every row with
//! `full_reset`; later frames hold only rows that changed since the previous frame, with the
//! sequence incremented by one. A client that sees anything else asks for a resync and gets
//! [`TerminalState::snapshot_frames`].

use std::collections::BTreeSet;

use xmux_proto::checksum::rows_checksum;
use xmux_proto::frame::Payload;
use xmux_proto::{Checksum, Cursor, CursorShape, Frame, Row, Rows, Run};

use crate::cells::wire_runs;
use crate::model::{CursorStyle, RenderCursor, RenderItem, RenderRow, Size, parse_color};

#[derive(Debug)]
pub struct TerminalState {
    id: u32,
    cols: u16,
    rows: u16,
    default_fg: u32,
    default_bg: u32,
    lines: Vec<Vec<Run>>,
    cursor: Cursor,
    generation: u32,
    sequence: u32,
    dirty: BTreeSet<u16>,
    reset: bool,
    cursor_dirty: bool,
    ready: bool,
}

impl TerminalState {
    pub fn new(id: u32) -> TerminalState {
        TerminalState {
            id,
            cols: 0,
            rows: 0,
            default_fg: 0,
            default_bg: 0,
            lines: Vec::new(),
            cursor: Cursor {
                terminal: id,
                ..Default::default()
            },
            generation: 0,
            sequence: 0,
            dirty: BTreeSet::new(),
            reset: false,
            cursor_dirty: false,
            ready: false,
        }
    }

    pub fn id(&self) -> u32 {
        self.id
    }

    /// True once a snapshot arrived; before that there is nothing to show.
    pub fn is_ready(&self) -> bool {
        self.ready
    }

    pub fn size(&self) -> Size {
        Size {
            cols: self.cols,
            rows: self.rows,
        }
    }

    pub fn generation(&self) -> u32 {
        self.generation
    }

    pub fn sequence(&self) -> u32 {
        self.sequence
    }

    /// True when [`take_frames`](Self::take_frames) has something to send.
    pub fn has_changes(&self) -> bool {
        self.reset || !self.dirty.is_empty() || self.cursor_dirty
    }

    pub fn apply(&mut self, item: &RenderItem) {
        match item {
            RenderItem::Snapshot {
                size,
                cursor,
                default_fg,
                default_bg,
                rows,
            } => {
                self.set_defaults(Some(default_fg), Some(default_bg));
                self.reset_to(*size);
                self.set_rows(rows);
                self.set_cursor(cursor);
                self.ready = true;
            }
            RenderItem::Patch {
                cursor,
                full_reset,
                size,
                default_fg,
                default_bg,
                rows,
            } => {
                if !self.ready {
                    // cmux opens every attachment with a snapshot; a patch before it has no base.
                    return;
                }
                let defaults_changed = self.set_defaults(default_fg.as_ref(), default_bg.as_ref());
                if let Some(size) = size {
                    self.reset_to(*size);
                } else if *full_reset {
                    let size = self.size();
                    self.reset_to(size);
                } else if defaults_changed {
                    // Same rows, different colors: start a new generation that resends them all.
                    self.reset = true;
                    self.dirty.clear();
                }
                self.set_rows(rows);
                self.set_cursor(cursor);
            }
            RenderItem::Scroll { .. } => {}
        }
    }

    /// Frames for everything that changed since the previous call: `Rows` (if any row changed),
    /// then `Cursor` (if it moved). Empty when nothing changed.
    pub fn take_frames(&mut self) -> Vec<Frame> {
        let mut out = Vec::with_capacity(2);
        if self.reset {
            self.generation = self.generation.wrapping_add(1);
            self.sequence = 1;
            self.reset = false;
            self.dirty.clear();
            out.push(self.full_rows());
        } else if !self.dirty.is_empty() {
            self.sequence = self.sequence.wrapping_add(1);
            let dirty = std::mem::take(&mut self.dirty);
            let rows = dirty.into_iter().map(|r| self.row(r)).collect();
            out.push(self.rows_frame(rows, false));
        }
        if self.cursor_dirty {
            self.cursor_dirty = false;
            out.push(Frame {
                payload: Some(Payload::Cursor(self.cursor)),
            });
        }
        out
    }

    /// The full current state for a client that just attached or asked for a resync, at the
    /// current generation and sequence. Call after [`take_frames`](Self::take_frames) so no
    /// pending change is lost.
    pub fn snapshot_frames(&self) -> Vec<Frame> {
        if !self.ready || self.generation == 0 {
            return Vec::new();
        }
        vec![
            self.full_rows(),
            Frame {
                payload: Some(Payload::Cursor(self.cursor)),
            },
        ]
    }

    pub fn checksum_frame(&self) -> Frame {
        let fnv1a = rows_checksum(
            self.lines
                .iter()
                .enumerate()
                .map(|(i, runs)| (i as u32, runs.as_slice())),
        );
        Frame {
            payload: Some(Payload::Checksum(Checksum {
                terminal: self.id,
                generation: self.generation,
                sequence: self.sequence,
                fnv1a,
            })),
        }
    }

    fn full_rows(&self) -> Frame {
        let rows = (0..self.rows).map(|r| self.row(r)).collect();
        self.rows_frame(rows, true)
    }

    fn rows_frame(&self, dirty: Vec<Row>, full_reset: bool) -> Frame {
        Frame {
            payload: Some(Payload::Rows(Rows {
                terminal: self.id,
                generation: self.generation,
                sequence: self.sequence,
                cols: u32::from(self.cols),
                rows: u32::from(self.rows),
                dirty,
                full_reset,
                default_fg: self.default_fg,
                default_bg: self.default_bg,
            })),
        }
    }

    fn row(&self, index: u16) -> Row {
        Row {
            index: u32::from(index),
            runs: self
                .lines
                .get(usize::from(index))
                .cloned()
                .unwrap_or_default(),
        }
    }

    fn reset_to(&mut self, size: Size) {
        self.cols = size.cols;
        self.rows = size.rows;
        self.lines = vec![Vec::new(); usize::from(size.rows)];
        self.dirty.clear();
        self.reset = true;
    }

    /// Returns true when a default color changed.
    fn set_defaults(&mut self, fg: Option<&String>, bg: Option<&String>) -> bool {
        let mut changed = false;
        if let Some(fg) = fg.and_then(|c| parse_color(c))
            && fg != self.default_fg
        {
            self.default_fg = fg;
            changed = true;
        }
        if let Some(bg) = bg.and_then(|c| parse_color(c))
            && bg != self.default_bg
        {
            self.default_bg = bg;
            changed = true;
        }
        changed && self.ready
    }

    fn set_rows(&mut self, rows: &[RenderRow]) {
        for row in rows {
            let Some(line) = self.lines.get_mut(usize::from(row.row)) else {
                continue;
            };
            *line = wire_runs(&row.runs, usize::from(self.cols));
            if !self.reset {
                self.dirty.insert(row.row);
            }
        }
    }

    fn set_cursor(&mut self, cursor: &RenderCursor) {
        let next = Cursor {
            terminal: self.id,
            row: u32::from(cursor.y),
            col: u32::from(cursor.x),
            visible: cursor.visible,
            shape: match cursor.style {
                CursorStyle::Block => CursorShape::Block,
                CursorStyle::Underline => CursorShape::Underline,
                CursorStyle::Bar => CursorShape::Bar,
            } as i32,
            blink: cursor.blink,
        };
        if next != self.cursor {
            self.cursor = next;
            self.cursor_dirty = true;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grid::{Applied, Grid};
    use crate::model::{RenderRun, cmux_attr};

    fn row(index: u16, text: &str) -> RenderRow {
        RenderRow {
            row: index,
            runs: vec![RenderRun {
                text: text.into(),
                ..Default::default()
            }],
        }
    }

    fn cursor(x: u16, y: u16) -> RenderCursor {
        RenderCursor {
            x,
            y,
            visible: true,
            ..Default::default()
        }
    }

    fn snapshot(cols: u16, lines: &[&str]) -> RenderItem {
        RenderItem::Snapshot {
            size: Size {
                cols,
                rows: lines.len() as u16,
            },
            cursor: cursor(0, 0),
            default_fg: "#d4d4d4".into(),
            default_bg: "#1e1e1e".into(),
            rows: lines
                .iter()
                .enumerate()
                .map(|(i, t)| row(i as u16, t))
                .collect(),
        }
    }

    fn patch(rows: Vec<RenderRow>, at: RenderCursor) -> RenderItem {
        RenderItem::Patch {
            cursor: at,
            full_reset: false,
            size: None,
            default_fg: None,
            default_bg: None,
            rows,
        }
    }

    fn rows_of(frames: &[Frame]) -> &Rows {
        frames
            .iter()
            .find_map(|f| match &f.payload {
                Some(Payload::Rows(r)) => Some(r),
                _ => None,
            })
            .expect("a Rows frame")
    }

    fn feed(grid: &mut Grid, frames: &[Frame]) {
        for f in frames {
            assert_ne!(grid.apply(f), Applied::NeedsSnapshot);
        }
    }

    #[test]
    fn snapshot_starts_generation_one_with_every_row() {
        let mut t = TerminalState::new(7);
        t.apply(&snapshot(4, &["ab", "cd", ""]));
        let frames = t.take_frames();
        let rows = rows_of(&frames);
        assert!(rows.full_reset);
        assert_eq!((rows.terminal, rows.generation, rows.sequence), (7, 1, 1));
        assert_eq!((rows.cols, rows.rows), (4, 3));
        assert_eq!(rows.dirty.len(), 3);
        assert_eq!((rows.default_fg, rows.default_bg), (0xFFD4D4D4, 0xFF1E1E1E));
        assert!(
            frames
                .iter()
                .any(|f| matches!(f.payload, Some(Payload::Cursor(_))))
        );
        assert!(t.take_frames().is_empty());
    }

    #[test]
    fn patches_send_only_changed_rows_and_coalesce_repeats() {
        let mut t = TerminalState::new(1);
        t.apply(&snapshot(4, &["aa", "bb", "cc"]));
        t.take_frames();
        t.apply(&patch(vec![row(1, "x")], cursor(1, 1)));
        t.apply(&patch(vec![row(1, "y"), row(2, "z")], cursor(1, 2)));
        let frames = t.take_frames();
        let rows = rows_of(&frames);
        assert!(!rows.full_reset);
        assert_eq!((rows.generation, rows.sequence), (1, 2));
        let dirty: Vec<_> = rows
            .dirty
            .iter()
            .map(|r| (r.index, r.runs[0].text.as_str()))
            .collect();
        assert_eq!(dirty, [(1, "y"), (2, "z")]);
    }

    #[test]
    fn full_reset_and_resize_start_new_generations() {
        let mut t = TerminalState::new(1);
        t.apply(&snapshot(4, &["aa", "bb"]));
        t.take_frames();
        t.apply(&RenderItem::Patch {
            cursor: cursor(0, 0),
            full_reset: true,
            size: None,
            default_fg: None,
            default_bg: None,
            rows: vec![row(0, "n")],
        });
        let frames = t.take_frames();
        let rows = rows_of(&frames);
        assert!(rows.full_reset);
        assert_eq!((rows.generation, rows.sequence), (2, 1));
        assert!(
            rows.dirty[1].runs.is_empty(),
            "rows a reset does not list are blank"
        );

        t.apply(&RenderItem::Patch {
            cursor: cursor(0, 0),
            full_reset: true,
            size: Some(Size { cols: 6, rows: 1 }),
            default_fg: None,
            default_bg: None,
            rows: vec![row(0, "wider!")],
        });
        let rows = rows_of(&t.take_frames()).clone();
        assert_eq!((rows.generation, rows.cols, rows.rows), (3, 6, 1));
    }

    #[test]
    fn default_color_change_resends_rows_in_a_new_generation() {
        let mut t = TerminalState::new(1);
        t.apply(&snapshot(4, &["aa"]));
        t.take_frames();
        t.apply(&RenderItem::Patch {
            cursor: cursor(0, 0),
            full_reset: false,
            size: None,
            default_fg: Some("#ffffff".into()),
            default_bg: None,
            rows: vec![],
        });
        let rows = rows_of(&t.take_frames()).clone();
        assert!(rows.full_reset);
        assert_eq!(rows.default_fg, 0xFFFFFFFF);
        assert_eq!(rows.dirty[0].runs[0].text, "aa");
    }

    #[test]
    fn patches_before_a_snapshot_are_ignored() {
        let mut t = TerminalState::new(1);
        t.apply(&patch(vec![row(0, "x")], cursor(0, 0)));
        assert!(!t.is_ready());
        assert!(t.take_frames().is_empty());
        assert!(t.snapshot_frames().is_empty());
    }

    #[test]
    fn a_late_subscriber_syncs_from_snapshot_frames_and_follows_deltas() {
        let mut t = TerminalState::new(1);
        let mut early = Grid::new();
        t.apply(&snapshot(5, &["hello", ""]));
        feed(&mut early, &t.take_frames());
        t.apply(&patch(vec![row(1, "world")], cursor(5, 1)));
        feed(&mut early, &t.take_frames());

        let mut late = Grid::new();
        feed(&mut late, &t.snapshot_frames());
        t.apply(&patch(vec![row(0, "HELLO")], cursor(5, 0)));
        let frames = t.take_frames();
        feed(&mut early, &frames);
        feed(&mut late, &frames);

        for g in [&early, &late] {
            assert_eq!(g.row_text(0), "HELLO");
            assert_eq!(g.row_text(1), "world");
            assert_eq!((g.cursor_row, g.cursor_col), (0, 5));
            assert_eq!(g.checksum(), {
                let Some(Payload::Checksum(c)) = t.checksum_frame().payload else {
                    unreachable!()
                };
                c.fnv1a
            });
        }
    }

    #[test]
    fn a_gap_asks_for_a_snapshot_and_a_snapshot_recovers() {
        let mut t = TerminalState::new(1);
        let mut g = Grid::new();
        t.apply(&snapshot(3, &["a"]));
        feed(&mut g, &t.take_frames());
        t.apply(&patch(vec![row(0, "b")], cursor(0, 0)));
        let _lost = t.take_frames();
        t.apply(&patch(vec![row(0, "c")], cursor(0, 0)));
        let next = t.take_frames();
        assert_eq!(g.apply(&next[0]), Applied::NeedsSnapshot);
        assert!(!g.is_synced());
        feed(&mut g, &t.snapshot_frames());
        assert_eq!(g.row_text(0), "c  ");
    }

    #[test]
    fn checksum_mismatch_is_detected() {
        let mut t = TerminalState::new(1);
        let mut g = Grid::new();
        t.apply(&snapshot(3, &["abc"]));
        feed(&mut g, &t.take_frames());
        assert_eq!(g.apply(&t.checksum_frame()), Applied::Ok);
        let mut other = TerminalState::new(1);
        other.apply(&snapshot(3, &["abd"]));
        other.take_frames();
        assert_eq!(g.apply(&other.checksum_frame()), Applied::NeedsSnapshot);
    }

    #[test]
    fn expected_grid_lists_text_and_style_spans() {
        let mut t = TerminalState::new(1);
        let mut g = Grid::new();
        t.apply(&RenderItem::Snapshot {
            size: Size { cols: 6, rows: 1 },
            cursor: cursor(2, 0),
            default_fg: "#d4d4d4".into(),
            default_bg: "#1e1e1e".into(),
            rows: vec![RenderRow {
                row: 0,
                runs: vec![
                    RenderRun {
                        text: "ok".into(),
                        ..Default::default()
                    },
                    RenderRun {
                        text: "你".into(),
                        fg: Some("#ff0000".into()),
                        attrs: cmux_attr::BOLD,
                        ..Default::default()
                    },
                ],
            }],
        });
        feed(&mut g, &t.take_frames());
        let e = g.expected();
        assert_eq!(e.lines, ["ok你  "]);
        assert_eq!(
            e.styles,
            [[0, 2, 4, 0xFFFF0000, 0, crate::cells::attr::BOLD]]
        );
        assert_eq!((e.cursor.row, e.cursor.col, e.cursor.visible), (0, 2, true));
    }
}
