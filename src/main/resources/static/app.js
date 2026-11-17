/* ScamGraph frontend: talks to the Spring Boot REST API and draws the network with vis-network. */
'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const state = {
  summary: null,
  graph: null,
  nodeById: new Map(),
  mode: 'all',              // all | ring | trail
  network: null,
  nodesDS: null,
  edgesDS: null,
  selected: null,
  frozen: new Set(),
  trail: null,
  timers: [],
  chart: null,
};

/* ------------------------------------------------------------------ helpers */
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function indian(n) {
  const s = String(Math.round(Math.abs(n)));
  if (s.length <= 3) return (n < 0 ? '-' : '') + s;
  const last3 = s.slice(-3);
  const rest = s.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',');
  return (n < 0 ? '-' : '') + rest + ',' + last3;
}
function rupees(n) {
  if (n >= 1e7) return '₹' + trimNum(n / 1e7) + ' Cr';
  if (n >= 1e5) return '₹' + trimNum(n / 1e5) + ' L';
  return '₹' + indian(n);
}
function trimNum(v) { return (Math.round(v * 100) / 100).toString(); }
function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
function shortName(name, id) {
  if (!name) return id;
  const clean = name.replace(/\s*\(.*\)\s*/, '').trim();
  return clean.length > 18 ? clean.slice(0, 17) + '…' : clean;
}

async function api(path, options = {}) {
  const res = await fetch('/api' + path, options);
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (!res.ok) throw new Error((data && data.error) || `Request failed (${res.status})`);
  return data;
}

function toast(msg, isError = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.toggle('error', isError);
  t.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.add('hidden'), isError ? 6000 : 3000);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function clearTimers() { state.timers.forEach(clearTimeout); state.timers = []; }

function show(view) {
  ['landing', 'rawView', 'appView'].forEach((id) => $('#' + id).classList.toggle('hidden', id !== view));
}

/* ------------------------------------------------------------------ loading data */
function setBusy(busy) {
  state.busy = busy;
  ['#btnAnalyse', '#btnDemo', '#btnClean', '#btnDemoHero'].forEach((sel) => { $(sel).disabled = busy; });
  document.body.style.cursor = busy ? 'progress' : '';
}

async function loadDemo(fraud) {
  if (state.busy) return;
  setBusy(true);
  try {
    toast('Loading demo town…');
    const summary = await api(`/demo?fraud=${fraud}`, { method: 'POST' });
    await showRaw(summary);
  } catch (e) { toast(e.message, true); } finally { setBusy(false); }
}

async function uploadFile(file) {
  if (!file || state.busy) return;
  setBusy(true);
  try {
    toast(`Uploading ${file.name}…`);
    const form = new FormData();
    form.append('file', file);
    const summary = await api('/upload', { method: 'POST', body: form });
    await showRaw(summary);
  } catch (e) { toast(e.message, true); } finally { setBusy(false); }
}

async function showRaw(summary) {
  state.summary = summary;
  state.frozen.clear();
  state.selected = null;
  $('#datasetLabel').textContent = `${summary.dataset} · ${summary.period}`;
  $('#rawEyebrow').textContent = summary.dataset;
  $('#rawTitle').textContent = `${indian(summary.transactions)} transactions · ${indian(summary.accounts)} accounts · ${rupees(summary.totalAmount)}`;
  const rows = await api('/transactions?limit=400');
  $('#rawTable tbody').innerHTML = rows.map((t) => `
    <tr>
      <td class="acct">${esc(t.id)}</td>
      <td>${esc(t.time)}</td>
      <td><span class="acct">${esc(t.from)}</span><span class="sub">${esc(t.fromName)}</span></td>
      <td><span class="acct">${esc(t.to)}</span><span class="sub">${esc(t.toName)}</span></td>
      <td class="num">${rupees(t.amount)}</td>
      <td>${esc(t.channel)}</td>
    </tr>`).join('') + (summary.transactions > rows.length
      ? `<tr><td colspan="6" class="muted" style="text-align:center;padding:14px">… and ${indian(summary.transactions - rows.length)} more transactions</td></tr>` : '');
  show('rawView');
  window.scrollTo(0, 0);
}

