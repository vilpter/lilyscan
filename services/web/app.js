// Lilyscan review UI. No build step: plain ES modules served by the API.

const $ = (id) => document.getElementById(id);
const SVG_NS = "http://www.w3.org/2000/svg";
const LINE_HEIGHT = 18; // must match .gutter / #code line-height in style.css

async function api(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    let message = response.statusText;
    try {
      message = (await response.json()).detail || message;
    } catch {
      /* not JSON */
    }
    throw new Error(`${response.status} ${message}`);
  }
  return response;
}
const getJSON = async (path, options) => (await api(path, options)).json();

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (value !== undefined && value !== null) node.setAttribute(key, value);
  }
  node.append(...children);
  return node;
}

// --- routing -------------------------------------------------------------------

let pollTimer = null;
function stopPolling() {
  clearTimeout(pollTimer);
  pollTimer = null;
}

function route() {
  stopPolling();
  const match = location.hash.match(/^#\/job\/([\w-]+)/);
  if (match) showJob(match[1]);
  else showHome();
}
window.addEventListener("hashchange", route);
window.addEventListener("beforeunload", (event) => {
  if (state.dirty.size) event.preventDefault();
});

getJSON("/api/health")
  .then((h) => ($("versions").textContent = `LilyPond ${h.lilypond} · Audiveris ${h.audiveris}`))
  .catch(() => {});

// --- home: upload and job list ---------------------------------------------------

function showHome() {
  $("job").hidden = true;
  $("home").hidden = false;
  refreshJobs();
}

async function refreshJobs() {
  const jobs = await getJSON("/api/jobs");
  const rows = $("job-rows");
  rows.replaceChildren(
    ...jobs.map((job) =>
      el(
        "tr",
        {},
        el("td", {}, el("a", { href: `#/job/${job.id}` }, job.id)),
        el("td", {}, jobLabel(job)),
        el("td", {}, el("span", { class: `pill ${job.status}` }, `${job.status}${job.status === "done" ? "" : ` · ${job.stage}`}`)),
        el("td", { class: "muted" }, job.created_at.replace("T", " ").replace("+00:00", " UTC")),
      ),
    ),
  );
  if (!jobs.length) rows.append(el("tr", {}, el("td", { colspan: 4, class: "muted" }, "No jobs yet.")));
  renderCombine(jobs);
  const busy = jobs.some((j) => j.status === "queued" || j.status === "running");
  if (busy && !$("home").hidden) pollTimer = setTimeout(refreshJobs, 3000);
}

function jobLabel(job) {
  if (job.options && job.options.combine) return `Combined: ${job.options.combine.title || "score"}`;
  return job.inputs.map((n) => n.replace(/^\d\d-/, "")).join(", ");
}

// Score combiner: parts of finished jobs, each optionally transposed.
const INTERVALS = [
  ["", "as written"],
  ["M2", "up a major 2nd"],
  ["-M2", "down a major 2nd"],
  ["m3", "up a minor 3rd"],
  ["-m3", "down a minor 3rd"],
  ["P4", "up a 4th"],
  ["-P4", "down a 4th"],
  ["P5", "up a 5th"],
  ["-P5", "down a 5th"],
  ["P8", "up an octave"],
  ["-P8", "down an octave"],
];
const combineParts = new Map(); // job id -> parts, fetched once

async function renderCombine(jobs) {
  const done = jobs.filter((j) => j.status === "done").slice(0, 20);
  const rows = [];
  for (const job of done) {
    if (!combineParts.has(job.id)) {
      try {
        combineParts.set(job.id, await getJSON(`/api/jobs/${job.id}/parts`));
      } catch {
        combineParts.set(job.id, []);
      }
    }
    for (const part of combineParts.get(job.id)) {
      const box = el("input", { type: "checkbox" });
      const choices = part.transpose_semitones ? [...INTERVALS, ["concert", "at concert pitch"]] : INTERVALS;
      const interval = el("select", {}, ...choices.map(([v, label]) => el("option", { value: v }, label)));
      box.addEventListener("change", updateCombine);
      const row = el(
        "label",
        { class: "row" },
        box,
        `${jobLabel(job)} · ${part.name} (${part.measures} measures)`,
        interval,
      );
      row.dataset.job = job.id;
      row.dataset.part = part.id;
      rows.push(row);
    }
  }
  $("combine-parts").replaceChildren(...(rows.length ? rows : [el("span", { class: "muted" }, "No finished jobs yet.")]));
  updateCombine();
}

function updateCombine() {
  $("combine-btn").disabled = !$("combine-parts").querySelector("input:checked");
}

$("combine-btn").addEventListener("click", async () => {
  const parts = [...$("combine-parts").querySelectorAll(".row")]
    .filter((row) => row.querySelector("input").checked)
    .map((row) => {
      const transpose = row.querySelector("select").value;
      return { job: row.dataset.job, part: row.dataset.part, ...(transpose ? { transpose } : {}) };
    });
  $("combine-btn").disabled = true;
  $("combine-msg").textContent = "Combining...";
  try {
    const response = await api("/api/scores", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: $("combine-title").value.trim() || null, parts }),
    });
    const job = await response.json();
    $("combine-msg").textContent = "";
    location.hash = `#/job/${job.id}`;
  } catch (err) {
    $("combine-msg").textContent = `Could not combine: ${err.message}`;
    updateCombine();
  }
});

