"use strict";

var vwParams = new URLSearchParams(location.search);
var vwName = vwParams.get("name") || "file";
var vwExt = (vwParams.get("ext") || "").toLowerCase();

function vwStatus(msg) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    el.className = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status";
  el.textContent = msg;
  el.style.display = "";
}

function vwStatusDone() {
  var el = document.getElementById("vw-status");
  if (el) el.style.display = "none";
}

function vwError(title, detail) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status vw-error";
  el.style.display = "";
  el.innerHTML = "";
  var t = document.createElement("div");
  t.className = "vw-error-title";
  t.textContent = title;
  var d = document.createElement("div");
  d.className = "vw-error-detail";
  d.textContent = detail || "";
  el.appendChild(t);
  el.appendChild(d);
}

/*
 * Publish the height the reader can actually see as --vw-fit, for the viewers that
 * centre a document short enough to fit on the screen.
 *
 * 100vh is not reliably that height. These pages carry no viewport meta, so the WebView
 * lays out at 980 CSS px and scales that to the screen, and the initial containing block
 * is fixed at the 980-wide geometry. A page whose content is wider than 980 overflows,
 * loadWithOverviewMode zooms further out to fit it, and the reader can then see more CSS
 * px of height than 100vh reports. Measured on the phone: an ordinary widescreen deck is
 * 1279 px wide and shows 2467 px of height against a 100vh that stays at 1886.
 *
 * window.innerHeight is the number that is right, and it is the one to use rather than
 * visualViewport.height, which shrinks as the reader pinches in. Centring against that
 * would move the document under the gesture; innerHeight holds still through a pinch and
 * changes only when the screen does.
 *
 * Call this once the document is in the DOM, because it is the content overflowing that
 * changes the answer and no resize is fired when that happens. The listener bound here is
 * for rotation, which does fire one, and is the only word the page gets: ViewerActivity
 * declares configChanges and never rebuilds. It binds on first call rather than on load
 * so that the viewers with nothing to centre do not carry it.
 */
var vwFitBound = false;

function vwFitHeight() {
  if (!vwFitBound) {
    vwFitBound = true;
    addEventListener("resize", vwFitHeight);
  }
  document.documentElement.style.setProperty("--vw-fit", window.innerHeight + "px");
}

function vwDocUrl() {
  return "/doc/file" + (vwExt ? "." + vwExt : "");
}

/* Fetch the document being viewed. kind: "buffer" | "text" */
function vwFetchDoc(kind) {
  return fetch(vwDocUrl()).then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");
    return kind === "text" ? r.text() : r.arrayBuffer();
  });
}

/* Anything can be asked for as text, including a multi-gigabyte binary, so the
   text viewer reads a page at a time and lets the reader ask for the next one
   rather than committing the whole file to the DOM up front. */
var VW_TEXT_PAGE = 5 * 1024 * 1024;

/* Encoding of a file from its byte order mark, if it has one. The decoder
   strips the mark itself. */
function vwEncodingOf(bytes) {
  if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) return "utf-16le";
  if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) return "utf-16be";
  return "utf-8";
}

/*
 * Opens the document for paged reading. Resolves to { next }, where next()
 * resolves to { text, bytes, done } for the next VW_TEXT_PAGE bytes.
 */
function vwOpenText() {
  return fetch(vwDocUrl()).then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");

    // One streaming decoder across every page, so a multi-byte character split
    // by a page boundary still comes out whole. Decoding stays lenient, so an
    // unfamiliar encoding degrades to replacement characters rather than an error
    var decoder = null;
    function decode(bytes, last) {
      if (!decoder) decoder = new TextDecoder(vwEncodingOf(bytes));
      return { text: decoder.decode(bytes, { stream: !last }), bytes: bytes.length, done: last };
    }

    // A WebView too old for the Streams API has to buffer the whole body
    if (!r.body || !r.body.getReader) {
      return r.arrayBuffer().then(function (buf) {
        var all = new Uint8Array(buf);
        var off = 0;
        return {
          next: function () {
            var end = Math.min(off + VW_TEXT_PAGE, all.length);
            var page = all.subarray(off, end);
            off = end;
            return Promise.resolve(decode(page, off >= all.length));
          }
        };
      });
    }

    var reader = r.body.getReader();
    var pending = [];
    var held = 0;
    var ended = false;

    return {
      next: function () {
        // Overshoot the page by a chunk rather than stopping level with it, so
        // a file that ends exactly on the boundary is known to be finished and
        // does not offer an empty page
        function pump() {
          if (ended || held > VW_TEXT_PAGE) return Promise.resolve();
          return reader.read().then(function (res) {
            if (res.done) { ended = true; return; }
            pending.push(res.value);
            held += res.value.length;
            return pump();
          });
        }
        return pump().then(function () {
          var take = Math.min(held, VW_TEXT_PAGE);
          var page = new Uint8Array(take);
          var off = 0;
          while (off < take) {
            var chunk = pending[0];
            var n = Math.min(chunk.length, take - off);
            page.set(chunk.subarray(0, n), off);
            off += n;
            if (n === chunk.length) pending.shift();
            else pending[0] = chunk.subarray(n);
          }
          held -= take;
          return decode(page, ended && held === 0);
        });
      }
    };
  });
}

/* Byte count as a short human size, for "showing the first N" notices. */
function vwFormatSize(bytes) {
  var mb = bytes / (1024 * 1024);
  if (mb < 1) return Math.max(1, Math.round(bytes / 1024)) + " KB";
  return (mb >= 10 ? Math.round(mb) : Math.round(mb * 10) / 10) + " MB";
}

window.onerror = function (message) {
  vwError("Something went wrong while rendering", String(message));
};