/* ------------------------------------------------------------------ analysis reveal */
async function analyse() {
  if (state.busy) return;
  show('appView');
  renderKpis(null);
  const overlay = $('#overlay');
  const steps = [
    'Building the money network',
    'FIFO money ageing (pass-through)',
    'Money chains (dynamic programming)',
    'Circular loops (DFS cycle search)',
    'Betweenness centrality (Brandes)',
    'Smurfing & burst detection',
    'Risk propagation & scoring',
    'Grouping fraud rings (Union-Find)',
    'Max-flow: where to freeze',
  ];
  $('#overlaySteps').innerHTML = steps.map((s) => `<li class="wait">${s}</li>`).join('');
  overlay.classList.remove('hidden');
  $('#overlayTitle').textContent = 'Analysing…';

  const [summary, graph] = await Promise.all([api('/summary'), api('/graph')]);
  state.summary = summary;
  state.graph = graph;
  state.nodeById = new Map(graph.nodes.map((n) => [n.id, n]));

  state.mode = 'all';
  setModeButtons();
  drawNetwork({ reveal: false });                 // everyone grey while the layout settles

  const items = $$('#overlaySteps li');
  for (let i = 0; i < items.length; i++) {
    items[i].className = 'run';
    await sleep(i === 0 ? 450 : 260);
    items[i].className = 'done';
  }
  $('#overlayTitle').textContent = summary.rings > 0
    ? `Found ${summary.rings} fraud ring${summary.rings > 1 ? 's' : ''} in ${summary.analysisMillis} ms`
    : `No fraud ring found (${summary.analysisMillis} ms)`;
  await sleep(700);
  overlay.classList.add('hidden');

  revealColours();
  renderKpis(summary);
  renderOverview();
  renderMethod();
  selectTab('overview');
  if (summary.rings > 0) {
    await sleep(900);
    focusRing();
  }
}

/* ------------------------------------------------------------------ KPIs */
function renderKpis(s) {
  const k = (label, value, sub, cls = '') => `<div class="kpi ${cls}"><div class="label">${label}</div><div class="value">${value}</div><div class="sub">${sub}</div></div>`;
  if (!s) {
    $('#kpis').innerHTML = ['Accounts', 'Transactions', 'Money moved', 'Suspects', 'Victims', 'Stolen money traced']
      .map((l) => k(l, '…', '&nbsp;')).join('');
    return;
  }
  $('#kpis').innerHTML = [
    k('Accounts', indian(s.accounts), 'nodes in the network'),
    k('Transactions', indian(s.transactions), s.period),
    k('Money moved', rupees(s.totalAmount), 'total value'),
    k('Suspects', indian(s.suspects), `${s.watchlist} more on watchlist`, s.suspects ? 'hot' : ''),
    k('Victims', indian(s.victims), s.victims ? `lost ${rupees(s.moneyStolen)}` : 'none found', s.victims ? 'victim' : ''),
    k('Stolen money traced', s.moneyTraced ? rupees(s.moneyTraced) : '—',
      s.moneyStolen ? `${Math.round(100 * s.moneyTraced / s.moneyStolen)}% reaches cash-out` : 'max-flow to cash-out', s.moneyTraced ? 'hot' : ''),
  ].join('');
}

/* ------------------------------------------------------------------ network drawing */
function nodeShape(n) {
  if (n.pattern === 'Collector mule') return 'triangle';
  if (n.pattern === 'Cash-out account') return 'diamond';
  return 'dot';
}

function roleColour(n) {
  if (n.role === 'SUSPECT') return cssVar('--hot');
  if (n.role === 'WATCH') return cssVar('--watch');
  if (n.role === 'VICTIM') return cssVar('--victim');
  return cssVar('--normal');
}

function tooltip(n) {
  const div = document.createElement('div');
  div.innerHTML = `<div class="tip-name">${esc(n.name || n.id)}</div>
    <div class="mono">${esc(n.id)}</div>
    <div>Risk <span class="tip-risk" style="color:${roleColour(n)}">${n.risk}</span> · ${esc(n.pattern)}</div>
    <div>In ${rupees(n.inAmount)} · Out ${rupees(n.outAmount)}</div>`;
  return div;
}

function visNode(n, reveal) {
  const important = n.role !== 'NORMAL';
  const colour = reveal ? roleColour(n) : cssVar('--normal');
  const frozen = state.frozen.has(n.id);
  const size = !reveal ? 6 : n.role === 'SUSPECT' ? 12 + n.risk / 8 : n.role === 'WATCH' ? 11 : n.role === 'VICTIM' ? 9 : 5;
  return {
    id: n.id,
    label: reveal && important ? (frozen ? '❄ ' : '') + shortName(n.name, n.id) : undefined,
    title: tooltip(n),
    shape: reveal ? nodeShape(n) : 'dot',
    size,
    color: {
      background: frozen ? cssVar('--surface-2') : colour,
      border: frozen ? cssVar('--muted') : colour,
      highlight: { background: colour, border: cssVar('--gold') },
      hover: { background: colour, border: cssVar('--text') },
    },
    opacity: reveal && !important ? 0.55 : 1,
    borderWidth: frozen ? 2 : 1,
    shapeProperties: { borderDashes: frozen ? [4, 3] : false },
    font: { color: cssVar('--text'), size: n.role === 'SUSPECT' ? 13 : 11, face: 'Segoe UI, system-ui, sans-serif', strokeWidth: 3, strokeColor: cssVar('--surface') },
  };
}

