"use strict";
// Offline DOM/fetch/SSE fakes only. No real HTTP, SIP, files, microphone, or camera.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const base = path.resolve(__dirname, "../../main/resources");
const accessSource = fs.readFileSync(path.join(base, "web-access.js"), "utf8");
const html = fs.readFileSync(path.join(base, "chat.html"), "utf8");
const inline = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
const tests = [];
function test(name, run) { tests.push([name, run]); }
function response(status, data) { return {status, ok: status >= 200 && status < 300, json: async () => data}; }
async function flush() { for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve)); }
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.events = {}; this.className = ""; this.value = ""; this.files = []; this._text = ""; }
  appendChild(child) { this.children.push(child); child.parent = this; return child; }
  set textContent(value) { this.children = []; this._text = String(value); }
  get textContent() { return this._text + this.children.map(c => c.textContent).join(""); }
  set innerHTML(value) { throw new Error("Unexpected dynamic innerHTML: " + value); }
  addEventListener(name, handler) { this.events[name] = handler; }
  removeAttribute(name) { delete this[name]; }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(c => c !== this); }
  querySelector(name) { return this.children.find(c => c.tagName === name) || null; }
  click() { if (this.onclick) return this.onclick(); }
  get classList() {
    const element = this;
    return {contains: name => element.className.split(/\s+/).includes(name),
      add: name => { if (!element.classList.contains(name)) element.className += " " + name; },
      remove: name => { element.className = element.className.split(/\s+/).filter(x => x !== name).join(" "); }};
  }
}
function fixture(withPage = false, config = {}) {
  const calls = [], events = [], intervals = new Map(), timeouts = new Map(), nodes = new Map();
  let nextTimer = 0;
  const routes = Object.assign({
    "/api/session": () => response(200, {ok: true, authenticated: false}),
    "/api/session/login": () => response(200, {ok: true, authenticated: true, csrfToken: "test-csrf"}),
    "/api/session/logout": () => response(200, {ok: true}),
    "/api/state": () => response(200, {registered: true, me: "100", peer: "101"}),
    "/api/contacts": () => response(200, {contacts: [{user: "101", online: true}]})
  }, config.routes || {});
  function fetch(url, opts) {
    calls.push({url, opts});
    const handler = routes[url];
    return Promise.resolve(handler ? handler(opts) : response(200, {ok: true}));
  }
  class FakeEventSource {
    constructor(url) { this.url = url; this.closed = false; events.push(this); }
    close() { this.closed = true; }
    send(data) { this.onmessage({data: JSON.stringify(data)}); }
  }
  const document = {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, new Element("div")); return nodes.get(id); },
    createElement: name => new Element(name), createTextNode: text => { const n = new Element("#text"); n.textContent = text; return n; }
  };
  const window = {fetch, location: {href: "http://127.0.0.1:18081/", origin: "http://127.0.0.1:18081"}};
  for (const key of ["localStorage", "sessionStorage"]) Object.defineProperty(window, key, {get() { throw new Error("Token storage forbidden"); }});
  const context = vm.createContext({window, document, URL, Headers, EventSource: FakeEventSource, console,
    setInterval(fn, ms) { const id = ++nextTimer; intervals.set(id, {fn, ms}); return id; }, clearInterval: id => intervals.delete(id),
    setTimeout(fn, ms) { const id = ++nextTimer; timeouts.set(id, {fn, ms}); return id; }, clearTimeout: id => timeouts.delete(id)});
  vm.runInContext(accessSource, context, {filename: "web-access.js"});
  context.WebAccess = window.WebAccess;
  if (withPage) vm.runInContext(inline, context, {filename: "chat.html"});
  return {api: window.WebAccess, calls, routes, events, intervals, timeouts, nodes, context, document};
}
async function login(f) { await f.api.login("synthetic-access-token"); await flush(); }

