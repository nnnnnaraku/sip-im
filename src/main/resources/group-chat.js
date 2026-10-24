/* Group text UI. No SIP target mutation and no conference/media ownership. */
(function (root) {
  "use strict";
  function createGroupChat(env) {
    const doc = env.document;
    const get = id => doc.getElementById(id);
    const groups = new Map();
    const drafts = new Map();
    const pending = new Set();
    let me = "", contacts = [], selectedId = null, visible = false;
    let choices = [];
    let snapshotEpoch = null, snapshotSequence = 0;
    const retiredEpochs = new Set();
    const statusNames = { ACTIVE: "已加入", INVITED: "待加入", JOINING: "正在加入", LEFT: "已退出", LEAVE_PENDING: "正在退出", CLOSED: "已解散" };
    const deliveryNames = { PENDING: "等待结果", SIP_ACCEPTED: "SIP 已接受（未确认应用接收）", DELIVERED: "对端已接收", FAILED: "失败", TIMEOUT: "超时", REMOVED: "成员已退出" };
    function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }
    function text(tag, value, className) {
      const el = doc.createElement(tag);
      el.textContent = value == null ? "" : String(value);
      if (className) el.className = className;
      return el;
    }
    function hide(id, hidden) { get(id).classList[hidden ? "add" : "remove"]("hidden"); }
    function announce(value) { get("groupNotice").textContent = value || ""; }
    function setView(on) {
      visible = on;
      hide("groupPanel", !on);
      ["chat", "privateDialbar", "privateComposer"].forEach(id => hide(id, on));
    }
    function showPrivate() {
      if (selectedId) drafts.set(selectedId, get("groupText").value);
      setView(false);
      renderList();
    }
    function renderChoices() {
      const box = get("groupContactChoices");
      const old = new Set(choices.filter(c => c.input.checked).map(c => c.user));
      clear(box); choices = [];
      const seen = new Set();
      contacts.forEach(c => {
        const user = String(c.user || "");
        if (!user || user === me || seen.has(user)) return;
        seen.add(user);
        const label = doc.createElement("label");
        label.className = "group-choice";
        const input = doc.createElement("input");
        input.type = "checkbox"; input.value = user; input.checked = old.has(user);
        label.appendChild(input);
        label.appendChild(text("span", user + (c.online ? "（在线）" : "（状态未知或离线）")));
        box.appendChild(label); choices.push({ user: user, input: input });
      });
      if (!choices.length) box.appendChild(text("p", "暂无联系人；等待原有联系人列表加载后再创建群。", "group-muted"));
    }
    function renderList() {
      const box = get("groups"); clear(box);
      groups.forEach(g => {
        const button = text("button", g.name + " · " + (statusNames[g.localStatus] || g.localStatus), "group-entry");
        button.type = "button";
        if (visible && g.id === selectedId) button.classList.add("active");
        button.onclick = () => selectGroup(g.id);
        box.appendChild(button);
      });
      if (!groups.size) box.appendChild(text("p", "暂无群组", "group-muted"));
    }
    function renderSelected() {
      const g = groups.get(selectedId);
      if (!g) { if (visible) showPrivate(); return; }
      get("groupTitle").textContent = g.name;
      get("groupIdentity").textContent = "Group ID：" + g.id + " · " + (statusNames[g.localStatus] || g.localStatus) + " · 群主 " + g.ownerId;
      get("groupError").textContent = g.lastError ? "最近错误：" + g.lastError : "";
      get("groupControlStatus").textContent = g.controlStatus ? "最近控制结果：" + String(g.controlStatus) : "";
      const members = get("groupMembers"); clear(members);
      (g.members || []).forEach(m => members.appendChild(text("li", m.userId + (m.userId === me ? "（我）" : "") + "：" + (statusNames[m.status] || m.status))));
      const messages = get("groupMessages");
      const wasBottom = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 60;
      const previousScroll = messages.scrollTop;
      clear(messages);
      (g.messages || []).forEach(m => {
        const row = doc.createElement("div");
        row.className = "row " + (m.outgoing ? "out" : "in");
        const bubble = doc.createElement("div"); bubble.className = "bubble";
        const when = Number.isFinite(Number(m.timestamp)) ? new Date(Number(m.timestamp)).toLocaleTimeString() : "";
        bubble.appendChild(text("div", m.senderId + (when ? " · " + when : ""), "who"));
        bubble.appendChild(text("div", m.text));
        let retry = false;
        Object.keys(m.deliveries || {}).forEach(user => {
          const d = m.deliveries[user];
          bubble.appendChild(text("div", user + "：" + (deliveryNames[d.status] || d.status) + (d.detail ? " · " + d.detail : ""), "group-delivery"));
          if (d.status === "FAILED" || d.status === "TIMEOUT") retry = true;
        });
        if (m.outgoing && retry && g.canSend) {
          const button = text("button", "重试失败收件人", "group-action"); button.type = "button";
          button.disabled = pending.has(g.id);
          const id = g.id, messageId = m.id;
          button.onclick = () => action("retry", { groupId: id, messageId: messageId });
          bubble.appendChild(button);
        }
        row.appendChild(bubble); messages.appendChild(row);
      });
      if (!(g.messages || []).length) messages.appendChild(text("div", "此群暂无文字消息。邀请成员确认加入后即可发送。", "sys"));
      messages.scrollTop = wasBottom ? messages.scrollHeight : previousScroll;
      get("groupText").disabled = !g.canSend;
      get("btnGroupSend").disabled = !g.canSend || pending.has(g.id);
      get("groupText").placeholder = g.canSend ? "群文字消息（最多 400 字节 UTF-8，过长时服务端提示）" : "当前成员状态不能发送";
      hide("btnGroupJoin", g.localStatus !== "INVITED");
      get("btnGroupJoin").disabled = pending.has(g.id);
      hide("btnGroupLeave", g.closed || g.localStatus === "LEFT" || g.localStatus === "CLOSED");
      get("btnGroupLeave").textContent = g.ownerId === me ? "退出并解散群" : (g.localStatus === "INVITED" ? "拒绝邀请" : "退出群组");
      get("btnGroupLeave").disabled = pending.has(g.id) || g.localStatus === "LEAVE_PENDING";
      get("btnGroupSync").disabled = pending.has(g.id);
    }
    function selectGroup(id) {
      if (!groups.has(id)) return;
      if (selectedId) drafts.set(selectedId, get("groupText").value);
      selectedId = id;
      get("groupText").value = drafts.get(id) || "";
      announce(""); setView(true); renderList(); renderSelected();
    }
    function applySnapshots(envelope) {
      const epoch = envelope.snapshotEpoch, sequence = envelope.snapshotSequence, list = envelope.groups;
      if (!Array.isArray(list) || typeof epoch !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(epoch)
          || !Number.isSafeInteger(sequence) || sequence < 1) throw new Error("无效群快照：缺少有效的构建会话及顺序标识，请核对客户端版本。");
      if (epoch !== snapshotEpoch) {
        if (retiredEpochs.has(epoch)) return false;
        if (snapshotEpoch !== null) retiredEpochs.add(snapshotEpoch);
        snapshotEpoch = epoch; snapshotSequence = 0;
      }
      // One server-generated order is shared by GET, POST and SSE. A delayed
      // response must never resurrect departed members or erase delivery ACKs.
      if (sequence <= snapshotSequence) return false;
      snapshotSequence = sequence;
      groups.clear();
      list.forEach(g => {
        if (g && typeof g.id === "string" && typeof g.name === "string") groups.set(g.id, g);
      });
      renderList(); renderSelected();
      return true;
    }
    async function request(path, body) {
      const response = await env.fetch(path, body === undefined ? undefined : {
        method: "POST", headers: { "Content-Type": "application/json; charset=utf-8" }, body: JSON.stringify(body)
      });
      const data = await response.json();
      if (!data || !data.ok || response.ok === false) throw new Error(data && data.error || "群操作失败");
      applySnapshots(data);
      return data;
    }
    async function action(name, body) {
      const id = body.groupId;
      if (id && pending.has(id)) return;
      if (id) pending.add(id);
      announce(""); renderSelected();
      try { return await request("/api/groups/" + name, body); }
      catch (e) { announce(e.message); return null; }
      finally { if (id) pending.delete(id); renderSelected(); }
    }
    async function send() {
      const id = selectedId, g = groups.get(id), value = get("groupText").value;
      if (!visible || !g || !g.canSend || pending.has(id) || !value.trim()) return;
      // Capture the immutable group ID before any asynchronous operation or contact switch.
      const result = await action("send", { groupId: id, text: value });
      if (result) {
        drafts.delete(id);
        if (selectedId === id) get("groupText").value = "";
      }
    }
    get("btnGroupCreate").onclick = () => { hide("groupCreateForm", false); renderChoices(); };
    get("btnGroupCancel").onclick = () => { hide("groupCreateForm", true); get("groupCreateError").textContent = ""; };
    get("btnGroupConfirm").onclick = async () => {
      const name = get("groupName").value.trim();
      const members = choices.filter(c => c.input.checked).map(c => c.user);
      const error = get("groupCreateError"); error.textContent = "";
      if (!name || members.length < 2 || members.length > 7) { error.textContent = "请输入群名称，并选择 2～7 位联系人（连同本人共 3～8 人）。"; return; }
      const before = new Set(groups.keys());
      get("btnGroupConfirm").disabled = true;
      try {
        await request("/api/groups/create", { name: name, members: members });
        hide("groupCreateForm", true); get("groupName").value = "";
        const created = Array.from(groups.values()).find(g => !before.has(g.id) && g.ownerId === me);
        if (created) selectGroup(created.id);
      } catch (e) { error.textContent = e.message; }
      finally { get("btnGroupConfirm").disabled = false; }
    };
    get("btnGroupPrivate").onclick = showPrivate;
    get("btnGroupJoin").onclick = () => action("join", { groupId: selectedId });
    get("btnGroupSync").onclick = () => action("sync", { groupId: selectedId });
    get("btnGroupLeave").onclick = () => {
      const g = groups.get(selectedId);
      if (!g) return;
      if (g.ownerId === me && env.confirm && !env.confirm("群主退出会解散该群，确定继续？")) return;
      action("leave", { groupId: g.id });
    };
    get("btnGroupSend").onclick = send;
    get("groupText").addEventListener("input", () => { if (selectedId) drafts.set(selectedId, get("groupText").value); });
    get("groupText").addEventListener("keydown", e => { if (e.key === "Enter" && !e.isComposing) { e.preventDefault(); send(); } });
    return {
      setIdentity: function (identity) { me = String(identity || ""); renderChoices(); renderSelected(); },
      updateContacts: function (list) { contacts = Array.isArray(list) ? list.slice() : []; renderChoices(); },
      showPrivate: showPrivate,
      handleEvent: function (event) {
        if (!event || event.t !== "group-state") return false;
        try { applySnapshots(event); } catch (e) { announce(e.message); }
        return true;
      },
      load: async function () { try { await request("/api/groups"); } catch (e) { announce(e.message); } }
    };
  }
  if (typeof module !== "undefined" && module.exports) module.exports = { createGroupChat: createGroupChat };
  if (root && root.document) {
    // 必须复用 WebAccess 的 fetch：它会带上 X-SIP-CSRF，否则所有群操作会被拒绝（CSRF_REQUIRED）。
    var groupFetch = (root.WebAccess && typeof root.WebAccess.fetch === "function")
      ? root.WebAccess.fetch.bind(root.WebAccess)
      : root.fetch.bind(root);
    root.GroupChat = createGroupChat({ document: root.document, fetch: groupFetch, confirm: root.confirm.bind(root) });
    root.GroupChat.load();
    root.setInterval(function () { root.GroupChat.load(); }, 5000);
  }
})(typeof window !== "undefined" ? window : null);
