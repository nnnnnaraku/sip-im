"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const { createGroupChat } = require("../../main/resources/group-chat.js");
const html = fs.readFileSync(path.join(__dirname, "../../main/resources/chat.html"), "utf8");
class Element {
  constructor(tag) {
    this.tagName = tag.toUpperCase(); this.children = []; this.className = "";
    this.value = ""; this.checked = false; this.disabled = false; this.scrollHeight = 100; this.scrollTop = 0; this.clientHeight = 100;
    this._text = ""; this.listeners = {}; this.dataset = {}; this.innerHtmlWrites = 0;
    const self = this;
    this.classList = {
      contains: c => self.className.split(/\s+/).includes(c),
      add: c => { if (!self.classList.contains(c)) self.className = (self.className + " " + c).trim(); },
      remove: c => { self.className = self.className.split(/\s+/).filter(v => v && v !== c).join(" "); }
    };
  }
  get firstChild() { return this.children[0] || null; }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; return child; }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(c => c.textContent).join(""); }
  set innerHTML(value) { this.innerHtmlWrites++; this._text = String(value); this.children = []; }
  get innerHTML() { return this._text; }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  querySelector() { return null; }
  click() { return this.onclick && this.onclick(); }
}
function documentFake() {
  const nodes = new Map(), created = [];
  Array.from(html.matchAll(/<([a-z]+)[^>]*\sid="([^"]+)"[^>]*>/g)).forEach(m => {
    const el = new Element(m[1]); const cl = m[0].match(/class="([^"]*)"/); el.className = cl ? cl[1] : ""; nodes.set(m[2], el);
  });
  return { created, getElementById: id => { assert(nodes.has(id), "Unknown DOM ID " + id); return nodes.get(id); }, createElement: tag => { const el = new Element(tag); created.push(el); return el; }, createTextNode: value => { const el = new Element("#text"); el.textContent = value; return el; } };
}
function snapshot(id, options) {
  return Object.assign({ id, name: "群 " + id, ownerId: "100", revision: 1, closed: false, localStatus: "ACTIVE", canSend: true,
    members: ["100", "101", "102"].map(userId => ({ userId, status: "ACTIVE" })), messages: [], lastError: "", controlStatus: "" }, options || {});
}
const EPOCH = "11111111-1111-1111-1111-111111111111";
const NEXT_EPOCH = "22222222-2222-2222-2222-222222222222";
function fixture(initial) {
  const document = documentFake(), calls = [];
  let sequence = 0;
  const response = (groups, metadata) => Object.assign({ ok: true, groups, snapshotEpoch: EPOCH, snapshotSequence: ++sequence }, metadata || {});
  let answer = async () => response(initial || []);
  const fetch = async (url, options) => { calls.push({ url, options, body: options && JSON.parse(options.body) }); return { ok: true, json: async () => answer(url, options) }; };
  const ui = createGroupChat({ document, fetch, confirm: () => true });
  ui.setIdentity("100");
  const $ = id => document.getElementById(id);
  return { document, calls, fetch, ui, $, response, setAnswer: value => { answer = value; }, event: (groups, metadata) => ui.handleEvent(Object.assign(response(groups, metadata), { t: "group-state" })), select: index => $("groups").children[index || 0].click() };
}
function descendants(el, predicate) { return el.children.flatMap(c => (predicate(c) ? [c] : []).concat(descendants(c, predicate))); }
const tests = [];
const test = (name, fn) => tests.push({ name, fn });
const tick = () => new Promise(resolve => setImmediate(resolve));

