/* Same-origin cookie sessions; access tokens are never persisted or put in a URL. */
(function (root) {
  "use strict";
  const nativeFetch = root.fetch.bind(root);
  let authenticated = false, csrf = "", generation = 0;
  const listeners = [];
  function changed(value) {
    const was = authenticated;
    authenticated = value;
    if (!value) csrf = "";
    if (was !== value) listeners.slice().forEach(fn => fn(value));
  }
  function localUrl(input) {
    if (typeof input !== "string" && !(input instanceof URL)) throw new TypeError("请求地址无效");
    const url = new URL(String(input), root.location.href);
    if ((url.protocol !== "http:" && url.protocol !== "https:") || url.origin !== root.location.origin || url.username || url.password) {
      throw new Error("拒绝向其他来源发送客户端请求");
    }
    return url.pathname + url.search;
  }
  function options(input, mutating) {
    const opts = Object.assign({}, input || {});
    opts.headers = new Headers(opts.headers || {});
    opts.credentials = "same-origin";
    opts.mode = "same-origin";
    opts.redirect = "error";
    if (mutating) opts.headers.set("X-SIP-CSRF", csrf);
    return opts;
  }
  async function readSession() {
    const ticket = ++generation;
    try {
      const response = await nativeFetch("/api/session", options({method: "GET"}, false));
      const data = response.ok ? await response.json() : null;
      if (ticket !== generation) return authenticated;
      if (data && data.authenticated === true && typeof data.csrfToken === "string" && data.csrfToken.length) {
        csrf = data.csrfToken;
        changed(true);
      } else changed(false);
    } catch (e) { if (ticket === generation) changed(false); }
    return authenticated;
  }
  async function login(token) {
    if (typeof token !== "string" || !token.length || token.length > 512) throw new Error("请输入有效的访问令牌");
    const ticket = ++generation;
    const response = await nativeFetch("/api/session/login", options({
      method: "POST", headers: {"Content-Type": "application/json;charset=UTF-8", "X-SIP-Login": "1"},
      body: JSON.stringify({token: token})
    }, false));
    const data = response.ok ? await response.json() : null;
    if (ticket !== generation) return false;
    if (!data || data.authenticated !== true || typeof data.csrfToken !== "string" || !data.csrfToken.length) {
      changed(false);
      throw new Error("登录失败，请检查本实例访问令牌");
    }
    csrf = data.csrfToken;
    changed(true);
    return true;
  }
  async function secureFetch(input, supplied) {
    const url = localUrl(input);
    if (!authenticated) throw new Error("请先登录本实例 Web 客户端");
    const method = String((supplied || {}).method || "GET").toUpperCase();
    const ticket = generation;
    const response = await nativeFetch(url, options(Object.assign({}, supplied || {}, {method: method}), method !== "GET" && method !== "HEAD"));
    if (ticket === generation && (response.status === 401 || response.status === 403)) {
      ++generation;
      changed(false);
    }
    return response;
  }
  async function logout() {
    try {
      if (authenticated) await secureFetch("/api/session/logout", {method: "POST"});
    } finally { ++generation; changed(false); }
  }
  root.WebAccess = Object.freeze({
    init: readSession, fetch: secureFetch, login: login, logout: logout,
    isAuthenticated: () => authenticated,
    onChanged: function (listener) {
      if (typeof listener !== "function") throw new TypeError("无效的会话监听器");
      listeners.push(listener);
      return () => { const index = listeners.indexOf(listener); if (index >= 0) listeners.splice(index, 1); };
    }
  });
}(window));
