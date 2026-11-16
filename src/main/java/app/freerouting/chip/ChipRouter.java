package app.freerouting.chip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Embryo Chip Router: routes a placed standard-cell design (LEF + DEF) on the metal layers' track
 * grids.
 *
 * <p>Grid: one node per track crossing; each routing layer only uses the nodes on its own tracks
 * and moves only in its preferred direction. Cell pins on li1 are reached with an li1-to-met1 via
 * placed fully inside the pin. Spacing between different nets is kept by giving every used node a
 * "halo" along its track, sized from the layer's width, spacing and via pads.
 *
 * <p>Method: negotiated congestion (PathFinder). Every net is routed as a tree with A*, nets may
 * share nodes at first, and each round shared nodes get more expensive until no two nets touch.
 * Nets whose search windows do not overlap are routed at the same time on all cores.
 */
public final class ChipRouter {

  // ---------------------------------------------------------------- grid
  final Lef lef;
  final Def def;
  final int threads;
  final List<Lef.Layer> layers = new ArrayList<>(); // routing layers used, bottom first
  int xs;
  int ys;
  int nx;
  int ny;
  int nl;
  boolean[][] onTrackX; // [layer][i] for vertical layers: x_i is a track
  boolean[][] onTrackY; // [layer][j] for horizontal layers
  int[] haloWire; // per layer, nodes along the track another net must keep clear of a wire node
  int[] haloVia; // ... of a via node
  String[] viaName; // via between layer l and l+1
  String accessVia; // li1 -> bottom layer
  int[] owner; // -1 free, -2 blocked for everyone, >= 0 only that net may use it
  int[] pathUse; // how many nets have a wire/via at this node
  int[] claimUse; // how many nets claim this node through their halos
  float[] hist; // history cost (PathFinder)
  int[] viaUse; // how many nets have a via pad at this node
  int[] crossStep; // per layer: grid steps to the neighbouring track, 0 if pads there never clash
  int[] crossAlong; // per layer: pads clash up to this many nodes along the track on that neighbour
  int[] padAlong; // per layer: largest pad half size along the track (dbu)
  int[] padAcross; // ... across the track
  float presFac = 0.5f;

  // ---------------------------------------------------------------- nets
  final List<NetJob> jobs = new ArrayList<>();
  static final int MAX_NUDGE = 20; // dbu: largest nudge of an li1 via across met1's tracks
  static final int MAX_NUDGE_ALONG = 100; // dbu: ... along them
  int noAccess = 0;

  /** One net: its terminals (each a set of nodes) and its current route. */
  final class NetJob {
    final int id;
    final String name;
    final List<int[]> terms = new ArrayList<>(); // each: node ids that reach that pin
    final List<Integer> accessNodes = new ArrayList<>(); // nodes that get an li1 via if used
    final Map<Integer, int[]> accessShift = new HashMap<>(); // node -> via nudged by (dx, dy) dbu onto a narrow pin
    int wx1;
    int wy1;
    int wx2;
    int wy2; // search window, grid indices
    int margin = 12;
    List<int[]> paths = new ArrayList<>(); // node sequences
    boolean[] isVia; // unused
    java.util.Set<Integer> nodes = new java.util.HashSet<>();
    java.util.Set<Integer> claims = new java.util.HashSet<>();
    java.util.Set<Integer> vias = new java.util.HashSet<>(); // nodes with a via pad (both layers)
    boolean routed;