function visEdge(e, reveal, i) {
  const hot = reveal && e.suspicious;
  const frozen = state.frozen.has(e.from) || state.frozen.has(e.to);
  return {
    id: 'e' + i,
    from: e.from,
    to: e.to,
    amount: e.amount,
    suspicious: e.suspicious,
    width: hot ? Math.min(6, 1 + Math.log10(e.amount / 10000 + 1) * 2.2) : 0.6,
    color: { color: hot ? (frozen ? cssVar('--faint') : cssVar('--hot')) : cssVar('--edge'), highlight: cssVar('--gold'), hover: cssVar('--gold'), inherit: false },
    arrows: { to: { enabled: hot, scaleFactor: 0.5 } },
    dashes: hot && frozen,
    title: `${e.from} → ${e.to}\n${rupees(e.amount)} in ${e.count} payment${e.count > 1 ? 's' : ''}`,
  };
}

function baseOptions(hierarchical, spacing = { node: 46, level: 210 }) {
  const opts = {
    autoResize: true,
    nodes: { borderWidth: 1 },
    edges: { smooth: false, selectionWidth: 1.5, hoverWidth: 0.5 },
    interaction: { hover: true, tooltipDelay: 120, hideEdgesOnDrag: !hierarchical, navigationButtons: false, keyboard: false, multiselect: false },
    layout: { improvedLayout: false, randomSeed: 11 },
    physics: {
      enabled: !hierarchical,
      solver: 'forceAtlas2Based',
      forceAtlas2Based: { gravitationalConstant: -42, centralGravity: 0.01, springLength: 80, springConstant: 0.05, avoidOverlap: 0.25 },
      stabilization: { enabled: true, iterations: 320, updateInterval: 40 },
      maxVelocity: 45,
      minVelocity: 0.8,
    },
  };
  if (hierarchical) {
    opts.layout = { hierarchical: { enabled: true, direction: 'LR', levelSeparation: spacing.level, nodeSpacing: spacing.node, treeSpacing: 60, sortMethod: 'directed', shakeTowards: 'roots' } };
    opts.edges.smooth = { enabled: true, type: 'cubicBezier', forceDirection: 'horizontal', roundness: 0.45 };
  }
  return opts;
}

function mountNetwork(nodes, edges, hierarchical, spacing) {
  if (state.network) { state.network.destroy(); state.network = null; }
  state.nodesDS = new vis.DataSet(nodes);
  state.edgesDS = new vis.DataSet(edges);
  const net = new vis.Network($('#network'), { nodes: state.nodesDS, edges: state.edgesDS }, baseOptions(hierarchical, spacing));
  net.on('click', (params) => {
    if (params.nodes.length) selectAccount(params.nodes[0]);
  });
  if (!hierarchical) {
    net.once('stabilizationIterationsDone', () => net.setOptions({ physics: { enabled: false } }));
  }
  state.network = net;
  return net;
}

function drawNetwork({ reveal }) {
  clearTimers();
  hideCaption();
  const g = state.graph;
  if (state.mode === 'all') {
    mountNetwork(g.nodes.map((n) => visNode(n, reveal)), g.edges.map((e, i) => visEdge(e, reveal, i)), false);
  } else if (state.mode === 'ring') {
    drawRing();
  }
}

function revealColours() {
  if (!state.nodesDS) return;
  state.nodesDS.update(state.graph.nodes.map((n) => visNode(n, true)));
  state.edgesDS.update(state.graph.edges.map((e, i) => visEdge(e, true, i)));
}

