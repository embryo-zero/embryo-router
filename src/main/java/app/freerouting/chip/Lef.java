// SPDX-License-Identifier: GPL-3.0
// Part of Embryo Router, a modified version of Freerouting (GPL-3.0). Added by Embryo-Zero, 2026-11-16.

package app.freerouting.chip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embryo Chip Router: reads the parts of LEF files (technology + standard cells) a router needs:
 * routing layers (direction, pitch, offset, width, spacing), fixed vias, and each cell's size,
 * pin shapes and obstructions. Coordinates are kept in microns here.
 */
public final class Lef {

  /** One routing or cut layer. */
  public static final class Layer {
    public final String name;
    public String type = "";
    public boolean horizontal;
    public double pitchX;
    public double pitchY;
    public double offsetX;
    public double offsetY;
    public double width;
    public double spacing;

    Layer(String name) {
      this.name = name;
    }
  }

  /** An axis-aligned rectangle on one layer, microns. */
  public record Rect(String layer, double x1, double y1, double x2, double y2) {}

  /** A fixed via: its shapes on the bottom layer, the cut and the top layer. */
  public static final class Via {
    public final String name;
    public boolean isDefault;
    public final List<Rect> rects = new ArrayList<>();

    Via(String name) {
      this.name = name;
    }

    /** The layers this via joins (routing layers only), in the order listed. */
    public List<String> layers() {
      List<String> out = new ArrayList<>();
      for (Rect r : rects) {
        if (!out.contains(r.layer())) {
          out.add(r.layer());
        }
      }
      return out;
    }
  }

  /** One standard cell. */
  public static final class Macro {
    public final String name;
    public double width;
    public double height;
    public final Map<String, List<Rect>> pins = new LinkedHashMap<>();
    public final Map<String, String> pinUse = new HashMap<>();
    public final List<Rect> obs = new ArrayList<>();

    Macro(String name) {
      this.name = name;
    }
  }

  public final Map<String, Layer> layers = new LinkedHashMap<>();
  public final Map<String, Via> vias = new LinkedHashMap<>();
  public final Map<String, Macro> macros = new HashMap<>();
  public double siteWidth = 0;
  public double siteHeight = 0;

  /** Reads one LEF file into this object (call it for the technology LEF, then the cell LEF). */
  public Lef read(Path file) throws IOException {
    Tokens t = new Tokens(Files.readString(file));
    while (t.more()) {
      String w = t.next();
      switch (w) {
        case "LAYER" -> readLayer(t);
        case "VIA" -> readVia(t);
        case "MACRO" -> readMacro(t);
        case "SITE" -> readSite(t);
        case "VIARULE", "PROPERTYDEFINITIONS", "UNITS", "SPACING", "NONDEFAULTRULE" -> skipBlock(t, w);
        default -> {
          // statements we do not need end with ';'
        }
      }
    }
    return this;
  }

  private static void skipBlock(Tokens t, String kind) {
    String name = kind.equals("VIARULE") || kind.equals("NONDEFAULTRULE") ? t.next() : kind;
    while (t.more()) {
      String w = t.next();
      if (w.equals("END") && t.more() && t.peek().equals(name)) {
        t.next();
        return;
      }
    }
  }

  private void readSite(Tokens t) {
    String name = t.next();
    while (t.more()) {
      String w = t.next();
      if (w.equals("SIZE")) {
        double a = t.num();
        t.next();
        double b = t.num();
        if (siteWidth == 0 || name.equals("unithd") || name.equals("unit")) {
          siteWidth = a;
          siteHeight = b;
        }
      } else if (w.equals("END") && t.peek().equals(name)) {
        t.next();
        return;
      }
    }
  }