    NetJob(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public ChipRouter(Lef lef, Def def, String topLayer, int threads) {
    this.lef = lef;
    this.def = def;
    this.threads = Math.max(1, threads);
    boolean started = false;
    for (Lef.Layer l : lef.layers.values()) {
      if (!"ROUTING".equals(l.type)) {
        continue;
      }
      if (l.name.equals("met1")) {
        started = true;
      }
      if (started) {
        layers.add(l);
      }
      if (l.name.equals(topLayer)) {
        break;
      }
    }
    nl = layers.size();
    buildGrid();
  }

  int dbu(double um) {
    return (int) Math.round(um * def.dbu);
  }

  static int gcd(int a, int b) {
    return b == 0 ? Math.abs(a) : gcd(b, a % b);
  }

  int pitch(Lef.Layer l) {
    return dbu(l.horizontal ? l.pitchY : l.pitchX);
  }

  int offset(Lef.Layer l) {
    return dbu(l.horizontal ? l.offsetY : l.offsetX);
  }

  int node(int l, int i, int j) {
    return (l * ny + j) * nx + i;
  }

  int layerOf(int n) {
    return n / (nx * ny);
  }

  int iOf(int n) {
    return n % nx;
  }

  int jOf(int n) {
    return (n / nx) % ny;
  }

  int xOf(int i) {
    return def.dieX1 + i * xs;
  }

  int yOf(int j) {
    return def.dieY1 + j * ys;
  }

  boolean onTrack(int l, int i, int j) {
    return layers.get(l).horizontal ? onTrackY[l][j] : onTrackX[l][i];
  }

  private void buildGrid() {
    xs = 0;
    ys = 0;
    for (Lef.Layer l : layers) {
      if (l.horizontal) {
        ys = gcd(ys, gcd(pitch(l), offset(l)));
      } else {
        xs = gcd(xs, gcd(pitch(l), offset(l)));
      }
    }
    nx = (def.dieX2 - def.dieX1) / xs + 1;
    ny = (def.dieY2 - def.dieY1) / ys + 1;
    onTrackX = new boolean[nl][nx];
    onTrackY = new boolean[nl][ny];
    for (int l = 0; l < nl; l++) {
      Lef.Layer L = layers.get(l);
      int p = pitch(L);
      int o = offset(L);
      if (L.horizontal) {
        for (int j = 0; j < ny; j++) {
          int d = j * ys - o;
          onTrackY[l][j] = d >= 0 && d % p == 0;
        }
      } else {
        for (int i = 0; i < nx; i++) {
          int d = i * xs - o;
          onTrackX[l][i] = d >= 0 && d % p == 0;
        }
      }
    }
    // vias between neighbouring layers: the first DEFAULT via joining them
    viaName = new String[nl];
    for (int l = 0; l + 1 < nl; l++) {
      viaName[l] = findVia(layers.get(l).name, layers.get(l + 1).name);
    }
    accessVia = findVia("li1", layers.get(0).name);
    // halos: k nodes along the track such that k*step - myHalf - otherHalf >= spacing
    haloWire = new int[nl];
    haloVia = new int[nl];
    for (int l = 0; l < nl; l++) {
      Lef.Layer L = layers.get(l);
      int step = L.horizontal ? xs : ys;
      int hw = dbu(L.width / 2);
      int pad = padHalf(l, L.horizontal);
      int sp = dbu(L.spacing);
      haloWire[l] = halo(step, hw, Math.max(hw, pad), sp);
      haloVia[l] = halo(step, Math.max(hw, pad), Math.max(hw, pad), sp);
    }
    crossStep = new int[nl];
    crossAlong = new int[nl];
    padAlong = new int[nl];
    padAcross = new int[nl];
    for (int l = 0; l < nl; l++) {
      Lef.Layer L = layers.get(l);
      int step = L.horizontal ? xs : ys;
      int across = L.horizontal ? ys : xs;
      int sp = dbu(L.spacing);
      padAlong[l] = Math.max(dbu(L.width / 2), padHalf(l, L.horizontal));
      padAcross[l] = Math.max(dbu(L.width / 2), padHalfAcross(l));
      if (pitch(L) - 2 * padAcross[l] < sp) {
        crossStep[l] = pitch(L) / across;
        int k = 0;
        while ((k + 1) * step - 2 * padAlong[l] < sp) {
          k++;
        }
        crossAlong[l] = k;
      }
    }
    int n = nl * nx * ny;
    owner = new int[n];
    viaUse = new int[n];
    pathUse = new int[n];
    claimUse = new int[n];
    hist = new float[n];
    Arrays.fill(owner, -1);
    for (int l = 0; l < nl; l++) {
      for (int j = 0; j < ny; j++) {
        for (int i = 0; i < nx; i++) {
          if (!onTrack(l, i, j)) {
            owner[node(l, i, j)] = -2;
          }
        }
      }
    }
  }

  static int halo(int step, int mine, int other, int spacing) {
    int k = 1;
    while (k * step - mine - other < spacing) {
      k++;
    }
    return k - 1;
  }

  String findVia(String bottom, String top) {
    for (Lef.Via v : lef.vias.values()) {
      List<String> ls = v.layers();
      if (v.isDefault && ls.contains(bottom) && ls.contains(top) && !v.name.endsWith("_R") && !v.name.endsWith("_M")) {
        return v.name;
      }
    }
    for (Lef.Via v : lef.vias.values()) {
      List<String> ls = v.layers();
      if (ls.contains(bottom) && ls.contains(top)) {
        return v.name;
      }
    }
    return null;
  }

  /** Largest half size, along (alongTrack) the layer, of any via pad that lands on routing layer l. */
  int padHalf(int l, boolean horizontalTrack) {
    String name = layers.get(l).name;
    int best = 0;
    List<String> candidates = new ArrayList<>();
    if (l == 0 && accessVia != null) {
      candidates.add(accessVia);
    }
    if (l > 0 && viaName[l - 1] != null) {
      candidates.add(viaName[l - 1]);
    }
    if (l < nl - 1 && viaName[l] != null) {
      candidates.add(viaName[l]);
    }
    for (String vn : candidates) {
      for (Lef.Rect r : lef.vias.get(vn).rects) {
        if (r.layer().equals(name)) {
          double h = horizontalTrack ? Math.max(-r.x1(), r.x2()) : Math.max(-r.y1(), r.y2());
          best = Math.max(best, dbu(h));
        }
      }
    }
    return best;
  }

  /** Half size across the track of the largest pad on layer l (for blocking near obstacles). */
  int padHalfAcross(int l) {
    return padHalf(l, !layers.get(l).horizontal);
  }

  // ---------------------------------------------------------------- obstacles and pins

  /** Rectangle of a cell shape on the die, dbu, for the cell's orientation. */
  int[] place(Def.Component c, Lef.Macro m, Lef.Rect r) {
    double w = m.width;
    double h = m.height;
    double x1 = r.x1();
    double y1 = r.y1();
    double x2 = r.x2();
    double y2 = r.y2();
    double a1;
    double b1;
    double a2;
    double b2;
    switch (c.orient()) {
      case "S" -> {
        a1 = w - x2; a2 = w - x1; b1 = h - y2; b2 = h - y1;
      }
      case "FS" -> {
        a1 = x1; a2 = x2; b1 = h - y2; b2 = h - y1;
      }
      case "FN" -> {
        a1 = w - x2; a2 = w - x1; b1 = y1; b2 = y2;
      }
      default -> {
        a1 = x1; a2 = x2; b1 = y1; b2 = y2;
      }
    }
    return new int[] {c.x() + dbu(a1), c.y() + dbu(b1), c.x() + dbu(a2), c.y() + dbu(b2)};
  }

  int layerIndex(String name) {
    for (int l = 0; l < nl; l++) {
      if (layers.get(l).name.equals(name)) {
        return l;
      }
    }
    return -1;
  }

  /** Marks nodes on layer l too close to rect (dbu) as blocked (or owned by net, if net >= 0). */
  void blockNear(int l, int[] r, int net) {
    Lef.Layer L = layers.get(l);
    int hw = dbu(L.width / 2);
    int halfX = Math.max(hw, padHalf(l, true));
    int halfY = Math.max(hw, padHalf(l, false));
    int sp = dbu(L.spacing);
    int i1 = Math.max(0, (r[0] - halfX - sp - def.dieX1) / xs - 1);
    int i2 = Math.min(nx - 1, (r[2] + halfX + sp - def.dieX1) / xs + 1);
    int j1 = Math.max(0, (r[1] - halfY - sp - def.dieY1) / ys - 1);
    int j2 = Math.min(ny - 1, (r[3] + halfY + sp - def.dieY1) / ys + 1);
    for (int j = j1; j <= j2; j++) {
      for (int i = i1; i <= i2; i++) {
        int n = node(l, i, j);
        if (owner[n] == -2) {
          continue;
        }
        int x = xOf(i);
        int y = yOf(j);
        int dx = Math.max(Math.max(r[0] - x, x - r[2]), 0) - halfX;
        int dy = Math.max(Math.max(r[1] - y, y - r[3]), 0) - halfY;
        boolean inside = x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
        if (net >= 0 && inside) {
          if (owner[n] == -1 || owner[n] == net) {
            owner[n] = net;
          } else {
            owner[n] = -2;
          }
          continue;
        }
        if (dx < sp && dy < sp) {
          if (net >= 0 && owner[n] == net) {
            continue;
          }
          owner[n] = -2;
        }
      }
    }
  }

  /** Reads cells, pins and nets; sets up obstacles, pin access and the net list. */
  public void prepare() {
    Map<String, Integer> netIds = new HashMap<>();
    Map<String, String> pinNet = new HashMap<>(); // "comp/pin" -> net
    for (Def.Net n : def.nets.values()) {
      if (!"SIGNAL".equals(n.use) && !"CLOCK".equals(n.use)) {
        continue;
      }
      NetJob job = new NetJob(jobs.size(), n.name);
      jobs.add(job);
      netIds.put(n.name, job.id);
      for (String[] t : n.terms) {
        pinNet.put(t[0] + "/" + t[1], n.name);
      }
    }
    // obstructions and power pins on the routing layers, and other cells' metal pins
    for (Def.Component c : def.components.values()) {
      Lef.Macro m = lef.macros.get(c.macro());
      if (m == null) {
        continue;
      }
      for (Lef.Rect r : m.obs) {
        int l = layerIndex(r.layer());
        if (l >= 0) {
          blockNear(l, place(c, m, r), -1);
        }
      }
      for (Map.Entry<String, List<Lef.Rect>> p : m.pins.entrySet()) {
        String net = pinNet.get(c.name() + "/" + p.getKey());
        Integer id = net == null ? null : netIds.get(net);
        for (Lef.Rect r : p.getValue()) {
          int l = layerIndex(r.layer());
          if (l >= 0) {
            blockNear(l, place(c, m, r), id == null ? -1 : id);
          }
        }
      }
    }
    for (Def.IoPin p : def.pins.values()) {
      int l = layerIndex(p.layer());
      Integer id = netIds.get(p.net());
      if (l >= 0) {
        blockNear(l, new int[] {p.x1(), p.y1(), p.x2(), p.y2()}, id == null ? -1 : id);
      }
    }
    // terminals
    Lef.Via av = accessVia == null ? null : lef.vias.get(accessVia);
    for (Def.Net n : def.nets.values()) {
      Integer id = netIds.get(n.name);
      if (id == null) {
        continue;
      }
      NetJob job = jobs.get(id);
      for (String[] t : n.terms) {
        List<Integer> nodes = new ArrayList<>();
        if (t[0].equals("PIN")) {
          Def.IoPin p = def.pins.get(t[1]);
          int l = p == null ? -1 : layerIndex(p.layer());
          if (l >= 0) {
            addInside(nodes, l, new int[] {p.x1(), p.y1(), p.x2(), p.y2()}, id);
          }
        } else {
          Def.Component c = def.components.get(t[0]);
          Lef.Macro m = c == null ? null : lef.macros.get(c.macro());
          List<Lef.Rect> rects = m == null ? null : m.pins.get(t[1]);
          if (rects != null) {
            for (Lef.Rect r : rects) {
              int[] pr = place(c, m, r);
              int l = layerIndex(r.layer());
              if (l >= 0) {
                addInside(nodes, l, pr, id);
              } else if (r.layer().equals("li1") && av != null) {
                addAccess(nodes, job, pr, av, c, m);
              }
            }
          }
        }
        if (nodes.isEmpty()) {
          noAccess++;
          if (noAccess <= 10) {
            Def.Component c = def.components.get(t[0]);
            System.out.println("  no access: " + t[0] + "/" + t[1] + (c == null ? "" : " (" + c.macro() + " " + c.orient() + ")") + " net " + n.name);
          }
        } else {
          job.terms.add(nodes.stream().mapToInt(Integer::intValue).toArray());
        }
      }
    }
    for (NetJob job : jobs) {
      int x1 = Integer.MAX_VALUE;
      int y1 = Integer.MAX_VALUE;
      int x2 = -1;
      int y2 = -1;
      for (int[] t : job.terms) {
        for (int n : t) {
          x1 = Math.min(x1, iOf(n));
          x2 = Math.max(x2, iOf(n));
          y1 = Math.min(y1, jOf(n));
          y2 = Math.max(y2, jOf(n));
        }
      }
      job.wx1 = x1;
      job.wy1 = y1;
      job.wx2 = x2;
      job.wy2 = y2;
    }
  }

  void addInside(List<Integer> nodes, int l, int[] r, int net) {
    for (int j = Math.max(0, (r[1] - def.dieY1) / ys); j <= Math.min(ny - 1, (r[3] - def.dieY1) / ys + 1); j++) {
      for (int i = Math.max(0, (r[0] - def.dieX1) / xs); i <= Math.min(nx - 1, (r[2] - def.dieX1) / xs + 1); i++) {
        int x = xOf(i);
        int y = yOf(j);
        int n = node(l, i, j);
        if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3] && owner[n] == net) {
          nodes.add(n);
        }
      }
    }
  }