/* Fraud-ring view: victims on the left, cash-out on the right, columns by hop distance (BFS). */
function drawRing() {
  const g = state.graph;
  const inRing = (n) => n.ring != null;
  const members = g.nodes.filter(inRing);
  if (!members.length) {
    mountNetwork([], [], true);
    showCaption('No fraud ring in this dataset. Every account behaves normally.');
    return;
  }
  const memberIds = new Set(members.map((n) => n.id));
  const keep = new Set(memberIds);
  g.edges.forEach((e) => { if (memberIds.has(e.to) && state.nodeById.get(e.from).role === 'VICTIM') keep.add(e.from); });

  // BFS from victims over ring edges to give every account a column (level)
  const level = new Map();
  const queue = [];
  keep.forEach((id) => { if (state.nodeById.get(id).role === 'VICTIM') { level.set(id, 0); queue.push(id); } });
  const out = new Map();
  g.edges.forEach((e) => {
    if (keep.has(e.from) && keep.has(e.to)) {
      if (!out.has(e.from)) out.set(e.from, []);
      out.get(e.from).push(e.to);
    }
  });
  while (queue.length) {
    const u = queue.shift();
    (out.get(u) || []).forEach((v) => { if (!level.has(v)) { level.set(v, level.get(u) + 1); queue.push(v); } });
  }
  let victimIndex = 0;
  const nodes = [...keep].sort().map((id) => {
    const n = state.nodeById.get(id);
    const vn = visNode(n, true);
    // victims are spread over two columns (levels 0 and 1) so the picture is not too tall
    vn.level = n.role === 'VICTIM' ? (victimIndex++ % 2) : (level.has(id) ? level.get(id) + 1 : 2);
    vn.label = (state.frozen.has(id) ? '❄ ' : '') + shortName(n.name, n.id) + (n.role === 'VICTIM' ? '' : ` · ${n.risk}`);
    vn.size = n.role === 'VICTIM' ? 9 : 16;
    vn.font.size = n.role === 'VICTIM' ? 12 : 14;
    vn.opacity = 1;
    return vn;
  });
  const edges = [];
  g.edges.forEach((e, i) => { if (keep.has(e.from) && keep.has(e.to)) edges.push(visEdge({ ...e, suspicious: true }, true, i)); });
  const net = mountNetwork(nodes, edges, true, { node: 44, level: 190 });
  net.once('afterDrawing', () => net.fit({ animation: { duration: 500 } }));
  const ring = state.summary.ringList[0];
  showCaption(`<b>Fraud ring #${ring.id}</b>: money flows left → right. ${ring.victims} victims → collector mules → layering mules → cash-out. ${ring.size} mule accounts, ${rupees(ring.stolen)} stolen.`);
}

function focusRing() {
  if (state.mode !== 'all' || !state.network) return;
  const ids = state.graph.nodes.filter((n) => n.ring != null).map((n) => n.id);
  if (!ids.length) return;
  state.network.selectNodes(ids, true);
  state.network.fit({ nodes: ids, animation: { duration: 1200, easingFunction: 'easeInOutQuad' } });
  state.timers.push(setTimeout(() => state.network && state.network.unselectAll(), 2600));
}

function setMode(mode) {
  if (!state.graph) return;
  state.mode = mode;
  setModeButtons();
  drawNetwork({ reveal: true });
}

function setModeButtons() {
  $$('.seg-btn').forEach((b) => b.classList.toggle('active', b.dataset.mode === state.mode));
}

function showCaption(html) { const c = $('#caption'); c.innerHTML = html; c.classList.remove('hidden'); }
function hideCaption() { $('#caption').classList.add('hidden'); }

/* ------------------------------------------------------------------ money trail animation */
async function playTrail(id) {
  try {
    const trail = await api('/trail/' + encodeURIComponent(id));
    if (!trail.steps.length) { toast('No money trail (no payments of ₹10,000 or more).'); return; }
    clearTimers();
    state.mode = 'trail';
    $$('.seg-btn').forEach((b) => b.classList.remove('active'));

    const hopOf = new Map([[id, 0]]);
    trail.steps.forEach((s) => {
      if (s.hop > 0 && !hopOf.has(s.to)) hopOf.set(s.to, s.hop);
      if (s.hop < 0 && !hopOf.has(s.from)) hopOf.set(s.from, s.hop);
    });
    trail.steps.forEach((s) => { if (!hopOf.has(s.from)) hopOf.set(s.from, s.hop - 1); if (!hopOf.has(s.to)) hopOf.set(s.to, s.hop); });
    const minHop = Math.min(...hopOf.values());

    const nodes = [...hopOf.keys()].map((nid) => {
      const n = state.nodeById.get(nid);
      const vn = visNode(n, true);
      vn.level = hopOf.get(nid) - minHop;
      vn.label = (state.frozen.has(nid) ? '❄ ' : '') + shortName(n.name, n.id);
      vn.opacity = 1;
      if (nid === id) { vn.borderWidth = 4; vn.color.border = cssVar('--gold'); }
      return vn;
    });
    const faint = cssVar('--line');
    const edges = trail.steps.map((s, i) => ({
      id: 't' + i, from: s.from, to: s.to, width: 1,
      color: { color: faint, highlight: cssVar('--gold'), inherit: false },
      arrows: { to: { enabled: true, scaleFactor: 0.5 } },
      title: `${s.from} → ${s.to}\n${rupees(s.amount)} in ${s.count} payment${s.count > 1 ? 's' : ''}`,
      font: { color: cssVar('--gold'), size: 11, strokeWidth: 3, strokeColor: cssVar('--surface'), align: 'top' },
    }));
    const net = mountNetwork(nodes, edges, true, { node: 58, level: 250 });
    net.once('afterDrawing', () => net.fit({ animation: { duration: 400 } }));

    // only the 4 biggest links of each hop get an amount label, so the picture stays readable
    const labelled = new Set();
    const byHop = new Map();
    trail.steps.forEach((s, i) => { if (!byHop.has(s.hop)) byHop.set(s.hop, []); byHop.get(s.hop).push(i); });
    byHop.forEach((list) => list.sort((a, b) => trail.steps[b].amount - trail.steps[a].amount).slice(0, 4).forEach((i) => labelled.add(i)));

    const hops = [...new Set(trail.steps.map((s) => s.hop))].sort((a, b) => a - b);
    const gold = cssVar('--gold');
    const n0 = state.nodeById.get(id);
    showCaption(`<b>Money trail</b> for ${esc(n0.name || id)}: watch the money move hop by hop…`);
    hops.forEach((hop, k) => {
      state.timers.push(setTimeout(() => {
        const ups = [];
        let total = 0;
        trail.steps.forEach((s, i) => {
          if (s.hop === hop) {
            total += s.amount;
            ups.push({ id: 't' + i, width: Math.min(7, 1.5 + Math.log10(s.amount / 10000 + 1) * 2.5), color: { color: gold, highlight: gold, inherit: false }, label: labelled.has(i) ? rupees(s.amount) : undefined });
          }
        });
        state.edgesDS.update(ups);
        const where = hop < 0 ? `${-hop} hop${hop < -1 ? 's' : ''} before` : `${hop} hop${hop > 1 ? 's' : ''} after`;
        showCaption(`<b>${where}</b>: ${ups.length} payment link${ups.length > 1 ? 's' : ''} carrying ${rupees(total)}`);
      }, 700 + k * 1100));
    });
    state.timers.push(setTimeout(() => showCaption(`<b>Trail complete.</b> ${trail.steps.length} payment links within 4 hops of ${esc(n0.name || id)}. Click "Whole town" or "Fraud ring" to go back.`), 900 + hops.length * 1100));
  } catch (e) { toast(e.message, true); }
}

