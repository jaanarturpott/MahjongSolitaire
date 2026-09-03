import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mahjong Solitaire backend.
 *
 * Serves the static frontend from ./public and exposes a small JSON REST API
 * under /api/* that owns all game logic (board layout, solvable dealing,
 * free-tile rules, matching, undo, shuffle, win/deadlock detection).
 *
 * No external dependencies - pure JDK (uses the built-in HttpServer).
 *
 * Run:
 *   javac MahjongServer.java
 *   java MahjongServer [port]
 */
public class MahjongServer {

    // ---------------------------------------------------------------------
    // Board layout
    // ---------------------------------------------------------------------

    /** A fixed physical slot on the board. Index == tile id throughout a game. */
    static final class Slot {
        final int level, col, row;
        Slot(int level, int col, int row) { this.level = level; this.col = col; this.row = row; }
    }

    /** Builds the 144 physical slots (4 stacked "turtle-ish" tiers). */
    static List<Slot> buildLayout() {
        List<Slot> slots = new ArrayList<>();

        // Level 0: 12 x 8 grid with the four 2x2 corners notched out (80 tiles)
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 12; col++) {
                boolean topLeft = col < 2 && row < 2;
                boolean topRight = col >= 10 && row < 2;
                boolean botLeft = col < 2 && row >= 6;
                boolean botRight = col >= 10 && row >= 6;
                if (topLeft || topRight || botLeft || botRight) continue;
                slots.add(new Slot(0, col, row));
            }
        }
        // Level 1: 8 x 6, centered (cols 2-9, rows 1-6) -> 48 tiles
        for (int row = 1; row < 7; row++) {
            for (int col = 2; col < 10; col++) {
                slots.add(new Slot(1, col, row));
            }
        }
        // Level 2: 4 x 3, centered (cols 4-7, rows 2-4) -> 12 tiles
        for (int row = 2; row < 5; row++) {
            for (int col = 4; col < 8; col++) {
                slots.add(new Slot(2, col, row));
            }
        }
        // Level 3: 2 x 2, centered (cols 5-6, rows 3-4) -> 4 tiles
        for (int row = 3; row < 5; row++) {
            for (int col = 5; col < 7; col++) {
                slots.add(new Slot(3, col, row));
            }
        }
        return slots; // 80 + 48 + 12 + 4 = 144
    }

    static final List<Slot> LAYOUT = Collections.unmodifiableList(buildLayout());

    // ---------------------------------------------------------------------
    // Tile types
    // ---------------------------------------------------------------------

    // 0-33 : 34 "regular" suits/honors, 4 physical copies each -> 136 tiles
    // 34-37 : Flowers (wildcard group - any flower matches any flower)
    // 38-41 : Seasons (wildcard group - any season matches any season)
    static final int REGULAR_TYPES = 34;
    static final int FLOWER_BASE = 34;
    static final int SEASON_BASE = 38;

    /** Human label for a type id, used by the client to draw the tile face. */
    static String typeLabel(int type) {
        // Characters (Wan) 1-9 -> ids 0-8
        if (type >= 0 && type <= 8) return "W" + (type + 1);
        // Bamboo (Suo) 1-9 -> ids 9-17
        if (type >= 9 && type <= 17) return "B" + (type - 9 + 1);
        // Circles (Tong) 1-9 -> ids 18-26
        if (type >= 18 && type <= 26) return "C" + (type - 18 + 1);
        // Winds -> ids 27-30 (East South West North)
        if (type >= 27 && type <= 30) {
            String[] w = {"WE", "WS", "WW", "WN"};
            return w[type - 27];
        }
        // Dragons -> ids 31-33 (Red Green White)
        if (type >= 31 && type <= 33) {
            String[] d = {"DR", "DG", "DW"};
            return d[type - 31];
        }
        if (type >= FLOWER_BASE && type < SEASON_BASE) return "F" + (type - FLOWER_BASE + 1);
        if (type >= SEASON_BASE && type < SEASON_BASE + 4) return "S" + (type - SEASON_BASE + 1);
        return "?";
    }

    /** Group key used for match validation: regular tiles match by exact type, flowers/seasons match by group. */
    static String matchKey(int type) {
        if (type < REGULAR_TYPES) return "R" + type;
        if (type < SEASON_BASE) return "FLOWER";
        return "SEASON";
    }

    // ---------------------------------------------------------------------
    // Game state
    // ---------------------------------------------------------------------

    static final class Tile {
        final int id;
        int type;
        boolean removed = false;
        Tile(int id, int type) { this.id = id; this.type = type; }
    }

    static final class Game {
        final String id;
        final Tile[] tiles = new Tile[LAYOUT.size()];
        final Deque<int[]> history = new ArrayDeque<>(); // stack of {idA, idB} for undo
        int moves = 0;

        Game(String id) { this.id = id; }

        boolean isWon() {
            for (Tile t : tiles) if (!t.removed) return false;
            return true;
        }

        /** A tile is free if nothing sits above it, and at least one same-row neighbor slot is open. */
        boolean isFree(int idx) {
            Tile t = tiles[idx];
            if (t.removed) return false;
            Slot s = LAYOUT.get(idx);

            for (int j = 0; j < tiles.length; j++) {
                if (tiles[j].removed) continue;
                Slot o = LAYOUT.get(j);
                if (o.level > s.level && o.col == s.col && o.row == s.row) return false;
            }

            boolean openLeft = true, openRight = true;
            for (int j = 0; j < tiles.length; j++) {
                if (tiles[j].removed) continue;
                Slot o = LAYOUT.get(j);
                if (o.level == s.level && o.row == s.row) {
                    if (o.col == s.col - 1) openLeft = false;
                    if (o.col == s.col + 1) openRight = false;
                }
            }
            return openLeft || openRight;
        }

        boolean isDeadlocked() {
            Map<String, Integer> freeCounts = new HashMap<>();
            for (int i = 0; i < tiles.length; i++) {
                if (!tiles[i].removed && isFree(i)) {
                    String key = matchKey(tiles[i].type);
                    int c = freeCounts.getOrDefault(key, 0) + 1;
                    freeCounts.put(key, c);
                    if (c >= 2) return false;
                }
            }
            return true;
        }
    }

    static final Map<String, Game> GAMES = new ConcurrentHashMap<>();
    static final Random RNG = new Random();

    // ---------------------------------------------------------------------
    // Solvable dealing
    // ---------------------------------------------------------------------

    /**
     * Simulates peeling a board (defined by which slots are currently "remaining")
     * from top to bottom, always removing two free slots at a time, to produce a
     * valid removal order. Returns null if it dead-ends (fewer than 2 free slots
     * remain but slots are still left) - caller should retry with a fresh shuffle.
     */
    static List<int[]> buildRemovalOrder(boolean[] initialRemaining) {
        int n = initialRemaining.length;
        boolean[] remaining = initialRemaining.clone();
        int remainingCount = 0;
        for (boolean b : remaining) if (b) remainingCount++;

        List<int[]> pairs = new ArrayList<>();
        while (remainingCount > 0) {
            List<Integer> free = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!remaining[i]) continue;
                Slot s = LAYOUT.get(i);

                boolean covered = false;
                for (int j = 0; j < n; j++) {
                    if (!remaining[j]) continue;
                    Slot o = LAYOUT.get(j);
                    if (o.level > s.level && o.col == s.col && o.row == s.row) { covered = true; break; }
                }
                if (covered) continue;

                boolean openLeft = true, openRight = true;
                for (int j = 0; j < n; j++) {
                    if (!remaining[j]) continue;
                    Slot o = LAYOUT.get(j);
                    if (o.level == s.level && o.row == s.row) {
                        if (o.col == s.col - 1) openLeft = false;
                        if (o.col == s.col + 1) openRight = false;
                    }
                }
                if (openLeft || openRight) free.add(i);
            }

            if (free.size() < 2) return null; // dead end, caller will retry

            Collections.shuffle(free, RNG);
            int a = free.get(0), b = free.get(1);
            remaining[a] = false; remaining[b] = false;
            remainingCount -= 2;
            pairs.add(new int[]{a, b});
        }
        return pairs;
    }

    /** Same free-tile check as Game.isFree, but operating on a plain boolean[] "removed" array. */
    static boolean isFreeSim(int idx, boolean[] removed) {
        Slot s = LAYOUT.get(idx);
        for (int j = 0; j < removed.length; j++) {
            if (removed[j]) continue;
            Slot o = LAYOUT.get(j);
            if (o.level > s.level && o.col == s.col && o.row == s.row) return false;
        }
        boolean openLeft = true, openRight = true;
        for (int j = 0; j < removed.length; j++) {
            if (removed[j]) continue;
            Slot o = LAYOUT.get(j);
            if (o.level == s.level && o.row == s.row) {
                if (o.col == s.col - 1) openLeft = false;
                if (o.col == s.col + 1) openRight = false;
            }
        }
        return openLeft || openRight;
    }

    /**
     * Actually plays out {@code pairs} in order against {@code typeOf}, starting from
     * {@code initialRemoved}, checking at every step that both tiles are genuinely free
     * AND genuinely match. This is the real solvability proof - it doesn't trust the
     * construction step, it re-derives freeness independently and confirms the whole
     * sequence is playable. If this returns true, the board is guaranteed solvable
     * by following these exact pairs in order.
     */
    static boolean simulateSolveFrom(boolean[] initialRemoved, List<int[]> pairs, int[] typeOf) {
        boolean[] removed = initialRemoved.clone();
        for (int[] pair : pairs) {
            int a = pair[0], b = pair[1];
            if (!isFreeSim(a, removed) || !isFreeSim(b, removed)) return false;
            if (!matchKey(typeOf[a]).equals(matchKey(typeOf[b]))) return false;
            removed[a] = true;
            removed[b] = true;
        }
        return true;
    }

    /** Assigns the full 144-tile canonical deck of types onto a removal order, one pair-token per slot pair. */
    static int[] assignFullDeckTypes(List<int[]> removalPairs, int n) {
        List<String> pairTokens = new ArrayList<>();
        for (int t = 0; t < REGULAR_TYPES; t++) { pairTokens.add("R" + t); pairTokens.add("R" + t); }
        pairTokens.add("FLOWER"); pairTokens.add("FLOWER");
        pairTokens.add("SEASON"); pairTokens.add("SEASON");
        Collections.shuffle(pairTokens, RNG);

        List<Integer> flowerIds = new ArrayList<>(List.of(34, 35, 36, 37));
        Collections.shuffle(flowerIds, RNG);
        List<Integer> seasonIds = new ArrayList<>(List.of(38, 39, 40, 41));
        Collections.shuffle(seasonIds, RNG);

        int[] typeOf = new int[n];
        for (int i = 0; i < removalPairs.size(); i++) {
            int[] pair = removalPairs.get(i);
            String token = pairTokens.get(i);
            int typeA, typeB;
            if (token.startsWith("R")) {
                int t = Integer.parseInt(token.substring(1));
                typeA = t; typeB = t;
            } else if (token.equals("FLOWER")) {
                typeA = flowerIds.remove(flowerIds.size() - 1);
                typeB = flowerIds.remove(flowerIds.size() - 1);
            } else {
                typeA = seasonIds.remove(seasonIds.size() - 1);
                typeB = seasonIds.remove(seasonIds.size() - 1);
            }
            typeOf[pair[0]] = typeA;
            typeOf[pair[1]] = typeB;
        }
        return typeOf;
    }

    /**
     * Assigns the CURRENT set of remaining tile types (from an in-progress game) onto
     * a fresh removal order over the remaining slots. Used by Shuffle, so the tile
     * *contents* on the board don't change, only their positions.
     */
    static int[] assignRemainingTypes(Game g, List<int[]> removalPairs) {
        int n = g.tiles.length;
        Map<Integer, Integer> regularCounts = new HashMap<>(); // exact type -> remaining count (always even)
        List<Integer> flowerIds = new ArrayList<>();
        List<Integer> seasonIds = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (g.tiles[i].removed) continue;
            int type = g.tiles[i].type;
            if (type < REGULAR_TYPES) regularCounts.merge(type, 1, Integer::sum);
            else if (type < SEASON_BASE) flowerIds.add(type);
            else seasonIds.add(type);
        }
        Collections.shuffle(flowerIds, RNG);
        Collections.shuffle(seasonIds, RNG);

        List<String> pairTokens = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : regularCounts.entrySet()) {
            int pairsOfThisType = e.getValue() / 2; // guaranteed even: matches always remove 2 of the same type
            for (int k = 0; k < pairsOfThisType; k++) pairTokens.add("R" + e.getKey());
        }
        for (int k = 0; k < flowerIds.size() / 2; k++) pairTokens.add("FLOWER");
        for (int k = 0; k < seasonIds.size() / 2; k++) pairTokens.add("SEASON");
        Collections.shuffle(pairTokens, RNG);

        int[] typeOf = new int[n];
        for (int i = 0; i < removalPairs.size(); i++) {
            int[] pair = removalPairs.get(i);
            String token = pairTokens.get(i);
            int typeA, typeB;
            if (token.startsWith("R")) {
                int t = Integer.parseInt(token.substring(1));
                typeA = t; typeB = t;
            } else if (token.equals("FLOWER")) {
                typeA = flowerIds.remove(flowerIds.size() - 1);
                typeB = flowerIds.remove(flowerIds.size() - 1);
            } else {
                typeA = seasonIds.remove(seasonIds.size() - 1);
                typeB = seasonIds.remove(seasonIds.size() - 1);
            }
            typeOf[pair[0]] = typeA;
            typeOf[pair[1]] = typeB;
        }
        return typeOf;
    }

    /**
     * Generates a fresh 144-tile board and NEVER returns until the board has been
     * verified, end to end, to be solvable. Every candidate is actually simulated
     * (see simulateSolveFrom) before it's accepted - nothing ships unverified.
     */
    static Game newGame() {
        int n = LAYOUT.size();
        Game g = new Game(UUID.randomUUID().toString());

        boolean[] allTrue = new boolean[n];
        Arrays.fill(allTrue, true);
        boolean[] noneRemoved = new boolean[n]; // all false: nothing removed at game start

        int[] typeOf = null;
        final int MAX_ATTEMPTS = 2000;
        for (int attempt = 0; attempt < MAX_ATTEMPTS && typeOf == null; attempt++) {
            List<int[]> removalPairs = buildRemovalOrder(allTrue);
            if (removalPairs == null) continue; // peeling dead-ended, try a fresh random order

            int[] candidate = assignFullDeckTypes(removalPairs, n);
            if (simulateSolveFrom(noneRemoved, removalPairs, candidate)) {
                typeOf = candidate; // independently verified playable start to finish
            }
        }

        if (typeOf == null) {
            // Practically unreachable for this fixed layout, but we refuse to ever
            // hand out a board we haven't verified - fail loudly instead of silently
            // shipping something that might be unsolvable.
            throw new IllegalStateException(
                "Failed to generate a verified-solvable board after " + MAX_ATTEMPTS + " attempts");
        }

        for (int i = 0; i < n; i++) g.tiles[i] = new Tile(i, typeOf[i]);
        GAMES.put(g.id, g);
        return g;
    }

    // ---------------------------------------------------------------------
    // JSON helpers (hand-rolled - payloads are small & simple, no lib needed)
    // ---------------------------------------------------------------------

    static String boardJson(Game g) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"gameId\":\"").append(g.id).append("\",");
        sb.append("\"moves\":").append(g.moves).append(",");
        sb.append("\"won\":").append(g.isWon()).append(",");
        sb.append("\"deadlock\":").append(!g.isWon() && g.isDeadlocked()).append(",");
        sb.append("\"canUndo\":").append(!g.history.isEmpty()).append(",");
        sb.append("\"tiles\":[");
        for (int i = 0; i < g.tiles.length; i++) {
            if (i > 0) sb.append(",");
            Tile t = g.tiles[i];
            Slot s = LAYOUT.get(i);
            sb.append("{\"id\":").append(t.id)
              .append(",\"type\":").append(t.type)
              .append(",\"label\":\"").append(typeLabel(t.type)).append("\"")
              .append(",\"level\":").append(s.level)
              .append(",\"col\":").append(s.col)
              .append(",\"row\":").append(s.row)
              .append(",\"removed\":").append(t.removed)
              .append(",\"free\":").append(g.isFree(i))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static String err(String msg) {
        return "{\"error\":\"" + msg.replace("\"", "'") + "\"}";
    }

    static Pattern strField(String key) { return Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\""); }
    static Pattern numField(String key) { return Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)"); }

    static String extractString(String json, String key) {
        Matcher m = strField(key).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static Integer extractInt(String json, String key) {
        Matcher m = numField(key).matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---------------------------------------------------------------------
    // HTTP handlers
    // ---------------------------------------------------------------------

    static Game requireGame(HttpExchange ex, String gameId) throws IOException {
        Game g = gameId == null ? null : GAMES.get(gameId);
        if (g == null) {
            sendJson(ex, 404, err("Unknown or expired gameId"));
            return null;
        }
        return g;
    }

    static class NewGameHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendJson(ex, 405, err("POST required")); return; }
            Game g = newGame();
            sendJson(ex, 200, boardJson(g));
        }
    }

    static class StateHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String query = ex.getRequestURI().getQuery();
            String gameId = queryParam(query, "gameId");
            Game g = requireGame(ex, gameId);
            if (g == null) return;
            sendJson(ex, 200, boardJson(g));
        }
    }

    static class MatchHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendJson(ex, 405, err("POST required")); return; }
            String body = readBody(ex);
            String gameId = extractString(body, "gameId");
            Integer a = extractInt(body, "tileA");
            Integer b = extractInt(body, "tileB");
            Game g = requireGame(ex, gameId);
            if (g == null) return;

            if (a == null || b == null || a < 0 || b < 0 || a >= g.tiles.length || b >= g.tiles.length || a.equals(b)) {
                sendJson(ex, 400, err("Invalid tile ids")); return;
            }

            Tile ta = g.tiles[a], tb = g.tiles[b];
            if (ta.removed || tb.removed) { sendJson(ex, 200, boardJson(g)); return; }

            boolean free = g.isFree(a) && g.isFree(b);
            boolean match = matchKey(ta.type).equals(matchKey(tb.type));

            if (free && match) {
                ta.removed = true;
                tb.removed = true;
                g.history.push(new int[]{a, b});
                g.moves++;
            }
            sendJson(ex, 200, boardJson(g));
        }
    }

    static class UndoHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendJson(ex, 405, err("POST required")); return; }
            String body = readBody(ex);
            String gameId = extractString(body, "gameId");
            Game g = requireGame(ex, gameId);
            if (g == null) return;

            if (!g.history.isEmpty()) {
                int[] pair = g.history.pop();
                g.tiles[pair[0]].removed = false;
                g.tiles[pair[1]].removed = false;
                g.moves++;
            }
            sendJson(ex, 200, boardJson(g));
        }
    }

    static class ShuffleHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendJson(ex, 405, err("POST required")); return; }
            String body = readBody(ex);
            String gameId = extractString(body, "gameId");
            Game g = requireGame(ex, gameId);
            if (g == null) return;

            int n = g.tiles.length;
            boolean[] remaining = new boolean[n];
            boolean[] alreadyRemoved = new boolean[n];
            for (int i = 0; i < n; i++) {
                remaining[i] = !g.tiles[i].removed;
                alreadyRemoved[i] = g.tiles[i].removed;
            }

            // Re-deal the remaining tiles along a freshly verified solvable order,
            // instead of a blind random shuffle that could easily deadlock the board.
            int[] newTypeOf = null;
            final int MAX_ATTEMPTS = 500;
            for (int attempt = 0; attempt < MAX_ATTEMPTS && newTypeOf == null; attempt++) {
                List<int[]> removalPairs = buildRemovalOrder(remaining);
                if (removalPairs == null) continue;

                int[] candidate = assignRemainingTypes(g, removalPairs);
                if (simulateSolveFrom(alreadyRemoved, removalPairs, candidate)) {
                    newTypeOf = candidate;
                }
            }

            if (newTypeOf == null) {
                // Extremely unlikely for this layout, but never apply an unverified shuffle -
                // leave the board exactly as it was rather than risk a worse deadlock.
                sendJson(ex, 200, boardJson(g));
                return;
            }

            for (int i = 0; i < n; i++) {
                if (remaining[i]) g.tiles[i].type = newTypeOf[i];
            }
            g.history.clear(); // reshuffled tiles invalidate prior undo history
            sendJson(ex, 200, boardJson(g));
        }
    }

    static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq);
            if (k.equals(key)) return part.substring(eq + 1);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Static file server
    // ---------------------------------------------------------------------

    static class StaticHandler implements HttpHandler {
        final Path root;
        StaticHandler(Path root) { this.root = root; }

        public void handle(HttpExchange ex) throws IOException {
            String uri = ex.getRequestURI().getPath();
            if (uri.equals("/")) uri = "/index.html";
            Path file = root.resolve(uri.substring(1)).normalize();

            if (!file.startsWith(root) || !Files.exists(file) || Files.isDirectory(file)) {
                byte[] nf = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, nf.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(nf); }
                return;
            }

            String ct = guessContentType(file.toString());
            byte[] bytes = Files.readAllBytes(file);
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }

        String guessContentType(String name) {
            if (name.endsWith(".html")) return "text/html; charset=utf-8";
            if (name.endsWith(".css")) return "text/css; charset=utf-8";
            if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (name.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }

    // ---------------------------------------------------------------------
    // main
    // ---------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path publicDir = Path.of("public").toAbsolutePath().normalize();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/new", new NewGameHandler());
        server.createContext("/api/state", new StateHandler());
        server.createContext("/api/match", new MatchHandler());
        server.createContext("/api/undo", new UndoHandler());
        server.createContext("/api/shuffle", new ShuffleHandler());
        server.createContext("/", new StaticHandler(publicDir));
        server.setExecutor(null);
        server.start();

        System.out.println("Mahjong Solitaire server running at http://localhost:" + port);
        System.out.println("Serving static files from: " + publicDir);
    }
}