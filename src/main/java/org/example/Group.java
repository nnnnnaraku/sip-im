package org.example;

import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local group state. Never serialize this object: token is protocol-only. */
final class Group {
    final String id;
    final String ownerId;
    final String token;
    final String name;
    int revision;
    boolean closed;
    boolean localLeft;
    boolean joining;
    String lastError = "";
    final LinkedHashMap<String, String> members = new LinkedHashMap<>();
    final LinkedHashMap<String, Text> messages = new LinkedHashMap<>();
    final LinkedHashMap<String, String> seen = new LinkedHashMap<>();
    final LinkedHashMap<String, String> controls = new LinkedHashMap<>();
    final LinkedHashMap<String, Operation> operations = new LinkedHashMap<>();

    Group(GroupMessage message) {
        id = message.groupId;
        ownerId = message.ownerId;
        token = message.token;
        name = message.name;
        revision = message.revision;
        closed = Boolean.TRUE.equals(message.closed);
        members.putAll(message.members);
    }

    static final class Delivery {
        String status = "PENDING";
        String detail = "WAITING_APPLICATION_ACK";
        long sentAt;
        long attempt = 1;
        Delivery(long now) { sentAt = now; }
    }

    static final class Operation {
        GroupMessage envelope;
        final LinkedHashMap<String, Delivery> deliveries = new LinkedHashMap<>();
        Operation(GroupMessage envelope) { this.envelope = envelope; }
    }

    static final class Text {
        final String id;
        final String senderId;
        final String text;
        final long timestamp;
        final boolean outgoing;
        final Map<String, Delivery> deliveries;
        Text(GroupMessage envelope, boolean outgoing, Map<String, Delivery> deliveries) {
            id = envelope.messageId;
            senderId = envelope.senderId;
            text = envelope.text;
            timestamp = envelope.timestamp;
            this.outgoing = outgoing;
            this.deliveries = deliveries;
        }
    }
}
