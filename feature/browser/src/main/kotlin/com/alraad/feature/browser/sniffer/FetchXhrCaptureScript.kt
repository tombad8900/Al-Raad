package com.alraad.feature.browser.sniffer

/** Installs best-effort fetch/XHR observers; never reads response bodies or response headers. */
internal object FetchXhrCaptureScript {
    fun build(token: String): String {
        require(token.matches(Regex("[0-9a-f-]{36}")))
        return """
            (function(token) {
              if (window.__alraadCaptureInstalled === token) return;
              window.__alraadCaptureInstalled = token;
              var allowed = {accept: true, "content-type": true, range: true};
              function report(rawUrl, headers, method) {
                try {
                  var url = new URL(rawUrl, location.href);
                  if (url.protocol !== "http:" && url.protocol !== "https:") return;
                  var safe = {};
                  if (headers) headers.forEach(function(value, name) {
                    name = name.toLowerCase();
                    if (allowed[name] && value.length <= 1024) safe[name] = value;
                  });
                  var payload = JSON.stringify({
                    token: token, pageUrl: location.href, url: url.href,
                    method: String(method || "GET").slice(0, 16), headers: safe
                  });
                  if (payload.length <= 16384 && window.AlRaadSniffer) {
                    window.AlRaadSniffer.onRequestObserved(payload);
                  }
                } catch (_) {}
              }
              if (typeof window.fetch === "function" && typeof Headers === "function") {
                var originalFetch = window.fetch;
                window.fetch = function(input, init) {
                  try {
                    var request = input instanceof Request ? input : null;
                    var headers = new Headers(request ? request.headers : undefined);
                    if (init && init.headers) new Headers(init.headers).forEach(function(v, k) {
                      headers.set(k, v);
                    });
                    report(request ? request.url : String(input),
                      headers, (init && init.method) || (request && request.method) || "GET");
                  } catch (_) {}
                  return originalFetch.apply(this, arguments);
                };
              }
              if (window.XMLHttpRequest) {
                var open = XMLHttpRequest.prototype.open;
                var setHeader = XMLHttpRequest.prototype.setRequestHeader;
                var send = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.__alraadMethod = method;
                  this.__alraadUrl = url;
                  this.__alraadHeaders = {};
                  return open.apply(this, arguments);
                };
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                  var key = String(name).toLowerCase();
                  if (allowed[key] && String(value).length <= 1024) {
                    this.__alraadHeaders[key] = String(value);
                  }
                  return setHeader.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  try {
                    var headers = new Headers(this.__alraadHeaders || {});
                    report(this.__alraadUrl, headers, this.__alraadMethod);
                  } catch (_) {}
                  return send.apply(this, arguments);
                };
              }
            })("$token");
        """.trimIndent()
    }
}