/* ------------------------------------------------------------------ freeze (max-flow what-if) */
async function freeze(id) {
  try {
    const r = await api('/block/' + encodeURIComponent(id));
    state.frozen.add(id);
    // update visuals in the current view
    if (state.nodesDS && state.nodesDS.get(id)) {
      const n = state.nodeById.get(id);
      const vn = visNode(n, true);
      if (state.mode !== 'all') { vn.label = '❄ ' + shortName(n.name, n.id); vn.opacity = 1; }
      state.nodesDS.update(vn);
      const faint = cssVar('--faint');
      state.edgesDS.update(state.edgesDS.get({ filter: (e) => e.from === id || e.to === id }).map((e) => ({ id: e.id, dashes: true, color: { color: faint, inherit: false } })));
    }
    const box = $('#blockResult');
    if (box) box.innerHTML = blockHtml(r);
    return r;
  } catch (e) { toast(e.message, true); }
}

function blockHtml(r) {
  const beforePct = 100;
  const afterPct = r.before ? Math.round(100 * r.after / r.before) : 0;
  return `<div class="block-result">
    <div class="big-line">❄ Freezing <b>${esc(r.name || r.account)}</b> stops <b class="hot">${rupees(r.stopped)}</b> (${r.percent}%)</div>
    <div class="flowbars">
      <div class="flowbar"><div class="l"><span>Stolen money reaching cash-out now</span><span>${rupees(r.before)}</span></div><div class="bar"><i style="width:${beforePct}%"></i></div></div>
      <div class="flowbar after"><div class="l"><span>After freezing</span><span>${rupees(r.after)}</span></div><div class="bar"><i style="width:${afterPct}%"></i></div></div>
    </div>
    <p class="muted" style="margin:6px 0">${esc(r.message)}</p>
    ${r.brokenLinks.length ? `<div class="chain-meta">Links cut: ${r.brokenLinks.map(esc).join(' · ')}</div>` : ''}
    <div class="chain-meta" style="margin-top:6px">Computed with max-flow (Edmonds–Karp) from victims to cash-out accounts.</div>
  </div>`;
}

