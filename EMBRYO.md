# Embryo Router

Embryo Router is Embryo-Zero's fork of [Freerouting](https://github.com/freerouting/freerouting) 2.5.0
(GPL-3.0; the original LICENSE and notices are kept unchanged). Our changes live on the `embryo` branch.

Freerouting routes printed circuit boards from Specctra DSN files (KiCad, Eagle, ...). We are extending it to:

1. **Route boards faster on many cores**: fanout that skips pins with no connection and runs by area in parallel; the
   autorouter routing separate areas of a board at the same time; built-in reuse for boards made of identical modules;
   later, one board spread across a cluster of machines.
2. **Route microchips**: read LEF/DEF, route on each metal layer's track grid with the process design rules, write DEF.
   First process: the open SkyWater SKY130.
3. **Route other things**: FPGA designs, data center cabling, wire harnesses and network paths.

Every change is checked against the official Freerouting 2.5.0 result on the same input.

Built with Java 25: `./gradlew executableJar -x test` -> `build/libs/freerouting-current-executable.jar`.