  private void readLayer(Tokens t) {
    Layer l = new Layer(t.next());
    boolean sawSpacing = false;
    while (t.more()) {
      String w = t.next();
      switch (w) {
        case "TYPE" -> l.type = t.next();
        case "DIRECTION" -> l.horizontal = t.next().equals("HORIZONTAL");
        case "PITCH" -> {
          l.pitchX = t.num();
          l.pitchY = t.peek().equals(";") ? l.pitchX : t.num();
        }
        case "OFFSET" -> {
          l.offsetX = t.num();
          l.offsetY = t.peek().equals(";") ? l.offsetX : t.num();
        }
        case "WIDTH" -> {
          if (l.width == 0) {
            l.width = t.num();
          }
        }
        case "SPACING" -> {
          if (!sawSpacing && isNumber(t.peek())) {
            l.spacing = t.num();
            sawSpacing = true;
          }
        }
        case "SPACINGTABLE" -> {
          // SPACINGTABLE PARALLELRUNLENGTH 0.00 WIDTH 0 0.14 ... ; -> first spacing value
          while (t.more() && !t.peek().equals(";")) {
            String x = t.next();
            if (x.equals("WIDTH") && !sawSpacing) {
              t.num();
              l.spacing = t.num();
              sawSpacing = true;
            }
          }
        }
        case "END" -> {
          if (t.peek().equals(l.name)) {
            t.next();
            layers.put(l.name, l);
            return;
          }
        }
        default -> {
          // ignored
        }
      }
    }
  }

  private void readVia(Tokens t) {
    Via v = new Via(t.next());
    String layer = null;
    while (t.more()) {
      String w = t.next();
      switch (w) {
        case "DEFAULT" -> v.isDefault = true;
        case "LAYER" -> layer = t.next();
        case "RECT" -> v.rects.add(new Rect(layer, t.num(), t.num(), t.num(), t.num()));
        case "END" -> {
          if (t.peek().equals(v.name)) {
            t.next();
            vias.put(v.name, v);
            return;
          }
        }
        default -> {
          // ignored
        }
      }
    }
  }

  private void readMacro(Tokens t) {
    Macro m = new Macro(t.next());
    while (t.more()) {
      String w = t.next();
      switch (w) {
        case "SIZE" -> {
          m.width = t.num();
          t.next();
          m.height = t.num();
        }
        case "PIN" -> readPin(t, m);
        case "OBS" -> {
          String layer = null;
          while (t.more()) {
            String x = t.next();
            if (x.equals("END")) {
              break;
            }
            if (x.equals("LAYER")) {
              layer = t.next();
            } else if (x.equals("RECT")) {
              m.obs.add(new Rect(layer, t.num(), t.num(), t.num(), t.num()));
            }
          }
        }
        case "END" -> {
          if (t.peek().equals(m.name)) {
            t.next();
            macros.put(m.name, m);
            return;
          }
        }
        default -> {
          // ignored
        }
      }
    }
  }

  private static void readPin(Tokens t, Macro m) {
    String pin = t.next();
    List<Rect> rects = new ArrayList<>();
    String layer = null;
    while (t.more()) {
      String w = t.next();
      if (w.equals("USE")) {
        m.pinUse.put(pin, t.next());
      } else if (w.equals("LAYER")) {
        layer = t.next();
      } else if (w.equals("RECT")) {
        rects.add(new Rect(layer, t.num(), t.num(), t.num(), t.num()));
      } else if (w.equals("END") && t.peek().equals(pin)) {
        t.next();
        m.pins.put(pin, rects);
        return;
      }
    }
  }

  static boolean isNumber(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    char c = s.charAt(0);
    return Character.isDigit(c) || c == '-' || c == '.' || c == '+';
  }

  /** Splits LEF/DEF text into tokens; ';' '(' ')' are separate tokens, '#' starts a comment. */
  static final class Tokens {
    private final List<String> list = new ArrayList<>();
    private int pos = 0;

    Tokens(String text) {
      StringBuilder cur = new StringBuilder();
      boolean quote = false;
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (quote) {
          cur.append(c);
          if (c == '"') {
            quote = false;
          }
          continue;
        }
        if (c == '"') {
          quote = true;
          cur.append(c);
        } else if (c == '#') {
          while (i < text.length() && text.charAt(i) != '\n') {
            i++;
          }
          flush(cur);
        } else if (Character.isWhitespace(c)) {
          flush(cur);
        } else if (c == ';' || c == '(' || c == ')') {
          flush(cur);
          list.add(String.valueOf(c));
        } else {
          cur.append(c);
        }
      }
      flush(cur);
    }

    private void flush(StringBuilder cur) {
      if (cur.length() > 0) {
        list.add(cur.toString());
        cur.setLength(0);
      }
    }

    boolean more() {
      return pos < list.size();
    }

    String next() {
      return list.get(pos++);
    }

    String peek() {
      return pos < list.size() ? list.get(pos) : "";
    }

    double num() {
      return Double.parseDouble(next());
    }
  }
}