test("initial GET restores groups without changing private view", async () => {
  const f = fixture([snapshot("a")]); await f.ui.load();
  assert.equal(f.calls[0].url, "/api/groups"); assert.equal(f.calls[0].options, undefined);
  assert.equal(f.$("groups").children.length, 1); assert(f.$("groupPanel").classList.contains("hidden"));
});
test("selecting a group isolates private controls and never mutates peer", () => {
  const f = fixture(); f.$("peer").textContent = "101"; f.event([snapshot("a")]); f.select();
  assert(f.$("chat").classList.contains("hidden")); assert(f.$("privateComposer").classList.contains("hidden"));
  assert(!f.$("groupPanel").classList.contains("hidden")); assert.equal(f.$("peer").textContent, "101"); assert.equal(f.calls.length, 0);
});
test("group history is rendered by group ID without cross-group text", () => {
  const f = fixture();
  f.event([snapshot("a", { messages: [{ id: "m1", senderId: "101", text: "ONLY-A", timestamp: 1 }] }), snapshot("b", { messages: [{ id: "m2", senderId: "102", text: "ONLY-B", timestamp: 2 }] })]);
  f.select(0); assert(f.$("groupMessages").textContent.includes("ONLY-A")); assert(!f.$("groupMessages").textContent.includes("ONLY-B"));
  f.select(1); assert(f.$("groupMessages").textContent.includes("ONLY-B")); assert(!f.$("groupMessages").textContent.includes("ONLY-A"));
});
test("send captures immutable group ID while view changes during request", async () => {
  const f = fixture(); f.event([snapshot("a"), snapshot("b")]); f.select(0);
  let finish; const waiting = new Promise(resolve => { finish = resolve; });
  f.setAnswer(async () => { await waiting; return f.response([snapshot("a"), snapshot("b")]); });
  f.$("groupText").value = "中文 English <>& \" \\ 😀";
  const sending = f.$("btnGroupSend").click();
  f.ui.showPrivate(); f.select(1); f.$("groupText").value = "draft-b"; finish(); await sending;
  assert.deepEqual(f.calls.map(c => c.url), ["/api/groups/send"]);
  assert.equal(f.calls[0].body.groupId, "a"); assert.equal(f.calls[0].body.text, "中文 English <>& \" \\ 😀");
  assert.equal(f.$("groupText").value, "draft-b");
});
test("message and failure text are rendered as text nodes, never HTML", () => {
  const evil = '<img src=x onerror="alert(1)">'; const f = fixture();
  f.event([snapshot("a", { name: evil, lastError: evil, messages: [{ id: "m", senderId: evil, text: evil, deliveries: { "101": { status: "FAILED", detail: evil } } }] })]); f.select();
  assert(f.$("groupMessages").textContent.includes(evil)); assert.equal(f.$("groupTitle").textContent, evil);
  assert.equal(descendants(f.$("groupPanel"), n => n.tagName === "IMG").length, 0);
  assert(f.document.created.every(el => el.innerHtmlWrites === 0), "Untrusted group data must never be assigned to innerHTML");
});
test("left group disables sending and does not attempt delivery", async () => {
  const f = fixture(); f.event([snapshot("a", { localStatus: "LEFT", canSend: false })]); f.select();
  f.$("groupText").value = "blocked"; await f.$("btnGroupSend").click();
  assert(f.$("groupText").disabled); assert(f.$("btnGroupSend").disabled); assert.equal(f.calls.length, 0);
});
test("invitation exposes explicit join and matching group ID", async () => {
  const f = fixture(); f.event([snapshot("a", { localStatus: "INVITED", canSend: false })]); f.select();
  assert(!f.$("btnGroupJoin").classList.contains("hidden")); await f.$("btnGroupJoin").click();
  assert.equal(f.calls[0].url, "/api/groups/join"); assert.deepEqual(f.calls[0].body, { groupId: "a" });
});
test("failed recipient retry sends original message ID and group ID", async () => {
  const f = fixture(); f.event([snapshot("a", { messages: [{ id: "original-id", senderId: "100", text: "hello", outgoing: true, deliveries: { "101": { status: "DELIVERED" }, "102": { status: "TIMEOUT" } } }] })]); f.select();
  const retry = descendants(f.$("groupMessages"), n => n.tagName === "BUTTON")[0]; assert(retry);
  await retry.click(); assert.equal(f.calls[0].url, "/api/groups/retry"); assert.deepEqual(f.calls[0].body, { groupId: "a", messageId: "original-id" });
});
test("sending error preserves draft and displays exact server reason", async () => {
  const f = fixture(); f.event([snapshot("a")]); f.select(); f.$("groupText").value = "draft";
  f.setAnswer(async () => ({ ok: false, error: "UTF-8 消息过长" })); await f.$("btnGroupSend").click();
  assert.equal(f.$("groupText").value, "draft"); assert.equal(f.$("groupNotice").textContent, "UTF-8 消息过长"); assert(!f.$("btnGroupSend").disabled);
});
test("creation lists unique other contacts, validates minimum and sends selection", async () => {
  const f = fixture(); f.ui.updateContacts([{ user: "100" }, { user: "101", online: true }, { user: "101" }, { user: "102" }]);
  f.$("btnGroupCreate").click(); f.$("groupName").value = "三人讨论";
  await f.$("btnGroupConfirm").click(); assert.equal(f.calls.length, 0); assert(f.$("groupCreateError").textContent.includes("2～7"));
  const inputs = descendants(f.$("groupContactChoices"), n => n.tagName === "INPUT"); assert.deepEqual(inputs.map(i => i.value), ["101", "102"]);
  inputs.forEach(i => { i.checked = true; }); f.setAnswer(async () => f.response([snapshot("new")]));
  await f.$("btnGroupConfirm").click(); assert.deepEqual(f.calls[0].body, { name: "三人讨论", members: ["101", "102"] });
  assert.equal(f.$("groupTitle").textContent, "群 new");
});
test("pending leave is read-only and synchronization targets same group", async () => {
  const f = fixture(); f.event([snapshot("a", { localStatus: "LEAVE_PENDING", canSend: false })]); f.select();
  assert(f.$("btnGroupLeave").disabled); await f.$("btnGroupSync").click();
  assert.equal(f.calls[0].url, "/api/groups/sync"); assert.deepEqual(f.calls[0].body, { groupId: "a" });
});
test("IME composition does not trigger send and normal Enter does", async () => {
  const f = fixture(); f.event([snapshot("a")]); f.select(); f.$("groupText").value = "中文";
  f.$("groupText").listeners.keydown({ key: "Enter", isComposing: true, preventDefault: () => {} }); assert.equal(f.calls.length, 0);
  f.$("groupText").listeners.keydown({ key: "Enter", isComposing: false, preventDefault: () => {} }); await tick(); assert.equal(f.calls.length, 1);
});
test("return to private restores original media controls without requests", () => {
  const f = fixture(); f.event([snapshot("a")]); f.select(); f.$("btnGroupPrivate").click();
  assert(!f.$("chat").classList.contains("hidden")); assert(!f.$("privateComposer").classList.contains("hidden")); assert(!f.$("privateDialbar").classList.contains("hidden"));
  ["btnImg", "btnVoice", "btnVideo", "btnCallVoice", "btnCallVideo", "btnHang"].forEach(id => assert(f.$(id))); assert.equal(f.calls.length, 0);
});
test("group SSE and private SSE remain separated in actual chat script", async () => {
  const f = fixture(); const sources = [];
  const fetch = async (url, options) => {
    f.calls.push({ url, options });
    const data = url === "/api/state" ? { registered: true, me: "100", peer: "101" } : url === "/api/contacts" ? { contacts: [{ user: "101", online: true }] } : { ok: true };
    return { ok: true, json: async () => data };
  };
  const context = { document: f.document, window: { GroupChat: f.ui }, fetch, EventSource: class { constructor() { sources.push(this); } }, setInterval: () => 1, setTimeout: () => 1, clearInterval: () => {}, console };
  const inline = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1]; vm.runInNewContext(inline, context); await tick();
  assert.equal(f.$("contacts").children.length, 1, "Original contacts must still render");
  const peerBefore = f.$("peer").textContent;
  sources[0].onmessage({ data: JSON.stringify(Object.assign(f.response([snapshot("a", { messages: [{ id: "m", senderId: "102", text: "GROUP-ONLY" }] })]), { t: "group-state" })) });
  f.select(); sources[0].onmessage({ data: JSON.stringify({ t: "msg", out: false, peer: "101", text: "PRIVATE-ONLY" }) });
  assert(f.$("chat").textContent.includes("PRIVATE-ONLY")); assert(!f.$("chat").textContent.includes("GROUP-ONLY"));
  assert(f.$("groupMessages").textContent.includes("GROUP-ONLY")); assert(!f.$("groupMessages").textContent.includes("PRIVATE-ONLY")); assert.equal(f.$("peer").textContent, peerBefore);
  await f.$("contacts").children[0].click(); await tick();
  assert(f.$("groupPanel").classList.contains("hidden"));
  assert(f.calls.some(c => c.url === "/api/setpeer?user=101"));
  f.$("text").value = "private text"; f.$("btnSend").click(); await tick();
  assert(f.calls.some(c => c.url === "/api/send" && c.options.body === "private text"));
  f.$("btnCallVoice").click(); await tick(); assert(f.calls.some(c => c.url === "/api/call?video=0"));
});
test("browser bootstrap polls read-only group snapshot every five seconds", async () => {
  const f = fixture(); const timers = [];
  const window = { document: f.document, fetch: f.fetch, confirm: () => true, setInterval: (callback, ms) => { timers.push({ callback, ms }); } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../../main/resources/group-chat.js"), "utf8"), { window }); await tick();
  assert.equal(timers.length, 1); assert.equal(timers[0].ms, 5000); timers[0].callback(); await tick();
  assert.deepEqual(f.calls.map(c => c.url), ["/api/groups", "/api/groups"]);
});
test("delayed GET never erases a newer SSE delivery confirmation", async () => {
  const f = fixture(); let finish;
  const old = f.response([snapshot("a", { messages: [{ id: "m", senderId: "100", text: "delayed", outgoing: true, deliveries: { "101": { status: "PENDING" } } }] })]);
  const waiting = new Promise(resolve => { finish = resolve; });
  f.setAnswer(async () => { await waiting; return old; });
  const loading = f.ui.load();
  f.event([snapshot("a", { messages: [{ id: "m", senderId: "100", text: "delayed", outgoing: true, deliveries: { "101": { status: "DELIVERED" } } }] })]);
  f.select(); finish(); await loading;
  assert(f.$("groupMessages").textContent.includes("对端已接收")); assert(!f.$("groupMessages").textContent.includes("等待结果"));
});
test("delayed SSE never revives membership after newer HTTP leave snapshot", async () => {
  const f = fixture(); const old = f.response([snapshot("a")]);
  f.setAnswer(async () => f.response([snapshot("a", { localStatus: "LEFT", canSend: false })])); await f.ui.load(); f.select();
  f.ui.handleEvent(Object.assign(old, { t: "group-state" }));
  f.$("groupText").value = "must not send"; const calls = f.calls.length; await f.$("btnGroupSend").click();
  assert(f.$("groupText").disabled); assert.equal(f.calls.length, calls); assert(f.$("groupIdentity").textContent.includes("已退出"));
});
test("service epoch changes accept reset sequence and retire old epoch forever", () => {
  const f = fixture(); f.event([snapshot("old")], { snapshotSequence: 50 });
  f.event([snapshot("new")], { snapshotEpoch: NEXT_EPOCH, snapshotSequence: 1 });
  assert(f.$("groups").textContent.includes("群 new"));
  f.event([snapshot("old")], { snapshotEpoch: EPOCH, snapshotSequence: 100 });
  assert(f.$("groups").textContent.includes("群 new")); assert(!f.$("groups").textContent.includes("群 old"));
});
test("missing or invalid snapshot order fails explicitly instead of downgrading state", () => {
  const f = fixture(); f.event([snapshot("a", { localStatus: "LEFT", canSend: false })]); f.select();
  f.ui.handleEvent({ t: "group-state", groups: [snapshot("a")] });
  assert(f.$("groupNotice").textContent.includes("无效群快照")); assert(f.$("groupText").disabled);
  f.event([snapshot("a")], { snapshotSequence: 0 }); assert(f.$("groupText").disabled);
  f.event([snapshot("a")], { snapshotEpoch: "invalid", snapshotSequence: 10 }); assert(f.$("groupText").disabled);
});
test("equal sequence is idempotent and cannot change public state", () => {
  const f = fixture(); f.event([snapshot("a", { localStatus: "JOINING", canSend: false })], { snapshotSequence: 10 }); f.select();
  f.event([snapshot("a")], { snapshotSequence: 10 });
  assert(f.$("groupText").disabled); assert(f.$("groupIdentity").textContent.includes("正在加入"));
});
(async () => {
  let failed = 0;
  for (const t of tests) {
    try { await t.fn(); console.log("PASS " + t.name); }
    catch (error) { failed++; console.error("FAIL " + t.name); console.error(error.stack); }
  }
  console.log(tests.length + " tests, " + (tests.length - failed) + " PASS, " + failed + " FAIL");
  process.exitCode = failed ? 1 : 0;
})();