/* ------------------------------------------------------------------ side panel */
function selectTab(name) {
  $$('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  ['overview', 'account', 'method'].forEach((t) => $('#tab-' + t).classList.toggle('hidden', t !== name));
}

function accountRow(id, risk, level, title, sub) {
  return `<li data-account="${esc(id)}"><span class="score ${level}">${risk}</span><div class="who"><div class="n">${esc(title)}</div><div class="d">${esc(sub)}</div></div></li>`;
}

function chainHtml(c) {
  const parts = [];
  c.accounts.forEach((acc, i) => {
    const n = state.nodeById.get(acc);
    const cls = n ? (n.role === 'VICTIM' ? 'v' : (n.role === 'SUSPECT' || n.role === 'WATCH') ? 's' : '') : '';
    parts.push(`<span class="node ${cls}" data-account="${esc(acc)}" title="${esc(n ? n.name : '')}">${esc(acc)}</span>`);
    if (i < c.amounts.length) parts.push(`<span class="arrow">→</span><span class="amt">${rupees(c.amounts[i])}</span><span class="arrow">→</span>`);
  });
  return `<div class="chain">${parts.join('')}</div>`;
}

function renderOverview() {
  const s = state.summary;
  const el = $('#tab-overview');
  let html = '';
  if (s.rings > 0) {
    const r = s.ringList[0];
    html += `<div class="card ring-card">
      <h3>Fraud ring #${r.id} <span class="chip hot">${r.size} accounts</span></h3>
      <div class="big-line"><b>${r.victims} victims</b> lost <b class="hot">${rupees(r.stolen)}</b>. The money passed through ${r.size} mule accounts in ${indian(r.internalPayments)} payments and ended in ${r.cashOut.length} cash-out account${r.cashOut.length === 1 ? '' : 's'}.</div>
      <div class="stat-grid">
        <div class="stat"><div class="l">Money chains</div><div class="v">${s.chainCount}</div></div>
        <div class="stat"><div class="l">Loops</div><div class="v">${s.cycleCount}</div></div>
        <div class="stat"><div class="l">Traced</div><div class="v">${rupees(s.moneyTraced)}</div></div>
      </div>
      <div class="row-actions"><button class="btn small primary" data-action="ring">Show ring only</button><button class="btn small ghost" data-action="focus">Zoom to ring</button></div>
    </div>`;
    if (s.bestBlock) {
      const b = s.bestBlock;
      html += `<div class="card"><h3>Best account to freeze</h3>
        <div class="big-line">Freezing <b>${esc(b.name || b.account)}</b> <span class="mono muted">${esc(b.account)}</span> stops <b class="hot">${b.percent}%</b> of the stolen money flow (${rupees(b.stopped)}).</div>
        <div class="row-actions"><button class="btn small gold" data-account="${esc(b.account)}">Investigate</button></div></div>`;
    }
  } else {
    html += `<div class="card"><h3>Result</h3><div class="big-line"><b>No fraud ring found.</b> Money in this town stays in accounts for days and flows to the usual places: shops, rent, bills, family. That is exactly what we expect from clean data.</div></div>`;
  }

  html += `<div class="card"><h3>Most suspicious accounts</h3>`;
  html += s.topAccounts.length
    ? `<ul class="list">${s.topAccounts.slice(0, 15).map((a) => accountRow(a.id, a.risk, a.level, a.name || a.id, `${a.pattern} · ${a.topReason}`)).join('')}</ul>`
    : `<p class="muted">No account scored above the watchlist line.</p>`;
  html += `</div>`;

  if (s.chains.length) {
    html += `<div class="card"><h3>Fastest money chains <span class="chip">${s.chainCount} found</span></h3>
      ${s.chains.slice(0, 4).map((c) => `${chainHtml(c)}<div class="chain-meta">${c.hops} hops in ${esc(c.duration)} · ${esc(c.times[0])}</div>`).join('<hr style="border:0;border-top:1px dashed var(--line);margin:8px 0">')}
    </div>`;
  }
  if (s.cycles.length) {
    html += `<div class="card"><h3>Circular loops (round-tripping)</h3>
      ${s.cycles.slice(0, 3).map((c) => `${chainHtml({ accounts: c.accounts, amounts: [] })}<div class="chain-meta">${rupees(c.amount)} went round the loop in ${esc(c.duration)}</div>`).join('')}
    </div>`;
  }
  html += `<div class="card"><h3>Risk score distribution</h3><div class="chart-box"><canvas id="histChart"></canvas></div>
    <div class="chain-meta">Most accounts score near 0. Mules stand far apart on the right.</div></div>`;
  el.innerHTML = html;
  drawHistogram();
}

function drawHistogram() {
  const canvas = $('#histChart');
  if (!canvas || !window.Chart) return;
  if (state.chart) state.chart.destroy();
  const s = state.summary;
  const colours = s.riskHistogram.map((_, i) => i >= 4.5 ? cssVar('--hot') : i >= 3.5 ? cssVar('--watch') : cssVar('--normal'));
  state.chart = new Chart(canvas, {
    type: 'bar',
    data: { labels: s.riskHistogram.map((_, i) => `${i * 10}–${i * 10 + 9}`), datasets: [{ data: s.riskHistogram.map((v) => v || null), backgroundColor: colours, borderRadius: 3 }] },
    options: {
      responsive: true, maintainAspectRatio: false, animation: { duration: 600 },
      plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => `${c.raw} accounts` } } },
      scales: {
        y: { type: 'logarithmic', min: 0.8, ticks: { color: cssVar('--muted'), callback: (v) => ([1, 10, 100, 1000].includes(v) ? v : '') }, grid: { color: cssVar('--line') }, title: { display: true, text: 'accounts (log)', color: cssVar('--muted') } },
        x: { ticks: { color: cssVar('--muted'), font: { size: 10 } }, grid: { display: false }, title: { display: true, text: 'risk score', color: cssVar('--muted') } },
      },
    },
  });
}

