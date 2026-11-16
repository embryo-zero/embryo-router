# klayout -b -r def2gds.py -rd defin=in.def -rd out=out.gds   (Embryo Chip Router check flow)
import pya, os
L = os.path.expanduser("~/embryo-router/chips/pdk/sky130/sky130A/libs.ref/sky130_fd_sc_hd")
opt = pya.LoadLayoutOptions()
c = opt.lefdef_config
c.read_lef_with_def = False
c.lef_files = [L + "/techlef/sky130_fd_sc_hd__nom.tlef", L + "/lef/sky130_fd_sc_hd.lef"]
c.macro_layout_files = [L + "/gds/sky130_fd_sc_hd.gds"]
c.macro_resolution_mode = 2
c.map_file = os.path.expanduser("~/embryo-router/chips/sky130.map")
ly = pya.Layout()
ly.read(defin, opt)
ly.write(out)
print("wrote", out, "top", ly.top_cells()[0].name, "cells", ly.cells())
