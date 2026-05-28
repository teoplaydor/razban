// Lightweight version-number injector — pulls the current version label from
// version.json (same manifest the desktop app polls). The actual download
// happens on GitHub Releases; this page just links out.
(function () {
  fetch("version.json", { cache: "no-store" })
    .then(function (r) { return r.json(); })
    .then(function (v) {
      var label = v && v.latestVersion ? v.latestVersion : "—";
      document.querySelectorAll("[data-version]").forEach(function (el) {
        el.textContent = label;
      });
    })
    .catch(function () { /* leave fallback "0.1.0" inline in HTML */ });
})();
