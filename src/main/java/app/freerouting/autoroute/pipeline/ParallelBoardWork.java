package app.freerouting.autoroute.pipeline;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.library.Padstack;
import app.freerouting.datastructures.Stoppable;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.logger.FRLogger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Embryo Router: runs independent routing work on many cores.
 *
 * <p>The board is cut into square tiles coloured like a 2x2 checkerboard, so two tiles of the
 * same colour never touch. For each colour, every worker thread takes its own copy of the board
 * and does the work of some of that colour's tiles on it. The new copper of each tile is then
 * copied onto the real board one tile at a time, on the calling thread, in a fixed order. A tile
 * whose copper would add a clearance violation, or that removed something that is no longer
 * there, is undone and left for the normal single-thread passes, so the result never depends on
 * thread timing and nothing unsafe is kept.
 */
final class ParallelBoardWork {

  /** One unit of work done on a worker's copy of the board, e.g. fanning out one pin. */
  interface Task {
    void run(RoutingBoard copy);
  }

  /** Counts reported after a run. */
  static final class Stats {
    int tiles;
    int tilesMerged;
    int tilesRejected;
    int itemsAdded;
    long millis;

    @Override
    public String toString() {
      return tiles
          + " areas, "
          + tilesMerged
          + " merged, "
          + tilesRejected
          + " left for the single-thread pass, "
          + itemsAdded
          + " new traces/vias, "
          + "%.1f".formatted(millis / 1000.0)
          + " s";
    }
  }

  private record TileDiff(
      List<Integer> removedIds,
      List<Item> added,
      Map<Integer, Integer> copyViolations,
      Set<Integer> goneInPhase) {}

  private final RoutingBoard board;
  private final int threads;
  private final Stoppable stop;
  private final int tileSize;
  private final IntBox box;
  /** colour -> tile key -> tasks (insertion order kept per tile). */
  private final List<TreeMap<Long, List<Task>>> colours = new ArrayList<>();
  /** Items of the board as it was when this colour started that an earlier merge removed. */
  private final Set<Integer> goneThisPhase = new HashSet<>();

  ParallelBoardWork(RoutingBoard board, int threads, Stoppable stop, double tileMm) {
    this.board = board;
    this.threads = Math.max(1, threads);
    this.stop = stop;
    this.box = board.boundingBox;
    this.tileSize = Math.max(1, (int) Math.round(mmToBoard(board, tileMm)));
    for (int c = 0; c < 4; c++) {
      colours.add(new TreeMap<>());
    }
  }

  /** Board units per millimetre for this board. */
  static double mmToBoard(RoutingBoard board, double mm) {
    return mm * board.communication.getResolution(app.freerouting.board.model.structure.Unit.MM);
  }

  /** Adds work located at board position (x, y). */
  void add(double x, double y, Task task) {
    long gx = (long) Math.floor((x - box.ll.x) / tileSize);
    long gy = (long) Math.floor((y - box.ll.y) / tileSize);
    int colour = (int) ((gx & 1) + 2 * (gy & 1));
    long key = (gy << 32) | (gx & 0xffffffffL);
    colours.get(colour).computeIfAbsent(key, k -> new ArrayList<>()).add(task);
  }

  Stats run() {
    Stats stats = new Stats();
    long start = System.currentTimeMillis();
    ExecutorService pool =
        Executors.newFixedThreadPool(
            threads,
            r -> {
              Thread t = new Thread(r, "embryo-parallel-worker");
              t.setDaemon(true);
              return t;
            });
    try {
      for (TreeMap<Long, List<Task>> tiles : colours) {
        if (tiles.isEmpty()) {
          continue;
        }
        if (stop != null && stop.isStopRequested()) {
          break;
        }
        stats.tiles += tiles.size();
        runColour(new ArrayList<>(tiles.values()), pool, stats);
      }
    } finally {
      pool.shutdownNow();
    }
    stats.millis = System.currentTimeMillis() - start;
    return stats;
  }

  private void runColour(List<List<Task>> tiles, ExecutorService pool, Stats stats) {
    int workers = Math.min(threads, tiles.size());
    // Round-robin keeps neighbouring (busy) tiles on different workers.
    List<List<List<Task>>> share = new ArrayList<>();
    for (int w = 0; w < workers; w++) {
      share.add(new ArrayList<>());
    }
    for (int i = 0; i < tiles.size(); i++) {
      share.get(i % workers).add(tiles.get(i));
    }
    final int baseMaxId = board.communication.idGenerator.maxGeneratedId();
    goneThisPhase.clear();
    List<RoutingBoard> copies = new ArrayList<>();
    for (int w = 0; w < workers; w++) {
      copies.add(board.deepCopy());
    }
    List<Future<List<TileDiff>>> futures = new ArrayList<>();
    for (int w = 0; w < workers; w++) {
      final RoutingBoard copy = copies.get(w);
      final List<List<Task>> mine = share.get(w);
      futures.add(pool.submit(() -> work(copy, mine)));
    }
    for (int w = 0; w < workers; w++) {
      List<TileDiff> diffs;
      try {
        diffs = futures.get(w).get();
      } catch (Exception e) {
        FRLogger.error("Embryo Router: a parallel worker failed; its areas go to the normal pass", e);
        stats.tilesRejected += share.get(w).size();
        continue;
      }
      Map<Integer, Integer> idMap = new HashMap<>();
      for (TileDiff diff : diffs) {
        diff.goneInPhase().addAll(goneThisPhase);
        if (diff.removedIds().isEmpty() && diff.added().isEmpty()) {
          continue;
        }
        if (merge(diff, baseMaxId, idMap, stats)) {
          stats.tilesMerged++;
        } else {
          stats.tilesRejected++;
        }
      }
    }
  }

