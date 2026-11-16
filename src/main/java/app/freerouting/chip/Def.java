// SPDX-License-Identifier: GPL-3.0
// Part of Embryo Router, a modified version of Freerouting (GPL-3.0). Added by Embryo-Zero, 2026-11-16.

package app.freerouting.chip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embryo Chip Router: reads a placed DEF design (die area, placed cells, I/O pins, nets) and writes
 * it back with routed wiring. Everything outside the NETS section is copied unchanged.
 * Coordinates are in DEF database units (dbu).
 */
public final class Def {

  /** A placed cell. */
  public record Component(String name, String macro, int x, int y, String orient) {}

  /** A design-level I/O pin with its shape already placed, dbu. */
  public record IoPin(String name, String net, String layer, int x1, int y1, int x2, int y2) {}

  /** A net: the (component, pin) pairs it joins; component "PIN" means a design I/O pin. */
  public static final class Net {
    public final String name;
    public final List<String[]> terms = new ArrayList<>();
    public String use = "SIGNAL";

    Net(String name) {
      this.name = name;
    }
  }

  public int dbu = 1000;
  public int dieX1;
  public int dieY1;
  public int dieX2;
  public int dieY2;
  public final Map<String, Component> components = new LinkedHashMap<>();
  public final Map<String, IoPin> pins = new LinkedHashMap<>();
  public final Map<String, Net> nets = new LinkedHashMap<>();
  private String text = "";

  public static Def read(Path file) throws IOException {
    Def d = new Def();
    d.text = Files.readString(file);
    Lef.Tokens t = new Lef.Tokens(d.text);
    while (t.more()) {
      String w = t.next();
      switch (w) {
        case "UNITS" -> {
          t.next();
          t.next();
          d.dbu = (int) t.num();
        }
        case "DIEAREA" -> {
          t.next();
          d.dieX1 = (int) t.num();
          d.dieY1 = (int) t.num();
          t.next();
          t.next();
          d.dieX2 = (int) t.num();
          d.dieY2 = (int) t.num();
        }
        case "COMPONENTS" -> d.readComponents(t);
        case "PINS" -> d.readPins(t);
        case "NETS" -> d.readNets(t);
        default -> {
          // other sections are copied as they are
        }
      }
    }
    return d;
  }

  private void readComponents(Lef.Tokens t) {
    while (t.more()) {
      String w = t.next();
      if (w.equals("END") && t.peek().equals("COMPONENTS")) {
        t.next();
        return;
      }
      if (!w.equals("-")) {
        continue;
      }
      String name = t.next();
      String macro = t.next();
      int x = 0;
      int y = 0;
      String orient = "N";
      while (t.more()) {
        String x1 = t.next();
        if (x1.equals(";")) {
          break;
        }
        if (x1.equals("PLACED") || x1.equals("FIXED")) {
          t.next();
          x = (int) t.num();
          y = (int) t.num();
          t.next();
          orient = t.next();
        }
      }
      components.put(name, new Component(name, macro, x, y, orient));
    }
  }

  private void readPins(Lef.Tokens t) {
    while (t.more()) {
      String w = t.next();
      if (w.equals("END") && t.peek().equals("PINS")) {
        t.next();
        return;
      }
      if (!w.equals("-")) {
        continue;
      }
      String name = t.next();
      String net = name;
      String layer = null;
      int rx1 = 0;
      int ry1 = 0;
      int rx2 = 0;
      int ry2 = 0;
      int px = 0;
      int py = 0;
      String orient = "N";
      while (t.more()) {
        String x = t.next();
        if (x.equals(";")) {
          break;
        }
        switch (x) {
          case "NET" -> net = t.next();
          case "LAYER" -> {
            layer = t.next();
            t.next();
            rx1 = (int) t.num();
            ry1 = (int) t.num();
            t.next();
            t.next();
            rx2 = (int) t.num();
            ry2 = (int) t.num();
            t.next();
          }
          case "PLACED", "FIXED" -> {
            t.next();
            px = (int) t.num();
            py = (int) t.num();
            t.next();
            orient = t.next();
          }
          default -> {
            // ignored
          }
        }
      }
      if (layer != null) {
        int ax1 = rx1;
        int ay1 = ry1;
        int ax2 = rx2;
        int ay2 = ry2;
        switch (orient) {
          case "S" -> { ax1 = -rx2; ax2 = -rx1; ay1 = -ry2; ay2 = -ry1; }
          case "E" -> { ax1 = ry1; ax2 = ry2; ay1 = -rx2; ay2 = -rx1; }
          case "W" -> { ax1 = -ry2; ax2 = -ry1; ay1 = rx1; ay2 = rx2; }
          default -> { }
        }
        pins.put(name, new IoPin(name, net, layer, px + ax1, py + ay1, px + ax2, py + ay2));
      }
    }
  }

  private void readNets(Lef.Tokens t) {
    while (t.more()) {
      String w = t.next();
      if (w.equals("END") && t.peek().equals("NETS")) {
        t.next();
        return;
      }
      if (!w.equals("-")) {
        continue;
      }
      Net n = new Net(t.next());
      while (t.more()) {
        String x = t.next();
        if (x.equals(";")) {
          break;
        }
        if (x.equals("(")) {
          String comp = t.next();
          String pin = t.next();
          t.next();
          n.terms.add(new String[] {comp, pin});
        } else if (x.equals("USE")) {
          n.use = t.next();
        }
      }
      nets.put(n.name, n);
    }
  }

  /** Writes the design with each net's routing (DEF "+ ROUTED" text per net; may be absent). */
  public void write(Path file, Map<String, String> routing) throws IOException {
    int a = indexOfSection(text, "NETS");
    int b = text.indexOf("END NETS", a);
    StringBuilder sb = new StringBuilder();
    sb.append(text, 0, a);
    sb.append("NETS ").append(nets.size()).append(" ;\n");
    for (Net n : nets.values()) {
      sb.append("- ").append(n.name);
      for (String[] term : n.terms) {
        sb.append(" ( ").append(term[0]).append(' ').append(term[1]).append(" )");
      }
      if (!"SIGNAL".equals(n.use)) {
        sb.append(" + USE ").append(n.use);
      }
      String r = routing.get(n.name);
      if (r != null && !r.isEmpty()) {
        sb.append('\n').append(r);
      }
      sb.append(" ;\n");
    }
    sb.append(text.substring(b));
    Files.writeString(file, sb.toString());
  }

  /** Start of the NETS section header line (not SPECIALNETS). */
  private static int indexOfSection(String text, String name) {
    int from = 0;
    while (true) {
      int i = text.indexOf(name, from);
      if (i < 0) {
        throw new IllegalStateException("no " + name + " section");
      }
      boolean lineStart = i == 0 || text.charAt(i - 1) == '\n';
      if (lineStart && text.startsWith(name + " ", i)) {
        return i;
      }
      from = i + name.length();
    }
  }
}
