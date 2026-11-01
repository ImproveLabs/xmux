# xmux-quest-stress

Quest rendering stress-test app with configurable window counts, animation rates, and synthetic
agent activity. Also serves as a minimal example of embedding `xmux-spatial`.

Build and install with `vpx _quest-install stress`, then run a workload with
`vpx _quest-stress 32 --active 4 --fps 30`. Use `vpx _quest-stats stress` to inspect runtime stats.