  private List<TileDiff> work(RoutingBoard copy, List<List<Task>> tiles) {
    List<TileDiff> diffs = new ArrayList<>();
    for (List<Task> tile : tiles) {
      if (stop != null && stop.isStopRequested()) {
        break;
      }
      Set<Integer> before = new HashSet<>();
      for (Item it : copy.getItems()) {
        before.add(it.getId());
      }
      for (Task task : tile) {
        if (stop != null && stop.isStopRequested()) {
          break;
        }
        task.run(copy);
      }
      Set<Integer> after = new HashSet<>();
      List<Item> added = new ArrayList<>();
      Map<Integer, Integer> violations = new HashMap<>();
      for (Item it : copy.getItems()) {
        after.add(it.getId());
        if (!before.contains(it.getId())) {
          added.add(it);
          violations.put(it.getId(), it.clearanceViolationCount());
        }
      }
      List<Integer> removed = new ArrayList<>();
      for (Integer id : before) {
        if (!after.contains(id)) {
          removed.add(id);
        }
      }
      removed.sort(null);
      added.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
      diffs.add(new TileDiff(removed, added, violations, new HashSet<>()));
    }
    return diffs;
  }

  /** Copies one tile's changes onto the real board; undoes them and returns false on conflict. */
  private boolean merge(TileDiff diff, int baseMaxId, Map<Integer, Integer> idMap, Stats stats) {
    List<Item> toRemove = new ArrayList<>();
    List<Integer> alreadyGone = new ArrayList<>();
    for (Integer copyId : diff.removedIds()) {
      if (!idMap.containsKey(copyId) && diff.goneInPhase().contains(copyId)) {
        // An earlier area of this round removed the same item (e.g. a tail cleaned up on its
        // net); this area wanted it gone too.
        alreadyGone.add(copyId);
        continue;
      }
      Integer realId = idMap.containsKey(copyId) ? idMap.get(copyId) : (copyId <= baseMaxId ? copyId : null);
      Item real = realId == null ? null : board.getItem(realId);
      if (real == null || real.isUserFixed() || real.isDeletionForbidden()) {
        reject("removed item " + copyId + (real == null ? " is gone" : " is fixed"));
        return false;
      }
      toRemove.add(real);
    }
    for (Item a : diff.added()) {
      if (!(a instanceof PolylineTrace) && !(a instanceof Via)) {
        reject("new item type " + a.getClass().getSimpleName());
        return false;
      }
    }
    board.generateSnapshot();
    boolean ok = true;
    Map<Integer, Integer> newIds = new HashMap<>();
    List<Item> inserted = new ArrayList<>();
    try {
      for (Item real : toRemove) {
        board.removeItem(real);
      }
      for (Item a : diff.added()) {
        Item n = transplant(a);
        if (n == null) {
          reject("could not copy " + a.getClass().getSimpleName());
          ok = false;
          break;
        }
        newIds.put(a.getId(), n.getId());
        inserted.add(n);
      }
      if (ok) {
        for (int i = 0; i < inserted.size(); i++) {
          Item a = diff.added().get(i);
          int v = inserted.get(i).clearanceViolationCount();
          if (v > diff.copyViolations().getOrDefault(a.getId(), 0)) {
            reject(a.getClass().getSimpleName() + " has " + v + " clearance violations, "
                + diff.copyViolations().getOrDefault(a.getId(), 0) + " on the copy");
            ok = false;
            break;
          }
        }
      }
    } catch (Exception e) {
      FRLogger.debug("Embryo Router: merge failed: " + e);
      ok = false;
    }
    if (ok) {
      board.popSnapshot();
      for (Integer copyId : diff.removedIds()) {
        Integer realId = idMap.remove(copyId);
        if (realId == null && copyId <= baseMaxId) {
          goneThisPhase.add(copyId);
        }
      }
      idMap.putAll(newIds);
      stats.itemsAdded += inserted.size();
      return true;
    }
    board.undo(null);
    return false;
  }

  private static void reject(String why) {
    FRLogger.info("Embryo Router: area left for the single-thread pass: " + why);
  }

  private Item transplant(Item a) {
    if (a instanceof PolylineTrace t) {
      return board.insertTraceWithoutCleaning(
          t.polyline(),
          t.getLayer(),
          t.getHalfWidth(),
          t.netNumbers.clone(),
          t.clearanceClassIndex(),
          t.getFixedState());
    }
    if (a instanceof Via v) {
      Padstack p = board.library.padstacks.get(v.getPadstack().name);
      if (p == null) {
        return null;
      }
      Via n =
          new Via(
              p,
              v.getCenter(),
              v.netNumbers.clone(),
              v.clearanceClassIndex(),
              0,
              v.getComponentId(),
              v.getFixedState(),
              v.attachAllowed,
              board);
      n.isEscapeVia = v.isEscapeVia;
      n.escapeViaSmdLayer = v.escapeViaSmdLayer;
      board.insertItem(n);
      return n;
    }
    return null;
  }
}
