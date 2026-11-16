# Changes made by Embryo-Zero (Embryo Zero Backyard Data Center Corporation) to Freerouting 2.5.0

All changes are on the `embryo` branch; `git log` has the full detail.

| Date | Files | Change |
|---|---|---|
| 2026-11-15 | EMBRYO.md | Fork notes and plan |
| 2026-11-16 | autoroute/pipeline/ParallelBoardWork.java (new) | Fanout and routing on many cores, area by area, with clearance checks and undo |
| 2026-11-16 | autoroute/pipeline/BatchFanout.java, AutoroutePassRunner.java, BatchAutorouter.java, AutorouteConnectionRouter.java, AutorouteBatchLoop.java (modified) | Use ParallelBoardWork; no-ripup mode for parallel workers; core-count log line |
| 2026-11-16 | settings/sources/CliSettings.java (modified) | -mt sets every stage; -mt 0 = all cores |
| 2026-11-16 | gui/windows/routing/WindowAutorouteParameter.java (modified) | Shows the computer's cores and a "Use all cores" button |
| 2026-11-16 | chip/Lef.java, Def.java, ChipRouter.java (new); chips/ (new) | Embryo Chip Router for microchips (SKY130) and its check flow |
| 2026-11-16 | README.md | Embryo name at the top; the original README follows unchanged |
