package org.example;

import java.util.Map;

/** Small, versioned SIP MESSAGE envelope. Never put SIP passwords in this DTO. */
public final class GroupMessage {
    public int version = 1;
    public String type;
    public String groupId;
    public String messageId;
    public String senderId;
    public String ownerId;
    public String token;
    public int revision;
    public long timestamp;
    public String name;
    public String text;
    public String ackForId;
    public String ackStatus;
    public String ackDetail;
    public Map<String, String> members;
    public Boolean closed;
}