test("init fetches only session without token in URL", async () => {
  const f = fixture(); assert.equal(await f.api.init(), false); assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].url, "/api/session"); assert.equal(f.calls[0].opts.credentials, "same-origin");
  assert.equal(f.calls[0].opts.mode, "same-origin");
});
test("unauthenticated control request is blocked before network", async () => {
  const f = fixture(); await assert.rejects(f.api.fetch("/api/call", {method: "POST"}), /先登录/); assert.equal(f.calls.length, 0);
});
test("login uses JSON marker header and does not put token in URL", async () => {
  const f = fixture(); await login(f); const call = f.calls[0]; assert.equal(call.url, "/api/session/login");
  assert.equal(call.opts.headers.get("X-SIP-Login"), "1"); assert.match(call.opts.headers.get("Content-Type"), /application\/json/);
  assert.equal(JSON.parse(call.opts.body).token, "synthetic-access-token"); assert.equal(f.api.isAuthenticated(), true);
});
test("wrong login clears state and reports generic error", async () => {
  const f = fixture(false, {routes: {"/api/session/login": () => response(401, {error: "sensitive"})}});
  await assert.rejects(f.api.login("wrong-token"), /登录失败/); assert.equal(f.api.isAuthenticated(), false);
});
test("login rejects empty and oversized tokens without request", async () => {
  const f = fixture(); await assert.rejects(f.api.login("")); await assert.rejects(f.api.login("a".repeat(513))); assert.equal(f.calls.length, 0);
});
test("session restore requires authenticated flag and nonempty CSRF", async () => {
  const f = fixture(false, {routes: {"/api/session": () => response(200, {authenticated: true, csrfToken: ""})}});
  assert.equal(await f.api.init(), false); f.routes["/api/session"] = () => response(200, {authenticated: true, csrfToken: "restored"});
  assert.equal(await f.api.init(), true); await f.api.fetch("/api/send", {method: "POST"}); assert.equal(f.calls[2].opts.headers.get("X-SIP-CSRF"), "restored");
});
test("mutating API includes server CSRF and preserves raw text", async () => {
  const f = fixture(); await login(f); await f.api.fetch("/api/send", {method: "post", body: "中文 <hello> & world"});
  const call = f.calls[1]; assert.equal(call.opts.body, "中文 <hello> & world"); assert.equal(call.opts.method, "POST"); assert.equal(call.opts.headers.get("X-SIP-CSRF"), "test-csrf");
});
test("upload binary body is passed unchanged", async () => {
  const f = fixture(); await login(f); const bytes = new Uint8Array([0, 255, 3]).buffer;
  await f.api.fetch("/api/file?type=IMG&name=test.png", {method: "POST", body: bytes});
  assert.equal(f.calls[1].opts.body, bytes); assert.equal(f.calls[1].opts.headers.get("X-SIP-CSRF"), "test-csrf");
});
test("GET does not expose CSRF header and authentication is cookie based", async () => {
  const f = fixture(); await login(f); await f.api.fetch("/api/state");
  assert.equal(f.calls[1].opts.headers.has("X-SIP-CSRF"), false); assert.equal(f.calls[1].opts.headers.has("Authorization"), false);
});
test("caller cannot force cross-origin mode, credentials, or redirects", async () => {
  const f = fixture(); await login(f); await f.api.fetch("/api/state", {credentials: "include", mode: "cors", redirect: "follow"});
  assert.equal(f.calls[1].opts.credentials, "same-origin"); assert.equal(f.calls[1].opts.mode, "same-origin"); assert.equal(f.calls[1].opts.redirect, "error");
});
test("external and credential-bearing URLs are blocked before network", async () => {
  const f = fixture(); await login(f);
  for (const url of ["https://evil.invalid/api/send", "//evil.invalid/api/send", "http://127.0.0.1:18082/api/send", "javascript:alert(1)", "data:text/plain,x", "http://user:pass@127.0.0.1:18081/api/send"])
    await assert.rejects(f.api.fetch(url, {method: "POST"}));
  assert.equal(f.calls.length, 1);
});
test("relative same-origin and URL objects remain compatible", async () => {
  const f = fixture(); await login(f); await f.api.fetch(new URL("http://127.0.0.1:18081/api/state")); assert.equal(f.calls[1].url, "/api/state");
});
test("401 and 403 invalidate session; further requests are blocked", async () => {
  for (const status of [401, 403]) {
    const f = fixture(); await login(f); f.routes["/api/state"] = () => response(status, {ok: false});
    assert.equal((await f.api.fetch("/api/state")).status, status); assert.equal(f.api.isAuthenticated(), false);
    await assert.rejects(f.api.fetch("/api/state")); assert.equal(f.calls.length, 2);
  }
});
test("ordinary API failure does not masquerade as lost authentication", async () => {
  const f = fixture(); await login(f); f.routes["/api/file"] = () => response(413, {ok: false});
  assert.equal((await f.api.fetch("/api/file", {method: "POST"})).status, 413); assert.equal(f.api.isAuthenticated(), true);
});
test("logout sends CSRF then clears local session even on failure", async () => {
  const f = fixture(); await login(f); f.routes["/api/session/logout"] = () => { throw new Error("offline"); };
  await assert.rejects(f.api.logout()); assert.equal(f.api.isAuthenticated(), false); assert.equal(f.calls[1].opts.headers.get("X-SIP-CSRF"), "test-csrf");
});
test("old response cannot revoke a newly logged-in session", async () => {
  const f = fixture(); await login(f); let resolve;
  f.routes["/api/state"] = () => new Promise(r => { resolve = r; });
  const pending = f.api.fetch("/api/state"); await f.api.login("new-access-token"); resolve(response(401, {})); await pending;
  assert.equal(f.api.isAuthenticated(), true);
});
test("API exposes no token or CSRF getter and never writes browser storage", async () => {
  const f = fixture(); await login(f); assert.deepEqual(Object.keys(f.api).sort(), ["fetch", "init", "isAuthenticated", "login", "logout", "onChanged"].sort());
});
test("page before login does not poll APIs or open SSE", async () => {
  const f = fixture(true); await flush(); assert.deepEqual(f.calls.map(c => c.url), ["/api/session"]); assert.equal(f.events.length, 0); assert.equal(f.intervals.size, 0);
  assert.match(f.nodes.get("chatApp").className, /hidden/);
});
test("page login starts state, contacts, SSE and two polling timers", async () => {
  const f = fixture(true); await flush(); await login(f);
  assert.equal(f.events.length, 1); assert.equal(f.events[0].url, "/api/stream"); assert.equal(f.intervals.size, 2);
  assert.equal(f.nodes.get("chatApp").className, "app"); assert.match(f.nodes.get("accessPanel").className, /hidden/);
});
test("login form clears password input before asynchronous login", async () => {
  const f = fixture(true); await flush(); f.document.getElementById("accessToken").value = "secret-from-input";
  const pending = f.nodes.get("accessForm").events.submit({preventDefault() {}});
  assert.equal(f.nodes.get("accessToken").value, ""); await pending;
  assert.equal(f.calls.find(c => c.url === "/api/session/login").opts.body.includes("secret-from-input"), true);
});
test("page logout closes SSE, polling and video preview", async () => {
  const f = fixture(true); await flush(); await login(f); const stream = f.events[0];
  stream.send({t: "video", local: true, img: "data:image/jpeg;base64,AAA="}); assert.ok(f.nodes.get("vLocal").src);
  await f.nodes.get("btnLogout").onclick(); assert.equal(stream.closed, true); assert.equal(f.intervals.size, 0); assert.equal(f.nodes.get("vLocal").src, undefined);
  assert.match(f.nodes.get("chatApp").className, /hidden/);
});
test("lost session closes SSE and timers immediately", async () => {
  const f = fixture(true); await flush(); await login(f); f.routes["/api/state"] = () => response(401, {});
  await f.api.fetch("/api/state"); assert.equal(f.events[0].closed, true); assert.equal(f.intervals.size, 0);
});
test("contact IDs render as plain text rather than markup", async () => {
  const hostile = '<img src=x onerror="alert(1)">';
  const f = fixture(true, {routes: {"/api/contacts": () => response(200, {contacts: [{user: hostile, online: true}]})}});
  await flush(); await login(f); const contact = f.nodes.get("contacts").children[0];
  assert.equal(contact.children[1].textContent, hostile); assert.equal(contact.children[1].tagName, "span");
});
test("normal private send, dial, recording and hangup use protected fetch", async () => {
  const f = fixture(true); await flush(); await login(f);
  f.nodes.get("text").value = "hello"; f.nodes.get("btnSend").onclick(); await flush();
  await f.nodes.get("btnCallVoice").onclick(); await f.nodes.get("btnCallVideo").onclick(); await f.nodes.get("btnHang").onclick();
  await f.nodes.get("btnVoice").onclick(); await f.nodes.get("btnVoice").onclick();
  const mutations = f.calls.filter(c => c.opts.method === "POST" && c.url !== "/api/session/login");
  assert.deepEqual(mutations.map(c => c.url), ["/api/send", "/api/call?video=0", "/api/call?video=1", "/api/hangup", "/api/record/start", "/api/record/stop"]);
  assert.ok(mutations.every(c => c.opts.headers.get("X-SIP-CSRF") === "test-csrf"));
});
test("file events preserve authenticated cookie URLs without URL tokens", async () => {
  const f = fixture(true); await flush(); await login(f);
  for (const type of ["IMG", "VOICE", "VIDEO"]) f.events[0].send({t: "file", out: false, peer: "101", ftype: type, name: "example", url: "/files/safe-file"});
  const rows = f.nodes.get("chat").children; assert.equal(rows.length, 3);
  assert.deepEqual(rows.map(row => row.children[0].children[1].children[0].src), ["/files/safe-file", "/files/safe-file", "/files/safe-file"]);
});
test("SSE renders private text, video and conference names safely", async () => {
  const f = fixture(true); await flush(); await login(f); const stream = f.events[0];
  stream.send({t: "msg", out: false, peer: "101", text: "<script>bad</script>"});
  assert.match(f.nodes.get("chat").textContent, /<script>bad<\/script>/);
  const name = '102"] <unsafe>';
  stream.send({t: "cvideo", from: name, img: "data:image/jpeg;base64,AAA="}); assert.equal(f.nodes.get("confPanel").children.length, 1);
  stream.send({t: "cvideo-left", from: name}); assert.equal(f.nodes.get("confPanel").children.length, 0);
});
test("SSE session error checks authentication before reconnection", async () => {
  const f = fixture(true); await flush(); await login(f); f.events[0].onerror();
  assert.equal(f.events[0].closed, true); assert.equal(f.timeouts.size, 1);
  const retry = [...f.timeouts.values()][0]; await retry.fn(); await flush(); assert.equal(f.api.isAuthenticated(), false); assert.equal(f.events.length, 1);
});
test("future group API can use the same authenticated wrapper", async () => {
  const f = fixture(); await login(f); const body = JSON.stringify({name: "小组", members: ["101", "102"]});
  await f.api.fetch("/api/groups/create", {method: "POST", headers: {"Content-Type": "application/json"}, body});
  assert.equal(f.calls[1].opts.body, body); assert.equal(f.calls[1].opts.headers.get("X-SIP-CSRF"), "test-csrf");
});
(async () => {
  let failures = 0;
  for (const [name, run] of tests) {
    try { await run(); console.log("PASS " + name); }
    catch (e) { failures++; console.error("FAIL " + name + "\n" + e.stack); }
  }
  console.log((tests.length - failures) + " PASS, " + failures + " FAIL");
  if (failures) process.exitCode = 1;
})();
