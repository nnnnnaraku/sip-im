package org.example;

import java.util.EnumMap;
import java.util.UUID;

/**
 * One SIP call. Identity and peer/media intent never follow the selected chat contact.
 * Mutable fields are written only while holding its CallLifecycleManager monitor.
 */
public final class CallSession {
    public enum Direction { OUTGOING, INCOMING }

    private final String id = UUID.randomUUID().toString();
    private final String callId;
    private final Direction direction;
    private final String peer;
    private final boolean video;
    private final String conferenceRoom;
    private final long createdAtMillis;
    private volatile CallState state;
    private volatile long inviteCSeq;
    private volatile String inviteBranch;
    private volatile long establishedAtMillis;
    private volatile long endedAtMillis;
    private volatile String endReason = "";
    boolean provisionalSeen;
    volatile boolean cancelRequested;
    boolean mediaClaimed;
    boolean cleanupClaimed;
    final EnumMap<CallLifecycleManager.TimerKind, CallLifecycleManager.TimerRegistration> timers =
            new EnumMap<>(CallLifecycleManager.TimerKind.class);

    CallSession(String callId, long inviteCSeq, String inviteBranch, Direction direction,
                String peer, boolean video, String conferenceRoom, long createdAtMillis) {
        this.callId = callId;
        this.inviteCSeq = inviteCSeq;
        this.inviteBranch = inviteBranch;
        this.direction = direction;
        this.peer = peer;
        this.video = video;
        this.conferenceRoom = conferenceRoom;
        this.createdAtMillis = createdAtMillis;
        this.state = direction == Direction.OUTGOING ? CallState.DIALING : CallState.RINGING;
    }

    public String getId() { return id; }
    public String getCallId() { return callId; }
    public Direction getDirection() { return direction; }
    public String getPeer() { return peer; }
    public boolean isVideo() { return video; }
    public String getConferenceRoom() { return conferenceRoom; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public CallState getState() { return state; }
    public long getInviteCSeq() { return inviteCSeq; }
    public String getInviteBranch() { return inviteBranch; }
    public long getEstablishedAtMillis() { return establishedAtMillis; }
    public long getEndedAtMillis() { return endedAtMillis; }
    public String getEndReason() { return endReason; }
    public boolean isCancelRequested() { return cancelRequested; }

    void state(CallState value) { state = value; }
    void bindInvite(long cseq, String branch) { inviteCSeq = cseq; inviteBranch = branch; }
    void established(long now) { establishedAtMillis = now; state = CallState.ESTABLISHED; }
    void ended(long now, String reason) {
        endedAtMillis = now;
        endReason = reason;
        state = CallState.ENDED;
    }
}
