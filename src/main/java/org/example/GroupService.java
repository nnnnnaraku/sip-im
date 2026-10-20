package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Small, process-local group chat. Membership authority is the creator; text is direct fan-out. */
public final class GroupService {
    static final int MAX_GROUPS = 16;
    static final int MAX_MESSAGES = 256;
    static final int MAX_OPERATIONS = 1024;
    static final long ACK_TIMEOUT_MS = 15000;
    private final Object lock = new Object();
    private final String localUser;
    private final GroupTransport transport;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    private volatile Runnable listener;
    private final String snapshotEpoch = UUID.randomUUID().toString();
    private long snapshotSequence;

    public GroupService(String localUser, GroupTransport transport) {
        this(localUser, transport, System::currentTimeMillis);
    }

    GroupService(String localUser, GroupTransport transport, LongSupplier clock) {
        this.localUser = GroupProtocol.user(localUser);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setListener(Runnable listener) { this.listener = listener; }

    public String create(String name, Collection<String> selectedUsers) {
        String checkedName = GroupProtocol.name(name);
        LinkedHashSet<String> users = new LinkedHashSet<>();
        users.add(localUser);
        if (selectedUsers == null) throw new IllegalArgumentException("请选择群成员");
        for (String user : selectedUsers) users.add(GroupProtocol.user(user));
        if (users.size() < 3 || users.size() > GroupProtocol.MAX_MEMBERS) {
            throw new IllegalArgumentException("群组需要包含本人在内的3至8名不同用户");
        }
        List<Job> jobs = new ArrayList<>();
        String id;
        synchronized (lock) {
            if (groups.size() >= MAX_GROUPS) throw new IllegalStateException("群组数量达到上限");
            GroupMessage invite = new GroupMessage();
            invite.type = "INVITE";
            invite.groupId = uuid();
            invite.messageId = uuid();
            invite.senderId = localUser;
            invite.ownerId = localUser;
            invite.token = uuid();
            invite.revision = 1;
            invite.timestamp = now();
            invite.name = checkedName;
            invite.closed = false;
            invite.members = new LinkedHashMap<>();
            for (String user : users) invite.members.put(user, user.equals(localUser) ? "ACTIVE" : "INVITED");
            GroupProtocol.encode(invite); // validate before storing any partial group
            Group group = new Group(invite);
            groups.put(group.id, group);
            track(group, invite, withoutSelf(users), jobs);
            id = group.id;
        }
        publish(jobs);
        return id;
    }

    public void join(String groupId) {
        List<Job> jobs = new ArrayList<>();
        synchronized (lock) {
            Group group = require(groupId);
            if (group.closed || group.localLeft || !"INVITED".equals(group.members.get(localUser))) {
                throw new IllegalStateException("当前邀请不可加入");
            }
            reserve(group, 1);
            group.joining = true;
            track(group, envelope(group, "ACCEPT"), Collections.singleton(group.ownerId), jobs);
        }
        publish(jobs);
    }

    public String send(String groupId, String text) {
        String checkedText = GroupProtocol.text(text);
        List<Job> jobs = new ArrayList<>();
        String id;
        synchronized (lock) {
            Group group = require(groupId);
            requireActive(group);
            if (group.messages.size() >= MAX_MESSAGES) throw new IllegalStateException("本进程群历史已达256条，请新建群");
            List<String> targets = activePeers(group);
            if (targets.isEmpty()) throw new IllegalStateException("尚无其他已加入成员");
            GroupMessage message = envelope(group, "TEXT");
            message.text = checkedText;
            Group.Operation operation = track(group, message, targets, jobs);
            group.messages.put(message.messageId, new Group.Text(message, true, operation.deliveries));
            group.seen.put(message.messageId, fingerprint(message));
            id = message.messageId;
        }
        publish(jobs);
        return id;
    }

    public void leave(String groupId) {
        List<Job> jobs = new ArrayList<>();
        synchronized (lock) {
            Group group = require(groupId);
            if (group.closed) throw new IllegalStateException("群组已解散");
            reserve(group, localUser.equals(group.ownerId) ? group.members.size() - 1 : 1);
            if (localUser.equals(group.ownerId) && group.revision >= 1000000000) throw new IllegalStateException("群版本达到上限");
            group.localLeft = true; // immediately stop local sends/receives, even if owner is offline
            group.joining = false;
            if (localUser.equals(group.ownerId)) {
                group.closed = true;
                group.members.put(localUser, "LEFT");
                advance(group);
                broadcastRoster(group, jobs);
            } else {
                track(group, envelope(group, "LEAVE"), Collections.singleton(group.ownerId), jobs);
            }
        }
        publish(jobs);
    }

    /** Owner resends invitations/state; members request authority state (or retry their pending leave). */
    public void sync(String groupId) {
        List<Job> jobs = new ArrayList<>();
        synchronized (lock) {
            Group group = require(groupId);
            if (localUser.equals(group.ownerId)) broadcastRoster(group, jobs);
            else track(group, envelope(group, group.localLeft && !"LEFT".equals(group.members.get(localUser)) ? "LEAVE" : group.joining && "INVITED".equals(group.members.get(localUser)) ? "ACCEPT" : "SYNC"),
                    Collections.singleton(group.ownerId), jobs);
        }
        publish(jobs);
    }

    /** Retry only original non-delivered targets that are still active; preserve message identity. */
    public void retry(String groupId, String messageId) {
        List<Job> jobs = new ArrayList<>();
        synchronized (lock) {
            Group group = require(groupId);
            requireActive(group);
            Group.Operation operation = group.operations.get(messageId);
            if (operation == null || !"TEXT".equals(operation.envelope.type)) {
                throw new IllegalArgumentException("只能重试本机已发送的群文字");
            }
            operation.envelope.revision = group.revision;
            String body = GroupProtocol.encode(operation.envelope);
            for (Map.Entry<String, Group.Delivery> entry : operation.deliveries.entrySet()) {
                Group.Delivery delivery = entry.getValue();
                if ("DELIVERED".equals(delivery.status)) continue;
                if (!"ACTIVE".equals(group.members.get(entry.getKey()))) {
                    delivery.status = "REMOVED"; delivery.detail = "MEMBER_LEFT";
                    continue;
                }
                delivery.status = "PENDING"; delivery.detail = "WAITING_APPLICATION_ACK";
                delivery.sentAt = now(); delivery.attempt++;
                jobs.add(new Job(group, operation, entry.getKey(), body, delivery.attempt));
            }
        }
        publish(jobs);
    }

    /** Called only for the dedicated group MIME; returns the SIP response status. */
    public int receive(String sipFrom, String json) {
        final GroupMessage message;
        try {
            GroupProtocol.user(sipFrom);
            message = GroupProtocol.decode(json);
        } catch (RuntimeException malformed) { return 400; }
        if (!sipFrom.equals(message.senderId)) return 403;
        List<Job> jobs = new ArrayList<>();
        int status = 200;
        boolean changed = false;
        synchronized (lock) {
            Group group = groups.get(message.groupId);
            try {
                boolean control = !"ACK".equals(message.type) && !"TEXT".equals(message.type);
                boolean duplicateControl = false;
                String controlKey = message.senderId + ":" + message.messageId;
                if (control && group != null) {
                    identity(group, message);
                    String previous = group.controls.get(controlKey);
                    if (previous != null) {
                        if (!previous.equals(GroupProtocol.encode(message))) reject(409, "MESSAGE_ID_CONFLICT");
                        duplicateControl = true;
                    } else if (group.controls.size() >= MAX_OPERATIONS) reject(507, "CONTROL_LIMIT");
                }
                if (duplicateControl) {
                    // SIP retransmission: re-ACK without repeating membership changes or fan-out.
                } else if ("INVITE".equals(message.type)) {
                    if (!message.ownerId.equals(sipFrom) || !message.members.containsKey(localUser)
                            || !"INVITED".equals(message.members.get(localUser))) reject(403, "INVALID_INVITATION");
                    if (group == null) {
                        if (groups.size() >= MAX_GROUPS) reject(507, "GROUP_LIMIT");
                        group = new Group(message);
                        groups.put(group.id, group);
                    } else {
                        identity(group, message);
                        if (group.localLeft || group.closed) reject(403, "LOCAL_LEFT");
                        applyRoster(group, message);
                    }
                    changed = true;
                } else {
                    if (group == null) reject(404, "UNKNOWN_GROUP");
                    identity(group, message);
                    if ("ACK".equals(message.type)) {
                        acceptAck(group, message);
                        changed = true;
                    } else if ("STATE".equals(message.type)) {
                        if (!sipFrom.equals(group.ownerId)) reject(403, "OWNER_REQUIRED");
                        applyRoster(group, message);
                        changed = true;
                    } else if ("TEXT".equals(message.type)) {
                        if (group.closed || group.localLeft || !"ACTIVE".equals(group.members.get(localUser))
                                || !"ACTIVE".equals(group.members.get(sipFrom))) reject(403, "NOT_ACTIVE");
                        String previous = group.seen.get(message.messageId);
                        if (previous != null) {
                            if (!previous.equals(fingerprint(message))) reject(409, "MESSAGE_ID_CONFLICT");
                        } else {
                            if (message.revision != group.revision) {
                                requestSync(group, jobs);
                                reject(409, "STATE_MISMATCH");
                            }
                            if (group.messages.size() >= MAX_MESSAGES) reject(507, "HISTORY_LIMIT");
                            group.messages.put(message.messageId, new Group.Text(message, false, Collections.emptyMap()));
                            group.seen.put(message.messageId, fingerprint(message));
                            changed = true;
                        }
                    } else if ("ACCEPT".equals(message.type)) {
                        requireOwnerRequest(group, sipFrom);
                        reserve(group, group.members.size() - 1);
                        if (!"INVITED".equals(group.members.get(sipFrom)) && !"ACTIVE".equals(group.members.get(sipFrom))) {
                            reject(403, "INVITATION_REVOKED");
                        }
                        if (!"ACTIVE".equals(group.members.get(sipFrom))) {
                            advance(group); group.members.put(sipFrom, "ACTIVE");
                        }
                        broadcastRoster(group, jobs);
                        changed = true;
                    } else if ("LEAVE".equals(message.type)) {
                        if (!localUser.equals(group.ownerId) || !group.members.containsKey(sipFrom) || sipFrom.equals(group.ownerId)) {
                            reject(403, "OWNER_REQUIRED");
                        }
                        reserve(group, group.members.size() - 1);
                        if (!group.closed && !"LEFT".equals(group.members.get(sipFrom))) {
                            advance(group); group.members.put(sipFrom, "LEFT");
                        }
                        broadcastRoster(group, jobs);
                        changed = true;
                    } else if ("SYNC".equals(message.type)) {
                        if (!localUser.equals(group.ownerId) || !group.members.containsKey(sipFrom)) reject(403, "OWNER_REQUIRED");
                        sendRoster(group, sipFrom, jobs);
                    }
                }
                if (control && group != null && !duplicateControl) group.controls.put(controlKey, GroupProtocol.encode(message));
                if (!"ACK".equals(message.type)) ack(message, true, "ACCEPTED", jobs);
            } catch (Rejection rejection) {
                status = rejection.status;
                if (group != null) group.lastError = rejection.reason;
                // Echo no credentials, and never acknowledge malformed / sender-mismatched envelopes.
                if (!"ACK".equals(message.type)) ack(message, false, rejection.reason, jobs);
                changed = true;
            } catch (IllegalArgumentException | IllegalStateException failure) {
                status = 409;
                if (group != null) group.lastError = "LOCAL_LIMIT_OR_STATE";
                if (!"ACK".equals(message.type)) ack(message, false, "LOCAL_LIMIT_OR_STATE", jobs);
                changed = true;
            }
        }
        if (changed) changed();
        dispatch(jobs);
        return status;
    }

    /** One atomic export prevents an older HTTP response from replacing a newer SSE snapshot. */
    public JsonObject snapshotEnvelope() {
        synchronized (lock) {
            JsonObject result = new JsonObject();
            result.add("groups", snapshot());
            result.addProperty("snapshotEpoch", snapshotEpoch);
            result.addProperty("snapshotSequence", ++snapshotSequence);
            return result;
        }
    }
    /** Detached public snapshot: never includes protocol tokens or mutable state. */
    public JsonArray snapshot() {
        synchronized (lock) {
            expire();
            JsonArray result = new JsonArray();
            for (Group group : groups.values()) {
                JsonObject value = new JsonObject();
                value.addProperty("id", group.id); value.addProperty("name", group.name);
                value.addProperty("ownerId", group.ownerId); value.addProperty("revision", group.revision);
                value.addProperty("closed", group.closed); value.addProperty("localStatus", localStatus(group));
                value.addProperty("canSend", canSend(group)); value.addProperty("lastError", group.lastError);
                JsonArray members = new JsonArray();
                for (Map.Entry<String, String> member : group.members.entrySet()) {
                    JsonObject item = new JsonObject(); item.addProperty("userId", member.getKey()); item.addProperty("status", member.getValue()); members.add(item);
                }
                value.add("members", members);
                JsonArray messages = new JsonArray();
                for (Group.Text text : group.messages.values()) {
                    JsonObject item = new JsonObject(); item.addProperty("id", text.id); item.addProperty("groupId", group.id);
                    item.addProperty("senderId", text.senderId); item.addProperty("text", text.text);
                    item.addProperty("timestamp", text.timestamp); item.addProperty("outgoing", text.outgoing);
                    JsonObject deliveries = new JsonObject();
                    for (Map.Entry<String, Group.Delivery> delivery : text.deliveries.entrySet()) {
                        JsonObject d = new JsonObject(); d.addProperty("status", delivery.getValue().status); d.addProperty("detail", delivery.getValue().detail);
                        deliveries.add(delivery.getKey(), d);
                    }
                    item.add("deliveries", deliveries); messages.add(item);
                }
                value.add("messages", messages);
                LinkedHashMap<String, String> latestControls = new LinkedHashMap<>();
                for (Group.Operation operation : group.operations.values()) {
                    if ("TEXT".equals(operation.envelope.type)) continue;
                    for (Map.Entry<String, Group.Delivery> delivery : operation.deliveries.entrySet()) {
                        latestControls.put(delivery.getKey(), operation.envelope.type + " " + delivery.getKey()
                                + "=" + delivery.getValue().status + "(" + delivery.getValue().detail + ")");
                    }
                }
                value.addProperty("controlStatus", String.join("; ", latestControls.values()));
                result.add(value);
            }
            return result;
        }
    }

    private Group.Operation track(Group group, GroupMessage message, Collection<String> targets, List<Job> jobs) {
        reserve(group, 1);
        String body = GroupProtocol.encode(message);
        Group.Operation operation = new Group.Operation(message);
        for (String target : targets) {
            if (!target.equals(localUser)) operation.deliveries.put(target, new Group.Delivery(now()));
        }
        group.operations.put(message.messageId, operation);
        for (Map.Entry<String, Group.Delivery> entry : operation.deliveries.entrySet()) {
            jobs.add(new Job(group, operation, entry.getKey(), body, entry.getValue().attempt));
        }
        return operation;
    }

    private void broadcastRoster(Group group, List<Job> jobs) {
        reserve(group, group.members.size() - 1);
        for (String member : group.members.keySet()) if (!member.equals(localUser)) sendRoster(group, member, jobs);
        for (Group.Operation operation : group.operations.values()) {
            if (!"TEXT".equals(operation.envelope.type)) continue;
            for (Map.Entry<String, Group.Delivery> entry : operation.deliveries.entrySet()) {
                if (!"ACTIVE".equals(group.members.get(entry.getKey())) && !"DELIVERED".equals(entry.getValue().status)) {
                    entry.getValue().status = "REMOVED"; entry.getValue().detail = "MEMBER_LEFT";
                }
            }
        }
    }

    private void sendRoster(Group group, String member, List<Job> jobs) {
        GroupMessage state = envelope(group, !group.closed && "INVITED".equals(group.members.get(member)) ? "INVITE" : "STATE");
        state.name = group.name; state.closed = group.closed; state.members = new LinkedHashMap<>(group.members);
        track(group, state, Collections.singleton(member), jobs);
    }

    private void requestSync(Group group, List<Job> jobs) {
        if (localUser.equals(group.ownerId)) return;
        track(group, envelope(group, "SYNC"), Collections.singleton(group.ownerId), jobs);
    }

    private void applyRoster(Group group, GroupMessage message) {
        if (!message.senderId.equals(group.ownerId) || !group.name.equals(message.name)
                || !group.members.keySet().equals(message.members.keySet())) reject(403, "INVALID_ROSTER");
        if (message.revision < group.revision) return; // stale retransmission never rolls back state
        if (message.revision == group.revision) {
            if (group.closed != Boolean.TRUE.equals(message.closed) || !group.members.equals(message.members)) reject(409, "REVISION_CONFLICT");
            return;
        }
        if (group.closed && !Boolean.TRUE.equals(message.closed)) reject(409, "GROUP_CLOSED");
        for (String user : group.members.keySet()) {
            String old = group.members.get(user), next = message.members.get(user);
            if (("LEFT".equals(old) && !"LEFT".equals(next)) || ("ACTIVE".equals(old) && "INVITED".equals(next))) {
                reject(409, "MEMBERSHIP_ROLLBACK");
            }
        }
        group.members.clear(); group.members.putAll(message.members);
        group.revision = message.revision; group.closed = Boolean.TRUE.equals(message.closed);
        if ("ACTIVE".equals(group.members.get(localUser))) group.joining = false;
        if ("LEFT".equals(group.members.get(localUser))) { group.localLeft = true; group.joining = false; }
        for (Group.Operation operation : group.operations.values()) {
            if (!"TEXT".equals(operation.envelope.type)) continue;
            for (Map.Entry<String, Group.Delivery> entry : operation.deliveries.entrySet()) {
                if (!"ACTIVE".equals(group.members.get(entry.getKey())) && !"DELIVERED".equals(entry.getValue().status)) {
                    entry.getValue().status = "REMOVED"; entry.getValue().detail = "MEMBER_LEFT";
                }
            }
        }
    }

    private void acceptAck(Group group, GroupMessage ack) {
        Group.Operation operation = group.operations.get(ack.ackForId);
        if (operation == null || !operation.deliveries.containsKey(ack.senderId)) reject(403, "UNEXPECTED_ACK");
        Group.Delivery delivery = operation.deliveries.get(ack.senderId);
        if ("DELIVERED".equals(ack.ackStatus)) {
            delivery.status = "DELIVERED"; delivery.detail = "APPLICATION_ACK";
        } else if (!"DELIVERED".equals(delivery.status) && ack.revision >= operation.envelope.revision) {
            delivery.status = "FAILED"; delivery.detail = ack.ackDetail == null ? "REJECTED" : ack.ackDetail;
            group.lastError = delivery.detail;
        }
    }

    private void ack(GroupMessage incoming, boolean accepted, String detail, List<Job> jobs) {
        GroupMessage ack = new GroupMessage(); ack.type = "ACK";
        ack.groupId = incoming.groupId; ack.ownerId = incoming.ownerId; ack.token = incoming.token;
        ack.messageId = uuid(); ack.senderId = localUser; ack.revision = incoming.revision; ack.timestamp = now();
        ack.ackForId = incoming.messageId; ack.ackStatus = accepted ? "DELIVERED" : "REJECTED"; ack.ackDetail = detail;
        jobs.add(new Job(incoming.senderId, GroupProtocol.encode(ack)));
    }

    private void identity(Group group, GroupMessage message) {
        if (!group.ownerId.equals(message.ownerId) || !group.token.equals(message.token)
                || !group.members.containsKey(message.senderId)) reject(403, "GROUP_IDENTITY_MISMATCH");
    }

    private void requireOwnerRequest(Group group, String from) {
        if (!localUser.equals(group.ownerId) || group.closed || !group.members.containsKey(from) || from.equals(group.ownerId)) {
            reject(403, "OWNER_REQUIRED");
        }
    }

    private GroupMessage envelope(Group group, String type) {
        GroupMessage message = new GroupMessage(); message.type = type; message.groupId = group.id;
        message.messageId = uuid(); message.senderId = localUser; message.ownerId = group.ownerId;
        message.token = group.token; message.revision = group.revision; message.timestamp = now();
        return message;
    }

    private Group require(String id) {
        Group group = groups.get(id);
        if (group == null) throw new IllegalArgumentException("群组不存在");
        return group;
    }
    private boolean canSend(Group group) { return !group.closed && !group.localLeft && "ACTIVE".equals(group.members.get(localUser)); }
    private void requireActive(Group group) { if (!canSend(group)) throw new IllegalStateException("尚未加入、已经退出或群组已解散"); }
    private String localStatus(Group group) {
        if (group.closed) return "CLOSED";
        if (group.localLeft) return "LEFT".equals(group.members.get(localUser)) ? "LEFT" : "LEAVE_PENDING";
        if (group.joining) return "JOINING";
        return group.members.get(localUser);
    }
    private List<String> activePeers(Group group) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> member : group.members.entrySet()) {
            if (!member.getKey().equals(localUser) && "ACTIVE".equals(member.getValue())) result.add(member.getKey());
        }
        return result;
    }
    private List<String> withoutSelf(Collection<String> members) { List<String> result = new ArrayList<>(members); result.remove(localUser); return result; }
    private void reserve(Group group, int slots) {
        if (slots < 0 || group.operations.size() > MAX_OPERATIONS - slots) throw new IllegalStateException("群操作达到本进程上限，请新建群");
    }
    private void advance(Group group) { if (group.revision >= 1000000000) throw new IllegalStateException("群版本达到上限"); group.revision++; }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private long now() { return Math.max(1L, clock.getAsLong()); }
    private static String fingerprint(GroupMessage message) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((message.senderId + "\n" + message.timestamp + "\n" + message.text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : hash) hex.append(String.format("%02x", b & 255)); return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void expire() {
        long current = now();
        for (Group group : groups.values()) for (Group.Operation operation : group.operations.values()) for (Group.Delivery delivery : operation.deliveries.values()) {
            if (("PENDING".equals(delivery.status) || "SIP_ACCEPTED".equals(delivery.status)) && current - delivery.sentAt >= ACK_TIMEOUT_MS) {
                delivery.status = "TIMEOUT"; delivery.detail = "APPLICATION_ACK_TIMEOUT";
            }
        }
    }
    private void publish(List<Job> jobs) { changed(); dispatch(jobs); }
    private void changed() { Runnable callback = listener; if (callback != null) callback.run(); }
    private void dispatch(List<Job> jobs) {
        for (Job job : jobs) {
            try { transport.send(job.recipient, job.body, (accepted, detail) -> complete(job, accepted, detail)); }
            catch (Exception failure) { complete(job, false, "SEND_FAILED"); }
        }
    }
    private void complete(Job job, boolean accepted, String detail) {
        if (job.operation == null) return; // ACK delivery itself is not recursively acknowledged
        synchronized (lock) {
            Group.Delivery delivery = job.operation.deliveries.get(job.recipient);
            if (delivery.attempt != job.attempt || "DELIVERED".equals(delivery.status) || "REMOVED".equals(delivery.status)) return;
            if (accepted) {
                if ("PENDING".equals(delivery.status)) { delivery.status = "SIP_ACCEPTED"; delivery.detail = "WAITING_APPLICATION_ACK"; }
            } else {
                delivery.status = detail != null && detail.contains("TIMEOUT") ? "TIMEOUT" : "FAILED";
                delivery.detail = detail != null && detail.matches("[A-Z0-9_]{1,48}") ? detail : "SIP_FAILED";
                job.group.lastError = delivery.detail;
            }
        }
        changed();
    }
    private static void reject(int status, String reason) { throw new Rejection(status, reason); }
    private static final class Rejection extends RuntimeException {
        final int status; final String reason;
        Rejection(int status, String reason) { this.status = status; this.reason = reason; }
    }
    private static final class Job {
        final Group group; final Group.Operation operation; final String recipient; final String body; final long attempt;
        Job(Group group, Group.Operation operation, String recipient, String body, long attempt) {
            this.group = group; this.operation = operation; this.recipient = recipient; this.body = body; this.attempt = attempt;
        }
        Job(String recipient, String body) { this(null, null, recipient, body, 0); }
    }
}