const drop = $("drop");
const fileInput = $("files");
function showChosen() {
  const names = [...fileInput.files].map((f) => f.name);
  $("chosen").textContent = names.length ? `Selected: ${names.join(", ")}` : "";
  $("upload-btn").disabled = !names.length;
}
fileInput.addEventListener("change", showChosen);
for (const type of ["dragenter", "dragover"]) {
  drop.addEventListener(type, (e) => {
    e.preventDefault();
    drop.classList.add("over");
  });
}
for (const type of ["dragleave", "drop"]) drop.addEventListener(type, () => drop.classList.remove("over"));
drop.addEventListener("drop", (e) => {
  e.preventDefault();
  fileInput.files = e.dataTransfer.files;
  showChosen();
});

$("upload-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = new FormData();
  for (const f of fileInput.files) form.append("files", f);
  const languages = $("ocr").value.trim();
  if (languages) form.append("ocr_languages", languages);
  form.append("straighten", $("straighten").checked ? "true" : "false");
  $("upload-btn").disabled = true;
  $("upload-msg").textContent = "Uploading...";
  try {
    const job = await getJSON("/api/jobs", { method: "POST", body: form });
    fileInput.value = "";
    showChosen();
    $("upload-msg").textContent = "";
    location.hash = `#/job/${job.id}`;
  } catch (err) {
    $("upload-msg").textContent = `Upload failed: ${err.message}`;
    $("upload-btn").disabled = false;
  }
});

// --- job view --------------------------------------------------------------------

const state = {
  id: null,
  review: null,
  byId: new Map(), // measure id -> measure
  byLine: new Map(), // "file:line" -> measure id
  files: {}, // relative path -> text
  dirty: new Set(),
  current: null, // file shown in the editor
  selected: null,
};

const STAGES = {
  upload: "Queued",
  prepare: "Straightening the pages",
  engine: "Recognizing the music (Audiveris, about 30 s per page)",
  import: "Reading the engine's result",
  repair: "Repairing parts, clefs, and rhythm",
  lilypond: "Engraving with LilyPond and running checks",
  recompile: "Recompiling your edits",
  combine: "Combining the parts",
};

function showJob(id) {
  $("home").hidden = true;
  $("job").hidden = false;
  if (state.id !== id) {
    Object.assign(state, { id, review: null, files: {}, dirty: new Set(), current: null, selected: null });
    $("panes").hidden = true;
  }
  pollJob();
}

async function pollJob() {
  let job;
  try {
    job = await getJSON(`/api/jobs/${state.id}`);
  } catch (err) {
    showError(err.message);
    return;
  }
  $("job-title").textContent = jobLabel(job);
  const status = $("job-status");
  status.className = `pill ${job.status}`;
  status.textContent = job.status;
  const busy = job.status === "queued" || job.status === "running";
  $("job-wait").hidden = !busy;
  $("job-wait-text").textContent = STAGES[job.stage] || job.stage;
  $("save-btn").disabled = busy || !state.dirty.size;
  showError(job.status === "failed" ? job.error : null);
  if (busy) {
    pollTimer = setTimeout(pollJob, 2000);
    return;
  }
  if (job.report) await loadReview();
}

