(() => {
  const boardEl = document.getElementById('board');
  const bannerEl = document.getElementById('banner');
  const timerEl = document.getElementById('timer');
  const movesEl = document.getElementById('moves');
  const tilesLeftEl = document.getElementById('tilesLeft');
  const newBtn = document.getElementById('newBtn');
  const undoBtn = document.getElementById('undoBtn');
  const shuffleBtn = document.getElementById('shuffleBtn');
  const hintBtn = document.getElementById('hintBtn');

  const TILE_W = 46, TILE_H = 62, STEP = 9;
  const COLS = 12, ROWS = 8, MAX_LEVEL = 3;

  let gameId = null;
  let selected = null; // tile id
  let timerHandle = null;
  let startTime = null;
  let finished = false;

  // --- Tile face rendering -------------------------------------------------
  // label codes produced by the server: W1-9 (characters), B1-9 (bamboo),
  // C1-9 (circles), WE/WS/WW/WN (winds), DR/DG/DW (dragons), F1-4, S1-4.
  function faceFor(label) {
    if (label[0] === 'W' && label.length === 2 && !isNaN(label[1])) {
      return { glyph: label[1], sub: '萬', cls: 'ink-red' };
    }
    if (label[0] === 'B' && label.length === 2) {
      return { glyph: label[1], sub: '條', cls: 'ink-green' };
    }
    if (label[0] === 'C' && label.length === 2) {
      return { glyph: label[1], sub: '筒', cls: 'ink-blue' };
    }
    const winds = { WE: '東', WS: '南', WW: '西', WN: '北' };
    if (winds[label]) return { glyph: winds[label], sub: '', cls: 'ink-blue' };
    const dragons = { DR: '中', DG: '發', DW: '白' };
    if (dragons[label]) {
      const cls = label === 'DR' ? 'ink-red' : (label === 'DG' ? 'ink-green' : 'ink-black');
      return { glyph: dragons[label], sub: '', cls };
    }
    if (label[0] === 'F') {
      const flowers = ['梅', '蘭', '竹', '菊'];
      return { glyph: flowers[parseInt(label[1], 10) - 1] || '花', sub: '花', cls: 'ink-red' };
    }
    if (label[0] === 'S') {
      const seasons = ['春', '夏', '秋', '冬'];
      return { glyph: seasons[parseInt(label[1], 10) - 1] || '季', sub: '', cls: 'ink-green' };
    }
    return { glyph: '?', sub: '', cls: 'ink-black' };
  }

  // --- API helpers ----------------------------------------------------------
  async function api(path, body) {
    const opts = body
      ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
      : { method: 'POST' };
    const res = await fetch(path, opts);
    if (!res.ok) throw new Error('Request failed: ' + path);
    return res.json();
  }

  async function startNewGame() {
    finished = false;
    selected = null;
    hideBanner();
    stopTimer();
    const state = await api('/api/new');
    gameId = state.gameId;
    render(state);
    startTimer();
  }

  async function tryMatch(a, b) {
    const state = await api('/api/match', { gameId, tileA: a, tileB: b });
    return state;
  }

  // --- Layout / sizing -------------------------------------------------------
  function sizeBoard() {
    const boardW = COLS * TILE_W + MAX_LEVEL * STEP + 24;
    const boardH = ROWS * TILE_H + MAX_LEVEL * STEP + 24;
    boardEl.style.width = boardW + 'px';
    boardEl.style.height = boardH + 'px';

    const wrap = boardEl.parentElement;
    const availW = wrap.clientWidth - 20;
    const scale = Math.min(1, availW / boardW);
    boardEl.style.transform = `scale(${scale})`;
    boardEl.style.transformOrigin = 'top center';
  }
  window.addEventListener('resize', sizeBoard);

  // --- Rendering ---------------------------------------------------------
  let tileEls = new Map(); // id -> element

  function render(state) {
    movesEl.textContent = state.moves;
    const remaining = state.tiles.filter(t => !t.removed).length;
    tilesLeftEl.textContent = remaining;

    // Build/update DOM
    const seen = new Set();
    for (const t of state.tiles) {
      seen.add(t.id);
      let el = tileEls.get(t.id);
      if (t.removed) {
        if (el) {
          el.classList.add('vanish');
          setTimeout(() => el.remove(), 280);
          tileEls.delete(t.id);
        }
        continue;
      }
      if (!el) {
        el = document.createElement('div');
        el.className = 'tile';
        el.dataset.id = t.id;
        const face = faceFor(t.label);
        el.innerHTML = `<div class="tile-face ${face.cls}"><div class="glyph">${face.glyph}</div>${face.sub ? `<div class="sub">${face.sub}</div>` : ''}</div>`;
        el.addEventListener('click', () => onTileClick(t.id));
        boardEl.appendChild(el);
        tileEls.set(t.id, el);
      }
      const left = 12 + t.col * TILE_W + t.level * STEP;
      const top = 12 + t.row * TILE_H - t.level * STEP;
      el.style.left = left + 'px';
      el.style.top = top + 'px';
      el.style.zIndex = String(t.level * 1000 + t.row * 10 + t.col);
      el.classList.toggle('free', t.free);
      el.classList.toggle('blocked', !t.free);
      el.classList.toggle('selected', selected === t.id);
    }

    undoBtn.disabled = !state.canUndo;
    sizeBoard();

    if (!finished && state.won) {
      finished = true;
      stopTimer();
      showBanner('You cleared the board!', `Solved in ${state.moves} moves, ${timerEl.textContent} elapsed.`, 'Play Again');
    } else if (!finished && state.deadlock) {
      finished = true;
      stopTimer();
      showBanner('No moves left', 'No free matching pair remains. Shuffle to keep going, or start a new game.', 'New Game');
    }
  }

  function showBanner(title, msg, btnLabel) {
    bannerEl.innerHTML = `<h2>${title}</h2><p>${msg}</p><button id="bannerBtn">${btnLabel}</button>`;
    bannerEl.classList.remove('hidden');
    document.getElementById('bannerBtn').addEventListener('click', () => startNewGame());
  }
  function hideBanner() {
    bannerEl.classList.add('hidden');
    bannerEl.innerHTML = '';
  }

  // --- Interaction ---------------------------------------------------------
  async function onTileClick(id) {
    if (finished) return;
    const el = tileEls.get(id);
    if (!el || !el.classList.contains('free')) return;

    if (selected === null) {
      selected = id;
      el.classList.add('selected');
      return;
    }
    if (selected === id) {
      selected = null;
      el.classList.remove('selected');
      return;
    }
    const a = selected, b = id;
    selected = null;
    const prevEl = tileEls.get(a);
    if (prevEl) prevEl.classList.remove('selected');

    const before = tileEls.get(a) && tileEls.get(b);
    const state = await tryMatch(a, b);
    const stillA = state.tiles.find(t => t.id === a);
    if (stillA && !stillA.removed) {
      // Not a match - brief shake feedback
      const elA = tileEls.get(a), elB = tileEls.get(b);
      [elA, elB].forEach(e => {
        if (!e) return;
        e.style.transition = 'none';
        e.style.filter = 'brightness(1.6)';
        setTimeout(() => { e.style.filter = ''; e.style.transition = ''; }, 180);
      });
    }
    render(state);
  }

  // --- Timer -----------------------------------------------------------
  function startTimer() {
    startTime = Date.now();
    timerHandle = setInterval(() => {
      const secs = Math.floor((Date.now() - startTime) / 1000);
      const m = String(Math.floor(secs / 60)).padStart(2, '0');
      const s = String(secs % 60).padStart(2, '0');
      timerEl.textContent = `${m}:${s}`;
    }, 500);
  }
  function stopTimer() {
    if (timerHandle) clearInterval(timerHandle);
    timerHandle = null;
  }

  // --- Buttons -----------------------------------------------------------
  newBtn.addEventListener('click', () => {
    tileEls.forEach(el => el.remove());
    tileEls.clear();
    startNewGame();
  });

  undoBtn.addEventListener('click', async () => {
    if (finished) return;
    const state = await api('/api/undo', { gameId });
    render(state);
  });

  shuffleBtn.addEventListener('click', async () => {
    const state = await api('/api/shuffle', { gameId });
    render(state);
  });

  let hintTimeout = null;
  hintBtn.addEventListener('click', async () => {
    const res = await fetch(`/api/state?gameId=${encodeURIComponent(gameId)}`);
    const state = await res.json();
    const free = state.tiles.filter(t => !t.removed && t.free);
    const byKey = new Map();
    const key = (type) => (type < 34 ? 'R' + type : (type < 38 ? 'FLOWER' : 'SEASON'));
    for (const t of free) {
      const k = key(t.type);
      if (!byKey.has(k)) byKey.set(k, []);
      byKey.get(k).push(t);
    }
    let pair = null;
    for (const arr of byKey.values()) {
      if (arr.length >= 2) { pair = [arr[0], arr[1]]; break; }
    }
    if (hintTimeout) clearTimeout(hintTimeout);
    if (!pair) return;
    for (const t of pair) {
      const el = tileEls.get(t.id);
      if (el) el.classList.add('hinted');
    }
    hintTimeout = setTimeout(() => {
      for (const t of pair) {
        const el = tileEls.get(t.id);
        if (el) el.classList.remove('hinted');
      }
    }, 1400);
  });

  // --- Boot ---------------------------------------------------------------
  startNewGame();
})();