  /** Bottom-layer nodes where the li1 via fits inside the pin rect and keeps clear of the cell's cuts. */
  void addAccess(List<Integer> nodes, NetJob job, int[] pr, Lef.Via av, Def.Component c, Lef.Macro m) {
    int ax1 = 0;
    int ay1 = 0;
    int ax2 = 0;
    int ay2 = 0;
    String cut = null;
    int cx1 = 0;
    int cy1 = 0;
    int cx2 = 0;
    int cy2 = 0;
    for (Lef.Rect r : av.rects) {
      if (r.layer().equals("li1")) {
        ax1 = dbu(r.x1());
        ay1 = dbu(r.y1());
        ax2 = dbu(r.x2());
        ay2 = dbu(r.y2());
      } else if (!r.layer().equals(layers.get(0).name)) {
        cut = r.layer();
        cx1 = dbu(r.x1());
        cy1 = dbu(r.y1());
        cx2 = dbu(r.x2());
        cy2 = dbu(r.y2());
      }
    }
    Lef.Layer cutLayer = cut == null ? null : lef.layers.get(cut);
    int cutSpacing = cutLayer == null ? 0 : dbu(cutLayer.spacing);
    for (int j = Math.max(0, (pr[1] - def.dieY1) / ys); j <= Math.min(ny - 1, (pr[3] - def.dieY1) / ys + 1); j++) {
      for (int i = Math.max(0, (pr[0] - def.dieX1) / xs); i <= Math.min(nx - 1, (pr[2] - def.dieX1) / xs + 1); i++) {
        int x = xOf(i);
        int y = yOf(j);
        int dx = 0;
        int dy = 0;
        if (x + ax1 < pr[0]) {
          dx = pr[0] - (x + ax1);
        } else if (x + ax2 > pr[2]) {
          dx = pr[2] - (x + ax2);
        }
        if (y + ay1 < pr[1]) {
          dy = pr[1] - (y + ay1);
        } else if (y + ay2 > pr[3]) {
          dy = pr[3] - (y + ay2);
        }
        // nudge onto the 5 nm manufacturing grid, and only by a little (the pad covers the stub)
        dx = dx > 0 ? (dx + 4) / 5 * 5 : -((-dx + 4) / 5 * 5);
        dy = dy > 0 ? (dy + 4) / 5 * 5 : -((-dy + 4) / 5 * 5);
        // along met1's track (x) the halo leaves room for a bigger nudge than across it (y)
        boolean hz = layers.get(0).horizontal;
        if (Math.abs(hz ? dx : dy) > MAX_NUDGE_ALONG || Math.abs(hz ? dy : dx) > MAX_NUDGE) {
          continue;
        }
        if (x + dx + ax1 < pr[0] || x + dx + ax2 > pr[2] || y + dy + ay1 < pr[1] || y + dy + ay2 > pr[3]) {
          continue;
        }
        int n = node(0, i, j);
        if (owner[n] != -1 && owner[n] != job.id) {
          continue;
        }
        boolean clear = true;
        if (cut != null) {
          for (Lef.Rect o : m.obs) {
            if (!o.layer().equals(cut)) {
              continue;
            }
            int[] q = place(c, m, o);
            int gx = Math.max(q[0] - (x + dx + cx2), (x + dx + cx1) - q[2]);
            int gy = Math.max(q[1] - (y + dy + cy2), (y + dy + cy1) - q[3]);
            if (gx < cutSpacing && gy < cutSpacing) {
              clear = false;
              break;
            }
          }
        }
        if (clear) {
          owner[n] = job.id;
          nodes.add(n);
          job.accessNodes.add(n);
          if (dx != 0 || dy != 0) {
            job.accessShift.put(n, new int[] {dx, dy});
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------- routing

  /** Routes everything; returns a short report. */
  public String route(int maxRounds) throws Exception {
    long t0 = System.currentTimeMillis();
    ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
      Thread t = new Thread(r, "embryo-chip-worker");
      t.setDaemon(true);
      return t;
    });
    int conflicts = -1;
    int round = 0;
    try {
      List<NetJob> todo = new ArrayList<>(jobs);
      for (round = 1; round <= maxRounds; round++) {
        runBatches(todo, pool);
        conflicts = 0;
        List<NetJob> bad = new ArrayList<>();
        for (NetJob job : jobs) {
          if (hasConflict(job) || !job.routed) {
            bad.add(job);
          }
        }
        for (NetJob job : bad) {
          conflicts += crossConflicts(job, true);
        }
        for (int n = 0; n < pathUse.length; n++) {
          if (pathUse[n] + claimUse[n] > 1 && pathUse[n] > 0 && conflictAt(n)) {
            hist[n] += 1.0f;
            conflicts++;
          }
        }
        System.out.println("Embryo Chip Router round " + round + ": " + bad.size() + " nets to redo, "
            + conflicts + " shared spots, " + (System.currentTimeMillis() - t0) / 1000.0 + " s");
        if (bad.isEmpty()) {
          break;
        }
        presFac *= 1.6f;
        for (NetJob job : bad) {
          job.margin = Math.min(job.margin * 2, Math.max(nx, ny));
        }
        todo = bad;
      }
    } finally {
      pool.shutdownNow();
    }
    int routed = 0;
    for (NetJob j : jobs) {
      if (j.routed && !hasConflict(j)) {
        routed++;
      } else {
        int shared = 0;
        int halo = 0;
        for (int n : j.nodes) {
          if (pathUse[n] > 1) shared++;
          if (claimUse[n] > 0) halo++;
        }
        for (int h : j.claims) {
          if (pathUse[h] > 0) halo++;
        }
        int[] own = {0};
        int[] other = {0};
        for (int n : j.vias) {
          crossOf(n, m -> {
            if (j.vias.contains(m)) own[0]++;
            else if (viaUse[m] > 0) other[0]++;
          });
        }
        System.out.println("  not clean: " + j.name + " routed=" + j.routed + " shared=" + shared + " halo=" + halo
            + " padsNextTrack(other)=" + other[0] + " padsNextTrack(own)=" + own[0] + " terms=" + j.terms.size());
      }
    }
    return routed + " of " + jobs.size() + " nets routed cleanly in " + Math.min(round, maxRounds) + " rounds on " + threads
        + " cores, " + noAccess + " pins without access, " + (System.currentTimeMillis() - t0) / 1000.0 + " s";
  }

  /** A net's wire here is shared, or sits in another net's halo (nets never claim their own nodes). */
  boolean conflictAt(int n) {
    return pathUse[n] > 1 || (pathUse[n] >= 1 && claimUse[n] >= 1);
  }

  boolean hasConflict(NetJob job) {
    for (int n : job.nodes) {
      int others = pathUse[n] - 1;
      if (others > 0) {
        return true;
      }
      if (claimUse[n] > 0) {
        return true;
      }
    }
    for (int h : job.claims) {
      if (pathUse[h] > 0) {
        return true;
      }
    }
    return crossConflicts(job, false) > 0;
  }

  /** Via pads of this net too close to pads on a neighbouring track (other nets, or its own). */
  int crossConflicts(NetJob job, boolean addHistory) {
    int[] count = {0};
    for (int n : job.vias) {
      crossOf(n, m -> {
        // the net's own pads on the next track are joined by a metal patch in the output instead
        int others = viaUse[m] - (job.vias.contains(m) ? 1 : 0);
        if (others > 0) {
          count[0]++;
          if (addHistory) {
            hist[n] += 1.0f;
          }
        }
      });
    }
    return count[0];
  }

  /** Splits the nets into rounds of non-overlapping windows and routes each round on all cores. */
  void runBatches(List<NetJob> todo, ExecutorService pool) throws Exception {
    List<NetJob> left = new ArrayList<>(todo);
    left.sort((a, b) -> Long.compare(area(b), area(a)));
    while (!left.isEmpty()) {
      List<NetJob> batch = new ArrayList<>();
      List<int[]> boxes = new ArrayList<>();
      List<NetJob> next = new ArrayList<>();
      for (NetJob job : left) {
        int[] w = window(job, 6);
        boolean free = true;
        for (int[] b : boxes) {
          if (w[0] <= b[2] && b[0] <= w[2] && w[1] <= b[3] && b[1] <= w[3]) {
            free = false;
            break;
          }
        }
        if (free) {
          batch.add(job);
          boxes.add(w);
        } else {
          next.add(job);
        }
      }
      List<Future<?>> fs = new ArrayList<>();
      for (NetJob job : batch) {
        fs.add(pool.submit(() -> routeNet(job)));
      }
      for (Future<?> f : fs) {
        f.get();
      }
      left = next;
    }
  }

  long area(NetJob j) {
    int[] w = window(j, 0);
    return (long) (w[2] - w[0] + 1) * (w[3] - w[1] + 1);
  }

  int[] window(NetJob j, int extra) {
    int m = j.margin + extra;
    return new int[] {Math.max(0, j.wx1 - m), Math.max(0, j.wy1 - m), Math.min(nx - 1, j.wx2 + m), Math.min(ny - 1, j.wy2 + m)};
  }

  /** Rips up the net and routes it again as a tree, terminal by terminal, inside its window. */
  void routeNet(NetJob job) {
    unclaim(job);
    job.paths = new ArrayList<>();
    job.nodes = new java.util.HashSet<>();
    job.routed = false;
    if (job.terms.size() < 2) {
      job.routed = true;
      return;
    }
    int[] w = window(job, 0);
    Search s = new Search(w);
    java.util.Set<Integer> tree = new java.util.HashSet<>();
    for (int n : job.terms.get(0)) {
      tree.add(n);
    }
    boolean[] done = new boolean[job.terms.size()];
    done[0] = true;
    boolean ok = true;
    for (int k = 1; k < job.terms.size(); k++) {
      // next: the terminal nearest the tree
      int best = -1;
      long bestD = Long.MAX_VALUE;
      for (int t = 0; t < job.terms.size(); t++) {
        if (done[t]) {
          continue;
        }
        long d = Long.MAX_VALUE;
        int probe = job.terms.get(t)[0];
        for (int n : tree) {
          d = Math.min(d, Math.abs(iOf(n) - iOf(probe)) * (long) xs + Math.abs(jOf(n) - jOf(probe)) * (long) ys);
        }
        if (d < bestD) {
          bestD = d;
          best = t;
        }
      }
      done[best] = true;
      int[] path = s.find(job, tree, job.terms.get(best));
      if (path == null) {
        ok = false;
        continue;
      }
      job.paths.add(path);
      for (int n : path) {
        tree.add(n);
      }
      for (int a = 1; a < path.length; a++) {
        if (layerOf(path[a]) != layerOf(path[a - 1])) {
          s.ownVias.add(path[a]);
          s.ownVias.add(path[a - 1]);
        }
      }
    }
    for (int[] p : job.paths) {
      for (int n : p) {
        job.nodes.add(n);
      }
    }
    // single-node terminals touched only through the tree count as used too
    claim(job);
    job.routed = ok;
  }

  boolean isViaNode(NetJob job, int n) {
    int l = layerOf(n);
    int i = iOf(n);
    int j = jOf(n);
    if (l == 0 && job.accessNodes.contains(n)) {
      return true;
    }
    return (l + 1 < nl && job.nodes.contains(node(l + 1, i, j))) || (l > 0 && job.nodes.contains(node(l - 1, i, j)));
  }

  /** Nodes along the track within the halo of node n on its layer. */
  void haloOf(int n, int radius, java.util.function.IntConsumer out) {
    int l = layerOf(n);
    int i = iOf(n);
    int j = jOf(n);
    boolean h = layers.get(l).horizontal;
    for (int k = -radius; k <= radius; k++) {
      if (k == 0) {
        continue;
      }
      int a = h ? i + k : i;
      int b = h ? j : j + k;
      if (a < 0 || b < 0 || a >= nx || b >= ny) {
        continue;
      }
      out.accept(node(l, a, b));
    }
  }

  /** Nodes on the neighbouring tracks whose via pads would be too close to a pad at n. */
  void crossOf(int n, java.util.function.IntConsumer out) {
    int l = layerOf(n);
    int c = crossStep[l];
    if (c == 0) {
      return;
    }
    int i = iOf(n);
    int j = jOf(n);
    boolean h = layers.get(l).horizontal;
    for (int side = -1; side <= 1; side += 2) {
      for (int k = -crossAlong[l]; k <= crossAlong[l]; k++) {
        int a = h ? i + k : i + side * c;
        int b = h ? j + side * c : j + k;
        if (a >= 0 && b >= 0 && a < nx && b < ny) {
          out.accept(node(l, a, b));
        }
      }
    }
  }

  /** Every node of this net that carries a via pad. */
  java.util.Set<Integer> viaNodes(NetJob job) {
    java.util.Set<Integer> v = new java.util.HashSet<>();
    for (int n : job.nodes) {
      if (isViaNode(job, n)) {
        v.add(n);
      }
    }
    return v;
  }

  void claim(NetJob job) {
    job.claims = new java.util.HashSet<>();
    for (int n : job.nodes) {
      pathUse[n]++;
    }
    job.vias = viaNodes(job);
    for (int n : job.vias) {
      viaUse[n]++;
    }
    for (int n : job.nodes) {
      int r = isViaNode(job, n) ? haloVia[layerOf(n)] : haloWire[layerOf(n)];
      int[] shift = job.accessShift.get(n);
      if (shift != null) {
        r += 1; // the nudged pad sits up to MAX_NUDGE_ALONG closer to one neighbour
      }
      haloOf(n, r, h -> {
        if (!job.nodes.contains(h) && job.claims.add(h)) {
          claimUse[h]++;
        }
      });
    }
  }

  void unclaim(NetJob job) {
    for (int n : job.nodes) {
      pathUse[n]--;
    }
    for (int h : job.claims) {
      claimUse[h]--;
    }
    for (int n : job.vias) {
      viaUse[n]--;
    }
    job.claims = new java.util.HashSet<>();
    job.vias = new java.util.HashSet<>();
  }

  /** A* search inside one window, with arrays local to the window. */
  final class Search {
    final int x1;
    final int y1;
    final int wnx;
    final int wny;
    final float[] dist;
    final int[] prev;
    final int[] stamp;
    int gen = 0;

    Search(int[] w) {
      x1 = w[0];
      y1 = w[1];
      wnx = w[2] - w[0] + 1;
      wny = w[3] - w[1] + 1;
      int size = nl * wnx * wny;
      dist = new float[size];
      prev = new int[size];
      stamp = new int[size];
    }

    int local(int n) {
      int i = iOf(n) - x1;
      int j = jOf(n) - y1;
      if (i < 0 || j < 0 || i >= wnx || j >= wny) {
        return -1;
      }
      return (layerOf(n) * wny + j) * wnx + i;
    }

    int global(int k) {
      int i = k % wnx;
      int j = (k / wnx) % wny;
      int l = k / (wnx * wny);
      return node(l, i + x1, j + y1);
    }

    java.util.Set<Integer> ownVias = new java.util.HashSet<>();

    float crossCost(int n) {
      int[] c = {0};
      crossOf(n, m -> c[0] += viaUse[m] + (ownVias.contains(m) ? 1 : 0));
      return c[0];
    }

    float cost(NetJob job, int n, boolean via) {
      int o = owner[n];
      if (o == -2 || (o >= 0 && o != job.id)) {
        return Float.POSITIVE_INFINITY;
      }
      int share = pathUse[n] + claimUse[n];
      // nodes this net's own wire would crowd: other nets' wires within its halo
      int r = via ? haloVia[layerOf(n)] : haloWire[layerOf(n)];
      int[] near = {0};
      haloOf(n, r, h -> near[0] += pathUse[h]);
      share += near[0];
      float base = layers.get(layerOf(n)).horizontal ? xs : ys;
      return (base + hist[n] * base) * (1 + presFac * share);
    }

    int[] find(NetJob job, java.util.Set<Integer> tree, int[] targets) {
      gen++;
      java.util.Set<Integer> goal = new java.util.HashSet<>();
      int tx1 = Integer.MAX_VALUE;
      int ty1 = Integer.MAX_VALUE;
      int tx2 = -1;
      int ty2 = -1;
      for (int t : targets) {
        goal.add(t);
        tx1 = Math.min(tx1, iOf(t));
        tx2 = Math.max(tx2, iOf(t));
        ty1 = Math.min(ty1, jOf(t));
        ty2 = Math.max(ty2, jOf(t));
      }
      final int gx1 = tx1;
      final int gx2 = tx2;
      final int gy1 = ty1;
      final int gy2 = ty2;
      PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
      for (int n : tree) {
        int k = local(n);
        if (k < 0) {
          continue;
        }
        stamp[k] = gen;
        dist[k] = 0;
        prev[k] = -1;
        open.add(new double[] {h(n, gx1, gy1, gx2, gy2), k});
      }
      float viaCost = 4f * Math.max(xs, ys);
      while (!open.isEmpty()) {
        double[] top = open.poll();
        int k = (int) top[1];
        int n = global(k);
        float d = dist[k];
        if (top[0] - h(n, gx1, gy1, gx2, gy2) > d + 0.01f) {
          continue;
        }
        if (goal.contains(n)) {
          List<Integer> path = new ArrayList<>();
          for (int c = k; ; c = prev[c]) {
            path.add(global(c));
            if (prev[c] < 0) {
              break;
            }
          }
          int[] out = new int[path.size()];
          for (int a = 0; a < out.length; a++) {
            out[a] = path.get(out.length - 1 - a);
          }
          return out;
        }
        int l = layerOf(n);
        int i = iOf(n);
        int j = jOf(n);
        boolean hz = layers.get(l).horizontal;
        int[][] steps = hz ? new int[][] {{l, i + 1, j}, {l, i - 1, j}} : new int[][] {{l, i, j + 1}, {l, i, j - 1}};
        for (int[] st : steps) {
          relax(job, open, k, d, st[0], st[1], st[2], 0f, false, gx1, gy1, gx2, gy2);
        }
        if (l + 1 < nl && viaName[l] != null) {
          relax(job, open, k, d, l + 1, i, j, viaCost, true, gx1, gy1, gx2, gy2);
        }
        if (l > 0 && viaName[l - 1] != null) {
          relax(job, open, k, d, l - 1, i, j, viaCost, true, gx1, gy1, gx2, gy2);
        }
      }
      return null;
    }

    void relax(NetJob job, PriorityQueue<double[]> open, int from, float d, int l, int i, int j, float extra,
        boolean via, int gx1, int gy1, int gx2, int gy2) {
      if (i < x1 || j < y1 || i >= x1 + wnx || j >= y1 + wny) {
        return;
      }
      int n = node(l, i, j);
      float c = cost(job, n, via);
      if (Float.isInfinite(c)) {
        return;
      }
      if (via) {
        float base = Math.max(xs, ys);
        c += presFac * 4 * base * (crossCost(n) + crossCost(global(from)));
      }
      int k = local(n);
      float nd = d + c + extra;
      if (stamp[k] == gen && dist[k] <= nd) {
        return;
      }
      stamp[k] = gen;
      dist[k] = nd;
      prev[k] = from;
      open.add(new double[] {nd + h(n, gx1, gy1, gx2, gy2), k});
    }

    float h(int n, int gx1, int gy1, int gx2, int gy2) {
      int i = iOf(n);
      int j = jOf(n);
      int dx = i < gx1 ? gx1 - i : (i > gx2 ? i - gx2 : 0);
      int dy = j < gy1 ? gy1 - j : (j > gy2 ? j - gy2 : 0);
      return dx * (float) xs + dy * (float) ys;
    }
  }

  // ---------------------------------------------------------------- output

  /** DEF "+ ROUTED" text for each net. */
  public Map<String, String> routing() {
    Map<String, String> out = new LinkedHashMap<>();
    for (NetJob job : jobs) {
      if (job.paths.isEmpty()) {
        continue;
      }
      List<String> parts = new ArrayList<>();
      java.util.Set<String> seenVias = new java.util.HashSet<>();
      for (int[] p : job.paths) {
        int a = 0;
        while (a < p.length) {
          int b = a;
          while (b + 1 < p.length && layerOf(p[b + 1]) == layerOf(p[a])) {
            b++;
          }
          int l = layerOf(p[a]);
          String ln = layers.get(l).name;
          if (b > a) {
            boolean hz = layers.get(l).horizontal;
            int sx = xOf(iOf(p[a]));
            int sy = yOf(jOf(p[a]));
            int ex = xOf(iOf(p[b]));
            int ey = yOf(jOf(p[b]));
            parts.add(ln + " ( " + sx + " " + sy + " ) ( " + (hz ? String.valueOf(ex) : "*") + " " + (hz ? "*" : String.valueOf(ey)) + " )");
          }
          if (b + 1 < p.length) {
            int l2 = layerOf(p[b + 1]);
            int lo = Math.min(l, l2);
            int x = xOf(iOf(p[b]));
            int y = yOf(jOf(p[b]));
            String key = lo + ":" + x + ":" + y;
            if (seenVias.add(key)) {
              parts.add(layers.get(lo).name + " ( " + x + " " + y + " ) " + viaName[lo]);
            }
          }
          a = b + 1;
        }
      }
      // one li1 via per pin: further access points the route crosses are already joined by met1
      java.util.Set<Integer> viaAt = new java.util.LinkedHashSet<>();
      for (int[] term : job.terms) {
        for (int n : term) {
          if (job.accessNodes.contains(n) && job.nodes.contains(n)) {
            viaAt.add(n);
            break;
          }
        }
      }
      for (int n : job.accessNodes) {
        if (viaAt.contains(n)) {
          int x = xOf(iOf(n));
          int y = yOf(jOf(n));
          int[] d = job.accessShift.get(n);
          if (d == null) {
            parts.add("li1 ( " + x + " " + y + " ) " + accessVia);
          } else {
            parts.add("li1 ( " + (x + d[0]) + " " + (y + d[1]) + " ) " + accessVia);
            int hw = dbu(layers.get(0).width / 2);
            parts.add(layers.get(0).name + " ( " + x + " " + y + " ) RECT ( " + Math.min(0, d[0]) + " " + (Math.min(0, d[1]) - hw)
                + " " + Math.max(0, d[0]) + " " + (Math.max(0, d[1]) + hw) + " )");
          }
        }
      }
      parts.addAll(patches(job, viaAt));
      StringBuilder sb = new StringBuilder();
      for (int k = 0; k < parts.size(); k++) {
        sb.append(k == 0 ? "  + ROUTED " : "\n    NEW ").append(parts.get(k));
      }
      out.put(job.name, sb.toString());
    }
    return out;
  }

  /**
   * Metal of this net at node n along its track: {start, end, half size across}, absolute dbu,
   * including any via pad (and a nudged li1 via's real position).
   */
  int[] padAt(NetJob job, int n, java.util.Set<Integer> liVias) {
    int l = layerOf(n);
    Lef.Layer L = layers.get(l);
    int hw = dbu(L.width / 2);
    int c = L.horizontal ? xOf(iOf(n)) : yOf(jOf(n));
    int lo = c - hw;
    int hi = c + hw;
    int across = hw;
    List<String> vs = new ArrayList<>();
    if (l > 0 && job.nodes.contains(node(l - 1, iOf(n), jOf(n)))) {
      vs.add(viaName[l - 1]);
    }
    if (l + 1 < nl && job.nodes.contains(node(l + 1, iOf(n), jOf(n)))) {
      vs.add(viaName[l]);
    }
    if (l == 0 && liVias.contains(n)) {
      vs.add(accessVia);
    }
    for (String vn : vs) {
      int shift = 0;
      if (vn.equals(accessVia) && job.accessShift.containsKey(n)) {
        int[] d = job.accessShift.get(n);
        shift = L.horizontal ? d[0] : d[1];
      }
      for (Lef.Rect r : lef.vias.get(vn).rects) {
        if (!r.layer().equals(L.name)) {
          continue;
        }
        lo = Math.min(lo, c + shift + dbu(L.horizontal ? r.x1() : r.y1()));
        hi = Math.max(hi, c + shift + dbu(L.horizontal ? r.x2() : r.y2()));
        across = Math.max(across, dbu(L.horizontal ? Math.max(-r.y1(), r.y2()) : Math.max(-r.x1(), r.x2())));
      }
    }
    return new int[] {lo, hi, across};
  }

  String rectAt(String layer, int ox, int oy, int x1, int y1, int x2, int y2) {
    return layer + " ( " + ox + " " + oy + " ) RECT ( " + (x1 - ox) + " " + (y1 - oy) + " " + (x2 - ox) + " " + (y2 - oy) + " )";
  }

  /**
   * Same-net metal closer than the spacing rule still counts as a violation (a notch, or a gap
   * between two branches). Wherever two pieces of one net end up too close, the gap is filled
   * with metal, never thinner than the layer's minimum width and never wider than the pieces.
   */
  List<String> patches(NetJob job, java.util.Set<Integer> liVias) {
    List<String> out = new ArrayList<>();
    java.util.Set<Long> edges = new java.util.HashSet<>();
    for (int[] p : job.paths) {
      for (int a = 1; a < p.length; a++) {
        if (layerOf(p[a]) == layerOf(p[a - 1])) {
          edges.add(Math.min(p[a], p[a - 1]) * (long) Integer.MAX_VALUE + Math.max(p[a], p[a - 1]));
        }
      }
    }
    Map<Long, List<Integer>> tracks = new HashMap<>();
    for (int n : job.nodes) {
      int l = layerOf(n);
      boolean h = layers.get(l).horizontal;
      long key = (long) l * 1_000_000L + (h ? jOf(n) : iOf(n));
      tracks.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
    }
    Map<Integer, int[]> pad = new HashMap<>();
    for (int n : job.nodes) {
      pad.put(n, padAt(job, n, liVias));
    }
    for (List<Integer> t : tracks.values()) {
      int l = layerOf(t.get(0));
      Lef.Layer L = layers.get(l);
      boolean h = L.horizontal;
      t.sort((p, q) -> Integer.compare(h ? iOf(p) : jOf(p), h ? iOf(q) : jOf(q)));
      int step = h ? xs : ys;
      int hw = dbu(L.width / 2);
      int sp = dbu(L.spacing);
      // 1. neighbouring pieces on the track: unconnected and too close -> join them
      for (int k = 1; k < t.size(); k++) {
        int a = t.get(k - 1);
        int b = t.get(k);
        int len = ((h ? iOf(b) : jOf(b)) - (h ? iOf(a) : jOf(a))) * step;
        int[] pa = pad.get(a);
        int[] pb = pad.get(b);
        int gap = pb[0] - pa[1];
        boolean joined = len == step && edges.contains(Math.min(a, b) * (long) Integer.MAX_VALUE + Math.max(a, b));
        if (!joined && gap < sp) {
          int across = Math.max(hw, Math.min(pa[2], pb[2]));
          int ax = xOf(iOf(a));
          int ay = yOf(jOf(a));
          int c = h ? ay : ax;
          int from = h ? ax : ay;
          int to = from + len;
          out.add(h ? rectAt(L.name, ax, ay, from, c - across, to, c + across)
                    : rectAt(L.name, ax, ay, c - across, from, c + across, to));
        }
      }
      // 2. two pads with only narrower wire between them leave a notch -> fill it
      int prev = -1;
      for (int n : t) {
        if (pad.get(n)[2] <= hw) {
          continue;
        }
        if (prev >= 0) {
          int[] pa = pad.get(prev);
          int[] pb = pad.get(n);
          int gap = pb[0] - pa[1];
          if (gap > 0 && gap < sp) {
            int across = Math.min(pa[2], pb[2]);
            int ax = xOf(iOf(prev));
            int ay = yOf(jOf(prev));
            int c = h ? ay : ax;
            out.add(h ? rectAt(L.name, ax, ay, pa[1], c - across, pb[0], c + across)
                      : rectAt(L.name, ax, ay, c - across, pa[1], c + across, pb[0]));
          }
        }
        prev = n;
      }
      // 3. own pads on the neighbouring track -> join them where they overlap along the track
      int minW = dbu(L.width);
      for (int n : t) {
        if (pad.get(n)[2] <= hw) {
          continue;
        }
        crossOf(n, m -> {
          if (m < n || !pad.containsKey(m) || pad.get(m)[2] <= hw) {
            return;
          }
          int[] pa = pad.get(n);
          int[] pb = pad.get(m);
          int lo = Math.max(pa[0], pb[0]);
          int hi = Math.min(pa[1], pb[1]);
          if (hi - lo < minW) {
            int mid = (lo + hi) / 2 / 5 * 5;
            lo = mid - minW / 2;
            hi = lo + minW;
          }
          int ca = h ? yOf(jOf(n)) : xOf(iOf(n));
          int cb = h ? yOf(jOf(m)) : xOf(iOf(m));
          int c1 = Math.min(ca, cb);
          int c2 = Math.max(ca, cb);
          int ox = xOf(iOf(n));
          int oy = yOf(jOf(n));
          out.add(h ? rectAt(L.name, ox, oy, lo, c1, hi, c2) : rectAt(L.name, ox, oy, c1, lo, c2, hi));
        });
      }
    }
    return out;
  }

  /** Total wire length (microns) and via count of the result. */
  public String summary() {
    long len = 0;
    int vias = 0;
    for (NetJob job : jobs) {
      for (int[] p : job.paths) {
        for (int a = 1; a < p.length; a++) {
          if (layerOf(p[a]) == layerOf(p[a - 1])) {
            len += layers.get(layerOf(p[a])).horizontal ? xs : ys;
          } else {
            vias++;
          }
        }
      }
      // one li1 via per pin: further access points the route crosses are already joined by met1
      java.util.Set<Integer> viaAt = new java.util.LinkedHashSet<>();
      for (int[] term : job.terms) {
        for (int n : term) {
          if (job.accessNodes.contains(n) && job.nodes.contains(n)) {
            viaAt.add(n);
            break;
          }
        }
      }
      for (int n : job.accessNodes) {
        if (viaAt.contains(n)) {
          vias++;
        }
      }
    }
    return "wire " + (len / (double) def.dbu) + " um, " + vias + " vias";
  }

  /**
   * Command line: java -cp embryo-router.jar app.freerouting.chip.ChipRouter tech.lef cells.lef in.def
   * out.def [cores, 0 = all] [top layer, default met4] [max rounds].
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      System.out.println("usage: ChipRouter tech.lef cells.lef in.def out.def [cores (0 = all)] [top layer] [rounds]");
      return;
    }
    int cores = Runtime.getRuntime().availableProcessors();
    int use = args.length > 4 ? Integer.parseInt(args[4]) : cores;
    if (use <= 0 || use > cores) {
      use = cores;
    }
    String top = args.length > 5 ? args[5] : "met4";
    int rounds = args.length > 6 ? Integer.parseInt(args[6]) : 60;
    System.out.println("Embryo Chip Router: this computer has " + cores + " cores; using " + use + ".");
    Lef lef = new Lef().read(Path.of(args[0])).read(Path.of(args[1]));
    Def def = Def.read(Path.of(args[2]));
    ChipRouter r = new ChipRouter(lef, def, top, use);
    System.out.println("Grid " + r.nx + " x " + r.ny + " x " + r.nl + " layers (" + r.xs + " x " + r.ys + " dbu), halos wire "
        + Arrays.toString(r.haloWire) + " via " + Arrays.toString(r.haloVia) + ", vias " + Arrays.toString(r.viaName)
        + " + " + r.accessVia);
    r.prepare();
    System.out.println(def.components.size() + " cells, " + r.jobs.size() + " nets");
    System.out.println(r.route(rounds));
    System.out.println(r.summary());
    def.write(Path.of(args[3]), r.routing());
    System.out.println("wrote " + args[3]);
  }
}