function renderMethod() {
  const s = state.summary;
  $('#tab-method').innerHTML = `
    <div class="card"><h3>Risk score</h3>
      <div class="formula">risk = 100 × Σ (weight × signal)<br>each signal is between 0 and 1<br>weights add up to 1.0 → risk is 0–100</div>
      <p class="muted">Suspect: 45+ · Watchlist: 35–44 · Victim: low-risk account that sent ₹25,000+ to flagged accounts.</p></div>
    <div class="card weights"><h3>The 7 signals</h3>
      ${s.weights.map((w) => `<div class="w-row"><div class="top"><span>${esc(w.label)}</span><span>${Math.round(w.weight * 100)}%</span></div>
        <div class="bar w"><i style="width:${w.weight * 400}%"></i></div><p>${esc(w.meaning)}</p></div>`).join('')}
    </div>
    <div class="card"><h3>Algorithms used</h3>
      <ul class="reasons">
        <li><b>Adjacency list</b>: stores the graph in O(V + E) memory.</li>
        <li><b>FIFO queue</b>: money ageing, how long each rupee stayed.</li>
        <li><b>Sliding window</b> (two pointers): busiest hour.</li>
        <li><b>Dynamic programming</b> on time-ordered payments: longest money chain.</li>
        <li><b>DFS</b> with colouring / bounded search: circular loops.</li>
        <li><b>BFS + Brandes' algorithm</b>: betweenness centrality.</li>
        <li><b>Union-Find</b>: groups suspects into fraud rings.</li>
        <li><b>Max-flow (Edmonds–Karp)</b>: stolen money reaching cash-out, and which account to freeze (min-cut).</li>
      </ul></div>`;
}

