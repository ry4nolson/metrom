(function () {
  var cfg = window.METROM || {};

  function applyConfig() {
    document.querySelectorAll("[data-email]").forEach(function (el) {
      var email = cfg.supportEmail || "support@metrom.dev";
      if (el.tagName === "A") {
        el.href = "mailto:" + email;
        if (!el.textContent.trim() || el.getAttribute("data-email") === "fill") {
          el.textContent = email;
        }
      } else {
        el.textContent = email;
      }
    });

    document.querySelectorAll("[data-domain]").forEach(function (el) {
      el.textContent = cfg.domain || "metrom.dev";
    });

    document.querySelectorAll("[data-operator]").forEach(function (el) {
      el.textContent = cfg.operator || "Ryan Olson";
    });

    document.querySelectorAll("[data-effective]").forEach(function (el) {
      el.textContent = cfg.effectiveDate || "";
    });

    document.querySelectorAll("[data-year]").forEach(function (el) {
      el.textContent = String(new Date().getFullYear());
    });

    var repoUrl = cfg.repoUrl || "https://github.com/ry4nolson/metrom";
    document.querySelectorAll("[data-repo]").forEach(function (el) {
      if (el.tagName === "A") {
        el.href = repoUrl;
        el.target = "_blank";
        el.rel = "noopener noreferrer";
        if (el.getAttribute("data-repo") === "fill") {
          el.textContent = repoUrl.replace(/^https?:\/\//, "");
        }
      } else {
        el.textContent = repoUrl;
      }
    });
  }

  function initHeader() {
    var header = document.querySelector(".site-header");
    if (!header) return;

    function onScroll() {
      header.classList.toggle("is-scrolled", window.scrollY > 24);
    }

    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  function markCurrentNav() {
    var path = window.location.pathname.replace(/\/index\.html$/, "/");
    document.querySelectorAll(".nav-links a, .footer-nav a").forEach(function (a) {
      var href = a.getAttribute("href");
      if (!href || href.startsWith("mailto:") || href.startsWith("http")) return;
      try {
        var url = new URL(href, window.location.origin);
        var linkPath = url.pathname.replace(/\/index\.html$/, "/");
        if (linkPath === path || (path.endsWith(linkPath) && linkPath !== "/")) {
          a.setAttribute("aria-current", "page");
        }
      } catch (_) {
        /* ignore */
      }
    });
  }

  applyConfig();
  initHeader();
  markCurrentNav();
})();
