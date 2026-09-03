# Mahjong Solitaire

A Chinese/tile-matching Mahjong Solitaire game with a Java backend and an
HTML/CSS/JS frontend, served as a website.

- **Backend (`MahjongServer.java`)**: pure JDK, no external libraries.
  Uses the built-in `com.sun.net.httpserver` to serve the frontend and a
  small JSON REST API. It owns all game logic: board layout (144 tiles
  across 4 stacked tiers), guaranteed-solvable dealing, free-tile rules,
  match validation, undo, shuffle, and win/deadlock detection.
- **Frontend (`public/`)**: plain HTML/CSS/JS, no build step, no
  frameworks. Renders the board, handles clicks, and talks to the API.

## Run it

Requires a JDK (17+ is fine; developed against 21). No Maven/Gradle,
no npm — just `javac`/`java`.

```bash
javac MahjongServer.java
java MahjongServer          # defaults to port 8080
# or choose a port:
java MahjongServer 9090
```

Then open **http://localhost:8080** (or whatever port you chose) in a browser.

## How to play

- Click a tile to select it, click a matching tile to remove both.
- A tile is **free** (clickable) only if nothing is stacked on top of it
  and at least one side (left or right) is open.
- Matching pairs: identical suit tiles (characters/bamboo/circles),
  identical winds, identical dragons, *or* any two flowers together,
  *or* any two seasons together (flowers and seasons are wildcards within
  their own group, per traditional rules).
- **Undo** reverses the last move. **Shuffle** reshuffles the remaining
  tiles in place if you get stuck (every dealt board is solvable if
  played in the right order, but it's still possible to paint yourself
  into a corner along the way — that's normal Mahjong Solitaire).
- **Hint** briefly highlights one valid free matching pair.

## Notes on deployment

This is a single Java process serving both the static frontend and the
API, so it can be dropped onto any host/VM/container that can run a JDK
and expose a port — no separate web server needed. Game state is kept
in memory per session (keyed by a `gameId`), so it resets if the server
restarts; that's fine for a casual game like this, but worth knowing if
you want persistence later (would need a database or file-backed store).