function showError(message) {
  $("job-error").hidden = !message;
  $("job-error").textContent = message || "";
}

async function loadReview() {
  let review;
  try {
    review = await getJSON(`/api/jobs/${state.id}/review`);
  } catch (err) {
    showError(`No review available for this job (${err.message}).`);
    return;
  }
  state.review = review;
  state.byId = new Map(review.measures.map((m) => [m.id, m]));
  state.byLine = new Map(review.measures.filter((m) => m.file).map((m) => [`${m.file}:${m.line}`, m.id]));
  $("panes").hidden = false;
  renderBadges(review.qa);
  renderDownloads();
  renderSource(review);
  renderNotes(review);
  renderRepairs(review);
  renderReviewList(review);
  await Promise.all([renderScore(review), loadFiles(review)]);
  if (state.selected && state.byId.has(state.selected)) select(state.selected);
}

function renderBadges(qa) {
  const checks = (qa && qa.checks) || [];
  $("qa-badges").replaceChildren(
    ...checks.map((c) =>
      el("span", { class: `badge ${c.passed ? "pass" : "fail"}`, title: `${c.name}: ${c.summary}` }, c.id),
    ),
  );
}

function renderDownloads() {
  const base = `/api/jobs/${state.id}/download`;
  $("downloads").replaceChildren(
    el("a", { href: `${base}/ly` }, ".ly project"),
    el("a", { href: `${base}/pdf` }, "PDF"),
    el("a", { href: `${base}/midi` }, "MIDI"),
    el("a", { href: `${base}/musicxml`, title: "The engine's MusicXML, before your edits" }, "MusicXML"),
  );
}

// Source pane: the page images the engine analysed, with measure and event boxes.
function confidenceClass(c) {
  if (c === null || c === undefined) return "unknown";
  if (c >= 0.8) return "high";
  if (c >= 0.5) return "mid";
  return "low";
}

function renderSource(review) {
  const source = $("source");
  source.replaceChildren();
  if (!review.pages.length) {
    const why = review.combined_from
      ? "A combined score has no pages of its own: open the jobs its parts came from to see them."
      : "No page images: the engine did not save its project for this job.";
    source.append(el("p", { class: "muted" }, why));
    return;
  }
  review.pages.forEach((page, index) => {
    const svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("class", "boxes");
    svg.setAttribute("viewBox", `0 0 ${page.width} ${page.height}`);
    svg.setAttribute("preserveAspectRatio", "none");
    for (const m of review.measures) {
      for (const e of m.events) {
        if (!e.bbox || e.bbox.page !== index) continue;
        svg.append(rect(e.bbox, `event ${confidenceClass(e.confidence)}`));
      }
      if (m.bbox && m.bbox.page === index) {
        const r = rect(m.bbox, `measure${m.issues.length ? " issue" : ""}`, 6);
        r.dataset.id = m.id;
        r.addEventListener("click", () => select(m.id, "source"));
        r.append(document.createElementNS(SVG_NS, "title"));
        r.firstChild.textContent = `${m.part_name} · m. ${m.number}`;
        svg.append(r);
      }
    }
    const img = el("img", { src: `/api/jobs/${state.id}/pages/${index + 1}.png`, alt: `Page ${index + 1}` });
    source.append(el("div", { class: "page" }, img, svg));
  });
}

function rect(b, cls, pad = 2) {
  const r = document.createElementNS(SVG_NS, "rect");
  r.setAttribute("x", b.x - pad);
  r.setAttribute("y", b.y - pad);
  r.setAttribute("width", b.w + 2 * pad);
  r.setAttribute("height", b.h + 2 * pad);
  r.setAttribute("class", cls);
  return r;
}

// Score pane: LilyPond SVG with point-and-click links (textedit://file:line:col:col).
function linkTarget(href) {
  const match = decodeURIComponent(href || "").match(/^textedit:\/\/(.*):(\d+):(\d+):(\d+)$/);
  if (!match) return null;
  const path = match[1].replace(/\\/g, "/");
  const at = path.lastIndexOf("/ly/");
  return at < 0 ? null : `${path.slice(at + 4)}:${match[2]}`;
}

