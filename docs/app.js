// Lightweight version-number injector — pulls the current version labels from
// version.json (the same manifest the desktop/Android apps poll for updates).
// Downloads happen on GitHub Releases; this page just links out.
(function () {
  function setText(attr, value) {
    document.querySelectorAll("[" + attr + "]").forEach(function (el) { el.textContent = value; });
  }
  fetch("version.json", { cache: "no-store" })
    .then(function (r) { return r.json(); })
    .then(function (v) {
      setText("data-version", (v && v.latestVersion) || "—");

      var a = (v && v.android) || {};
      if (a.version) setText("data-android-version", a.version);
      var card = document.querySelector("[data-android-card]");
      var link = document.querySelector("[data-android-url]");
      if (a.available && a.url) {
        if (link) link.setAttribute("href", a.url);
        setText("data-android-status", "Доступно");
      } else {
        // Not released yet — keep the cell but point at the releases page and
        // mark it as in-progress.
        setText("data-android-status", "В разработке");
        if (link) {
          link.setAttribute("href", "https://github.com/teoplaydor/razban/releases");
          link.textContent = "Скоро";
          link.classList.add("btn-disabled");
        }
      }
    })
    .catch(function () { /* leave inline fallbacks */ });
})();
