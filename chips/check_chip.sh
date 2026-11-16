#!/bin/bash
# SPDX-License-Identifier: GPL-3.0 (Embryo Router, 2026-11-16)
# Embryo Chip Router check flow: route a DEF, turn it into GDS with the real SKY130 cells, run the SKY130 rule deck.
# usage: check_chip.sh design.def [cores] [top layer]
set -e
cd "$(dirname "$1")"; B=$(basename "$1" .def)
L=~/embryo-router/chips/pdk/sky130/sky130A/libs.ref/sky130_fd_sc_hd
D=~/embryo-router/chips/pdk/sky130/sky130A/libs.tech/klayout/drc
JAR=${JAR:-~/embryo-router/src/build/libs/freerouting-current-executable.jar}
~/jdk/java-1.25.0-openjdk-amd64/bin/java -Xmx6g -cp $JAR app.freerouting.chip.ChipRouter $L/techlef/sky130_fd_sc_hd__nom.tlef $L/lef/sky130_fd_sc_hd.lef $B.def $B.routed.def ${2:-0} ${3:-met4} 80 | grep -vE "round [0-9]+:" | tail -5
klayout -b -r ~/embryo-router/chips/def2gds.py -rd defin=$B.routed.def -rd out=$B.gds 2>&1 | grep -vE "No mapping" | tail -1
klayout -b -r $D/sky130A_mr.drc -rd input=$B.gds -rd report=$B.drc.xml -rd feol=true -rd beol=true -rd offgrid=true >/dev/null 2>&1
python3 -c "
import xml.etree.ElementTree as ET, collections
r = ET.parse('$B.drc.xml').getroot()
c = collections.Counter(i.findtext('category').strip(chr(39)) for i in r.iter('item'))
metal = {k: v for k, v in c.items() if k[:1] in ('m', 'v', 'l') or k.startswith('li') or k.startswith('ct')}
print('SKY130 check:', sum(c.values()), 'total;', sum(metal.values()), 'from wiring', metal, '; placement/cells:', {k: v for k, v in c.items() if k not in metal})"