async function renderScore(review) {
  const score = $("score");
  const pages = (review.lilypond && review.lilypond.svg) || [];
  const texts = await Promise.all(
    pages.map((p) => api(`/api/jobs/${state.id}/files/${p}?v=${Date.now()}`).then((r) => r.text())),
  );
  score.replaceChildren();
  for (const text of texts) {
    const holder = el("div");
    holder.innerHTML = text;
    for (const a of holder.querySelectorAll("a")) {
      const href = a.getAttribute("xlink:href") || a.getAttribute("href");
      const id = state.byLine.get(linkTarget(href));
      a.removeAttribute("xlink:href");
      a.removeAttribute("href");
      if (!id) continue;
      a.dataset.mid = id;
      a.addEventListener("click", (e) => {
        e.preventDefault();
        select(id, "score");
      });
    }
    score.append(...holder.childNodes);
  }
  if (!texts.length) score.append(el("p", { class: "muted" }, "Nothing engraved yet."));
}

// Editor pane.
const code = $("code");
const gutter = $("gutter");

async function loadFiles(review) {
  const names = ((review.lilypond && review.lilypond.files) || [])
    .map((f) => f.replace(/^ly\//, ""))
    .filter((f) => f.endsWith(".ly"));
  const order = (f) => (f.startsWith("parts/") ? 0 : f === "chords.ly" ? 1 : f === "main.ly" ? 2 : 3);
  names.sort((a, b) => order(a) - order(b) || a.localeCompare(b));
  const texts = await Promise.all(
    names.map((f) => api(`/api/jobs/${state.id}/ly/${f}`).then((r) => r.text())),
  );
  state.files = Object.fromEntries(names.map((f, i) => [f, texts[i]]));
  state.dirty.clear();
  if (!state.current || !(state.current in state.files)) state.current = names[0] || null;
  renderTabs();
  showFile(state.current);
}

function renderTabs() {
  $("tabs").replaceChildren(
    ...Object.keys(state.files).map((f) =>
      el(
        "button",
        {
          class: `${f === state.current ? "active" : ""} ${state.dirty.has(f) ? "dirty" : ""}`,
          title: f,
          onclick: () => showFile(f),
        },
        f.replace(/^parts\//, "").replace(/\.ly$/, ""),
      ),
    ),
  );
  $("save-btn").disabled = !state.dirty.size;
}

function showFile(name) {
  state.current = name;
  code.value = name ? state.files[name] : "";
  updateGutter();
  renderTabs();
}

function updateGutter() {
  const lines = code.value.split("\n").length;
  gutter.textContent = Array.from({ length: lines }, (_, i) => i + 1).join("\n");
  gutter.scrollTop = code.scrollTop;
}

code.addEventListener("input", () => {
  if (!state.current) return;
  state.files[state.current] = code.value;
  state.dirty.add(state.current);
  updateGutter();
  renderTabs();
});
code.addEventListener("scroll", () => (gutter.scrollTop = code.scrollTop));
for (const type of ["click", "keyup"]) {
  code.addEventListener(type, () => {
    if (!state.current) return;
    const line = code.value.slice(0, code.selectionStart).split("\n").length;
    // The measure whose music line is at or above the caret.
    for (let n = line; n > 0; n--) {
      const id = state.byLine.get(`${state.current}:${n}`);
      if (id) {
        if (id !== state.selected) select(id, "editor");
        return;
      }
    }
  });
}

function revealLine(line) {
  const lines = code.value.split("\n");
  const start = lines.slice(0, line - 1).reduce((n, l) => n + l.length + 1, 0);
  code.focus({ preventScroll: true });
  code.setSelectionRange(start, start + (lines[line - 1] || "").length);
  code.scrollTop = Math.max(0, (line - 4) * LINE_HEIGHT);
  gutter.scrollTop = code.scrollTop;
}

$("save-btn").addEventListener("click", async () => {
  $("save-btn").disabled = true;
  try {
    for (const name of state.dirty) {
      await api(`/api/jobs/${state.id}/ly/${name}`, {
        method: "PUT",
        headers: { "Content-Type": "text/plain; charset=utf-8" },
        body: state.files[name],
      });
    }
    state.dirty.clear();
    renderTabs();
    await api(`/api/jobs/${state.id}/recompile`, { method: "POST" });
    pollJob();
  } catch (err) {
    showError(`Saving failed: ${err.message}`);
    $("save-btn").disabled = false;
  }
});

// Review list: measures with problems, most urgent first.
const LABELS = {
  compile: "does not compile",
  "bar-check": "bar check",
  rhythm: "rhythm",
  range: "range",
  repaired: "repaired",
  "low-confidence": "low confidence",
};

// Stage 1 quality warnings, and which engine run was kept for a scan.
function renderNotes(review) {
  const notes = [];
  for (const page of review.prepare || []) {
    const where = page.page ? `${page.input}, page ${page.page + 1}` : page.input;
    if (page.error) notes.push(`${where}: could not be read (${page.error}); it was left out.`);
    for (const w of page.warnings || []) notes.push(`${where}: ${w}.`);
  }
  const kept = (review.alternatives || []).find((a) => a.chosen);
  if (kept && kept.pages === "uploaded") {
    notes.push("The pages as uploaded read better than the straightened ones, so those were kept.");
  }
  $("job-notes").hidden = !notes.length;
  $("job-notes").replaceChildren(el("strong", {}, "Input quality"), el("ul", {}, ...notes.map((n) => el("li", {}, n))));
}

// Staff-wide repairs (merged parts, octave clefs) are listed once, above the review list.
function renderRepairs(review) {
  const repairs = review.repairs || [];
  $("repairs").hidden = !repairs.length;
  $("repairs").replaceChildren(
    el("strong", {}, `Repaired automatically (${repairs.length})`),
    el("ul", {}, ...repairs.map((r) => el("li", {}, el("span", { class: "kind" }, r.rule), ` · ${r.detail}`))),
  );
}

function renderReviewList(review) {
  $("review-count").textContent = `${review.review.length} measure(s)`;
  $("review").replaceChildren(
    ...review.review.map((id) => {
      const m = state.byId.get(id);
      const kinds = [...new Set(m.issues.map((i) => LABELS[i.kind] || i.kind))].join(", ");
      const item = el(
        "li",
        { title: m.issues.map((i) => i.detail).join("\n"), onclick: () => select(id, "review") },
        el("span", { class: "kind" }, `m. ${m.number}`),
        ` · ${m.part_name}${m.staff > 1 ? ` (staff ${m.staff})` : ""} · ${kinds}`,
      );
      item.dataset.id = id;
      return item;
    }),
  );
}

// --- selection: one measure, highlighted in every pane --------------------------

function select(id, origin) {
  const m = state.byId.get(id);
  if (!m) return;
  state.selected = id;

  for (const node of document.querySelectorAll(".sel")) node.classList.remove("sel");

  const box = document.querySelector(`#source rect.measure[data-id="${CSS.escape(id)}"]`);
  if (box) {
    box.classList.add("sel");
    if (origin !== "source") box.scrollIntoView({ block: "center", behavior: "smooth" });
  }
  const notes = document.querySelectorAll(`#score a[data-mid="${CSS.escape(id)}"]`);
  notes.forEach((a) => a.classList.add("sel"));
  if (notes.length && origin !== "score") notes[0].scrollIntoView({ block: "center", behavior: "smooth" });

  const item = document.querySelector(`#review li[data-id="${CSS.escape(id)}"]`);
  if (item) {
    item.classList.add("sel");
    if (origin !== "review") item.scrollIntoView({ block: "nearest" });
  }

  if (origin !== "editor" && m.file && m.file in state.files) {
    if (state.current !== m.file) showFile(m.file);
    revealLine(m.line);
  }

  const problems = m.issues.map((i) => `${LABELS[i.kind] || i.kind}: ${i.detail}`).join(" · ");
  const confidence = m.min_confidence === null ? "" : ` · lowest confidence ${m.min_confidence}`;
  $("selection").textContent = `${m.part_name}${m.staff > 1 ? ` staff ${m.staff}` : ""}, measure ${m.number}${confidence}${problems ? ` · ${problems}` : ""}`;
}

route();
