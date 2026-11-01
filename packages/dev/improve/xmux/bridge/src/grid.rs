//! The client side of `Rows`/`Cursor` frames in Rust: what a headset's grid looks like after a
//! frame stream. The conformance tests and `xmux` use it; the Kotlin `RowApplier` follows
//! the same rules and is checked against the same expected grids.

use serde::{Deserialize, Serialize};
use xmux_proto::checksum::rows_checksum;
use xmux_proto::frame::Payload;
use xmux_proto::{Frame, Rows, Run};

use crate::cells::{Cell, attr, expand};

#[derive(Clone, Debug, Default)]
pub struct Grid {
    pub cols: u16,
    pub rows: u16,
    pub default_fg: u32,
    pub default_bg: u32,
    pub cursor_row: u16,
    pub cursor_col: u16,
    pub cursor_visible: bool,
    generation: u32,
    sequence: u32,
    synced: bool,
    lines: Vec<Vec<Run>>,
    cells: Vec<Vec<Cell>>,
}

/// What applying a frame did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Applied {
    Ok,
    /// Out of order or from an old generation: dropped; the client must ask for a resync.
    NeedsSnapshot,
    /// Not a frame for grids.
    Ignored,
}

impl Grid {
    pub fn new() -> Grid {
        Grid::default()
    }

    pub fn is_synced(&self) -> bool {
        self.synced
    }

    pub fn apply(&mut self, frame: &Frame) -> Applied {
        match &frame.payload {
            Some(Payload::Rows(rows)) => self.apply_rows(rows),
            Some(Payload::Cursor(c)) => {
                self.cursor_row = c.row as u16;
                self.cursor_col = c.col as u16;
                self.cursor_visible = c.visible;
                Applied::Ok
            }
            Some(Payload::Checksum(c)) => {
                if !self.synced || c.generation != self.generation || c.sequence != self.sequence {
                    Applied::Ignored
                } else if c.fnv1a == self.checksum() {
                    Applied::Ok
                } else {
                    self.synced = false;
                    Applied::NeedsSnapshot
                }
            }
            _ => Applied::Ignored,
        }
    }

    pub fn apply_rows(&mut self, rows: &Rows) -> Applied {
        if rows.full_reset {
            self.cols = rows.cols as u16;
            self.rows = rows.rows as u16;
            self.lines = vec![Vec::new(); rows.rows as usize];
            self.cells = vec![expand(&[], rows.cols as usize); rows.rows as usize];
            self.generation = rows.generation;
            self.synced = true;
        } else if !self.synced
            || rows.generation != self.generation
            || rows.sequence != self.sequence.wrapping_add(1)
        {
            self.synced = false;
            return Applied::NeedsSnapshot;
        }
        self.sequence = rows.sequence;
        self.default_fg = rows.default_fg;
        self.default_bg = rows.default_bg;
        for row in &rows.dirty {
            let i = row.index as usize;
            if i < self.lines.len() {
                self.cells[i] = expand(&row.runs, usize::from(self.cols));
                self.lines[i] = row.runs.clone();
            }
        }
        Applied::Ok
    }

    pub fn checksum(&self) -> u64 {
        rows_checksum(
            self.lines
                .iter()
                .enumerate()
                .map(|(i, r)| (i as u32, r.as_slice())),
        )
    }

    pub fn cell(&self, row: u16, col: u16) -> Option<&Cell> {
        self.cells.get(usize::from(row))?.get(usize::from(col))
    }

    pub fn row_text(&self, row: u16) -> String {
        self.cells
            .get(usize::from(row))
            .map(|cells| cells.iter().map(|c| c.text.as_str()).collect())
            .unwrap_or_default()
    }

    /// The grid in the cross-language expected-grid format.
    pub fn expected(&self) -> ExpectedGrid {
        let mut styles = Vec::new();
        for (r, cells) in self.cells.iter().enumerate() {
            let mut c = 0;
            while c < cells.len() {
                let key = style_key(&cells[c]);
                let mut end = c + 1;
                while end < cells.len() && style_key(&cells[end]) == key {
                    end += 1;
                }
                if key != (0, 0, 0) {
                    styles.push([r as u32, c as u32, end as u32, key.0, key.1, key.2]);
                }
                c = end;
            }
        }
        ExpectedGrid {
            cols: self.cols,
            rows: self.rows,
            default_fg: self.default_fg,
            default_bg: self.default_bg,
            cursor: ExpectedCursor {
                row: self.cursor_row,
                col: self.cursor_col,
                visible: self.cursor_visible,
            },
            lines: (0..self.rows).map(|r| self.row_text(r)).collect(),
            styles,
        }
    }
}

/// A spacer's style is its lead's; the spacer bit itself is implied by the lead being wide.
fn style_key(cell: &Cell) -> (u32, u32, u32) {
    (cell.fg, cell.bg, cell.attrs & !attr::WIDE_SPACER)
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExpectedCursor {
    pub row: u16,
    pub col: u16,
    pub visible: bool,
}

/// One terminal's grid as JSON (`testing/fixtures/expected/*.grid.json`): the text of each row
/// (wide spacers contribute nothing) and every span of cells whose `(fg, bg, attrs)` is not all
/// zero, as `[row, from_col, to_col_exclusive, fg, bg, attrs]`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExpectedGrid {
    pub cols: u16,
    pub rows: u16,
    pub default_fg: u32,
    pub default_bg: u32,
    pub cursor: ExpectedCursor,
    pub lines: Vec<String>,
    pub styles: Vec<[u32; 6]>,
}