async function selectAccount(id) {
  state.selected = id;
  selectTab('account');
  const el = $('#tab-account');
  el.innerHTML = `<p class="muted pad">Loading ${esc(id)}…</p>`;
  try {
    const a = await api('/account/' + encodeURIComponent(id));
    const colour = a.role === 'SUSPECT' ? 'var(--hot)' : a.role === 'WATCH' ? 'var(--watch)' : a.role === 'VICTIM' ? 'var(--victim)' : 'var(--normal)';
    const chipCls = a.role === 'SUSPECT' ? 'hot' : a.role === 'WATCH' ? 'watch' : a.role === 'VICTIM' ? 'victim' : '';
    const canFreeze = a.role === 'SUSPECT' || a.role === 'WATCH';
    el.innerHTML = `
      <div class="card">
        <div class="acct-head">
          <div class="gauge" style="--p:${a.risk};--c:${colour}"><div><div><div class="g-val" style="color:${colour}">${a.risk}</div><div class="g-lbl">risk</div></div></div></div>
          <div style="min-width:0">
            <div class="acct-name">${esc(a.name || a.id)}</div>
            <div class="acct-id">${esc(a.id)}</div>
            <div class="chips"><span class="chip ${chipCls}">${esc(a.pattern)}</span>${a.ring ? `<span class="chip hot">Ring #${a.ring}</span>` : ''}${a.victimLoss ? `<span class="chip victim">lost ${rupees(a.victimLoss)}</span>` : ''}</div>
          </div>
        </div>
        <div class="stat-grid">
          <div class="stat"><div class="l">Money in</div><div class="v">${rupees(a.inAmount)}</div></div>
          <div class="stat"><div class="l">Money out</div><div class="v">${rupees(a.outAmount)}</div></div>
          <div class="stat"><div class="l">Forwarded</div><div class="v">${a.forwardedPercent}%</div></div>
          <div class="stat"><div class="l">Money stays</div><div class="v">${esc(a.avgHoldTime)}</div></div>
          <div class="stat"><div class="l">Paid by</div><div class="v">${a.inDegree} accts</div></div>
          <div class="stat"><div class="l">Paid to</div><div class="v">${a.outDegree} accts</div></div>
        </div>
        <div class="row-actions">
          <button class="btn small gold" data-trail="${esc(a.id)}">▶ Animate money trail</button>
          ${canFreeze ? `<button class="btn small danger" data-freeze="${esc(a.id)}">❄ Freeze account (what-if)</button>` : ''}
        </div>
        <div id="blockResult"></div>
      </div>
      <div class="card"><h3>Why this score</h3><ul class="reasons">${a.reasons.map((r) => `<li>${esc(r)}</li>`).join('')}</ul></div>
      <div class="card"><h3>Signals <span class="chip">${a.risk} / 100</span></h3>
        ${a.signals.map((sg) => `<div class="signal"><div class="top"><span>${esc(sg.label)} <span class="muted">(${Math.round(sg.weight * 100)}%)</span></span><span class="pts">+${sg.points.toFixed(1)}</span></div>
          <div class="bar ${sg.points < 2.5 ? 'w' : ''}"><i style="width:${Math.round(sg.value * 100)}%"></i></div><div class="why">${esc(sg.explanation)}</div></div>`).join('')}
      </div>
      <div class="card"><h3>Latest transactions <span class="chip">${a.transactions.length}</span></h3>
        <table class="small-table">${a.transactions.map((t) => {
          const other = t.direction === 'IN' ? t.from : t.to;
          const otherName = t.direction === 'IN' ? t.fromName : t.toName;
          return `<tr class="${t.direction}"><td><div class="t">${esc(t.time)}</div>${t.direction === 'IN' ? '← from' : '→ to'} <span class="mono" data-account="${esc(other)}" style="cursor:pointer;text-decoration:underline dotted">${esc(other)}</span><div class="t">${esc(otherName)}</div></td><td class="num">${t.direction === 'IN' ? '+' : '−'}${rupees(t.amount)}</td></tr>`;
        }).join('')}</table>
      </div>`;
    if (state.network && state.nodesDS && state.nodesDS.get(id)) {
      state.network.selectNodes([id]);
      if (state.mode === 'all') state.network.focus(id, { scale: 1.1, animation: { duration: 600 } });
    }
  } catch (e) {
    el.innerHTML = `<p class="muted pad">${esc(e.message)}</p>`;
  }
}

/* ------------------------------------------------------------------ theme */
function setTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  $('#btnTheme').textContent = theme === 'dark' ? 'Light mode' : 'Dark mode';
  try { localStorage.setItem('scamgraph-theme', theme); } catch (e) { /* storage may be blocked */ }
  if (state.graph && state.nodesDS) {
    if (state.mode === 'trail' && state.selected) playTrail(state.selected);
    else drawNetwork({ reveal: true });
    drawHistogram();
  }
}

/* ------------------------------------------------------------------ wiring */
function wire() {
  $('#btnDemo').addEventListener('click', () => loadDemo(true));
  $('#btnDemoHero').addEventListener('click', () => loadDemo(true));
  $('#btnClean').addEventListener('click', () => loadDemo(false));
  ['#fileInput', '#fileInputHero'].forEach((sel) => $(sel).addEventListener('change', (e) => { uploadFile(e.target.files[0]); e.target.value = ''; }));
  $('#btnAnalyse').addEventListener('click', () => analyse().catch((e) => { $('#overlay').classList.add('hidden'); toast(e.message, true); }));
  $('#btnFit').addEventListener('click', () => state.network && state.network.fit({ animation: { duration: 500 } }));
  $('#btnTheme').addEventListener('click', () => setTheme(document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark'));
  $$('.seg-btn').forEach((b) => b.addEventListener('click', () => setMode(b.dataset.mode)));
  $$('.tab').forEach((t) => t.addEventListener('click', () => selectTab(t.dataset.tab)));

  // one click handler for everything inside the side panel
  document.querySelector('.panel').addEventListener('click', (e) => {
    const target = e.target.closest('[data-account],[data-action],[data-trail],[data-freeze]');
    if (!target) return;
    if (target.dataset.trail) return playTrail(target.dataset.trail);
    if (target.dataset.freeze) return freeze(target.dataset.freeze);
    if (target.dataset.action === 'ring') return setMode('ring');
    if (target.dataset.action === 'focus') { if (state.mode !== 'all') setMode('all'); return setTimeout(focusRing, 300); }
    if (target.dataset.account) return selectAccount(target.dataset.account);
  });

  // drag & drop a CSV anywhere on the landing page
  ['dragover', 'drop'].forEach((ev) => document.addEventListener(ev, (e) => {
    e.preventDefault();
    if (ev === 'drop' && e.dataTransfer.files.length) uploadFile(e.dataTransfer.files[0]);
  }));
}

async function init() {
  let theme = 'dark';
  try { theme = localStorage.getItem('scamgraph-theme') || 'dark'; } catch (e) { /* ignore */ }
  document.documentElement.setAttribute('data-theme', theme);
  $('#btnTheme').textContent = theme === 'dark' ? 'Light mode' : 'Dark mode';
  wire();
  try {
    const st = await api('/status');
    if (st.loaded) {
      const summary = await api('/summary');
      await showRaw(summary);
    }
  } catch (e) { /* server not ready yet: stay on landing */ }
}

init();
