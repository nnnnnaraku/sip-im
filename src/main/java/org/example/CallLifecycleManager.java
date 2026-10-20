package org.example;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Small, transport-independent call state machine.
 *
 * The SIP adapter serializes signalling and media ownership on this same monitor.
 * Timer callbacks do not hold another lock: the adapter must recheck isActive(session)
 * under this monitor before touching SIP or shared media.
 */
public final class CallLifecycleManager implements AutoCloseable {
    public enum AcceptResult { START_MEDIA, DUPLICATE, TERMINATE_LATE, IGNORE }
    public enum HangupAction { CANCEL, BYE, WAIT, IGNORE }
    public enum TimerKind { INVITE_DEADLINE, ACK_DEADLINE, AUTO_HANGUP, END_DEADLINE }

    public interface Cancellable { void cancel(); }
    public interface Scheduler extends AutoCloseable {
        Cancellable schedule(Runnable task, long delayMillis);
        @Override void close();
    }

    static final class TimerRegistration {
        Cancellable handle;
        boolean cancelled;
        boolean fired;
        void cancel() {
            if (!cancelled) {
                cancelled = true;
                if (handle != null) handle.cancel();
            }
        }
    }

    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final int historyLimit;
    private final LinkedHashMap<String, CallSession> sessions = new LinkedHashMap<>();
    private CallSession active;
    private boolean closed;

    public CallLifecycleManager() { this(new DaemonScheduler()); }
    public CallLifecycleManager(Scheduler scheduler) {
        this(scheduler, System::currentTimeMillis, 64);
    }
    public CallLifecycleManager(Scheduler scheduler, LongSupplier clock, int historyLimit) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (historyLimit < 1) throw new IllegalArgumentException("historyLimit must be positive");
        this.historyLimit = historyLimit;
    }

    public synchronized CallSession beginOutgoing(String callId, long cseq, String branch,
                                                   String peer, boolean video, String room) {
        return begin(callId, cseq, branch, CallSession.Direction.OUTGOING, peer, video, room);
    }

    public synchronized CallSession beginIncoming(String callId, long cseq, String branch,
                                                   String peer, boolean video, String room) {
        return begin(callId, cseq, branch, CallSession.Direction.INCOMING, peer, video, room);
    }

    private CallSession begin(String callId, long cseq, String branch, CallSession.Direction direction,
                              String peer, boolean video, String room) {
        if (closed) throw new IllegalStateException("Call lifecycle is closed");
        if (active != null) throw new IllegalStateException("Another call is active");
        requireText(callId, "callId");
        requireText(peer, "peer");
        if (cseq <= 0 || cseq > 0x7fffffffL) throw new IllegalArgumentException("Invalid INVITE CSeq");
        if (branch != null) requireText(branch, "branch");
        if (room != null) requireText(room, "room");
        if (sessions.containsKey(callId)) throw new IllegalArgumentException("SIP Call-ID was already used");
        CallSession session = new CallSession(callId, cseq, branch, direction, peer, video, room,
                clock.getAsLong());
        sessions.put(callId, session);
        active = session;
        pruneHistory();
        return session;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid " + name);
    }

    public synchronized CallSession active() { return active; }
    public synchronized CallSession find(String callId) { return sessions.get(callId); }
    public synchronized boolean isActive(CallSession session) {
        return !closed && session != null && active == session
                && session.getState() != CallState.ENDING && session.getState() != CallState.ENDED;
    }
    public synchronized int retainedSessionCount() { return sessions.size(); }

    private boolean known(CallSession session) {
        return session != null && sessions.get(session.getCallId()) == session;
    }

    /** A response without a transaction may omit branch, but its Call-ID and CSeq still must match. */
    public synchronized boolean matchesInvite(CallSession session, long cseq, String branch) {
        return known(session) && session.getInviteCSeq() == cseq
                && (branch == null || Objects.equals(session.getInviteBranch(), branch));
    }

    /** Digest retry replaces only the transaction identity, never the call or media intent. */
    public synchronized boolean bindInvite(CallSession session, long cseq, String branch) {
        if (!isActive(session) || session.getDirection() != CallSession.Direction.OUTGOING
                || (session.getState() != CallState.DIALING && session.getState() != CallState.RINGING))
            return false;
        if (cseq <= session.getInviteCSeq() || cseq > 0x7fffffffL)
            throw new IllegalArgumentException("Retry INVITE CSeq must increase");
        requireText(branch, "branch");
        session.bindInvite(cseq, branch);
        session.provisionalSeen = false;
        session.state(CallState.DIALING);
        return true;
    }

    public synchronized boolean provisional(CallSession session) {
        if (!isActive(session) || session.getDirection() != CallSession.Direction.OUTGOING
                || (session.getState() != CallState.DIALING && session.getState() != CallState.RINGING))
            return false;
        session.provisionalSeen = true;
        return true;
    }

    public synchronized boolean ringing(CallSession session) {
        if (!isActive(session) || session.getState() == CallState.ESTABLISHED
                || session.getState() == CallState.ACCEPTED) return false;
        session.provisionalSeen = true;
        session.state(CallState.RINGING);
        return true;
    }

    public synchronized boolean awaitingAck(CallSession session) {
        if (!isActive(session) || session.getDirection() != CallSession.Direction.INCOMING
                || session.getState() != CallState.RINGING) return false;
        session.state(CallState.ACCEPTED);
        return true;
    }

    public synchronized AcceptResult accepted(CallSession session) {
        if (!known(session) || session.getDirection() != CallSession.Direction.OUTGOING)
            return AcceptResult.IGNORE;
        return establish(session, false);
    }

    public synchronized AcceptResult acknowledged(CallSession session) {
        if (!known(session) || session.getDirection() != CallSession.Direction.INCOMING)
            return AcceptResult.IGNORE;
        return establish(session, true);
    }

    private AcceptResult establish(CallSession session, boolean incoming) {
        CallState state = session.getState();
        if (state == CallState.ENDING || state == CallState.ENDED || session.cancelRequested)
            return AcceptResult.TERMINATE_LATE;
        if (!isActive(session)) return AcceptResult.IGNORE;
        if (state == CallState.ESTABLISHED) return AcceptResult.DUPLICATE;
        if (incoming && state != CallState.ACCEPTED) return AcceptResult.IGNORE;
        session.established(clock.getAsLong());
        cancelTimer(session, TimerKind.INVITE_DEADLINE);
        cancelTimer(session, TimerKind.ACK_DEADLINE);
        return AcceptResult.START_MEDIA;
    }

    /** Reserves media once; a duplicate response can ACK again but must not allocate devices again. */
    public synchronized boolean claimMedia(CallSession session) {
        if (!isActive(session) || session.getState() != CallState.ESTABLISHED || session.mediaClaimed)
            return false;
        session.mediaClaimed = true;
        return true;
    }

    /** Exactly-once cleanup is separate from state, allowing partial media startup to be unwound. */
    public synchronized boolean claimCleanup(CallSession session) {
        if (!known(session) || session.cleanupClaimed) return false;
        session.cleanupClaimed = true;
        return true;
    }

    /**
     * Ends local ownership immediately. Late final responses remain correlated by history
     * and return TERMINATE_LATE; a redial does not wait for the old CANCEL/BYE transaction.
     */
    public synchronized HangupAction requestHangup(CallSession session) {
        if (!isActive(session)) return HangupAction.IGNORE;
        CallState oldState = session.getState();
        HangupAction action;
        if (oldState == CallState.ESTABLISHED) {
            action = HangupAction.BYE;
        } else {
            session.cancelRequested = true;
            action = session.getDirection() == CallSession.Direction.OUTGOING && session.provisionalSeen
                    ? HangupAction.CANCEL : HangupAction.WAIT;
        }
        session.state(CallState.ENDING);
        cancelAllTimers(session);
        active = null;
        return action;
    }

    /** Returns true once for an identity; finishing a stale call never alters the current call. */
    public synchronized boolean finish(CallSession session, String reason) {
        if (!known(session) || session.getState() == CallState.ENDED) return false;
        session.ended(clock.getAsLong(), reason == null ? "" : reason);
        cancelAllTimers(session);
        if (active == session) active = null;
        pruneHistory();
        return true;
    }

    public synchronized boolean armAutoHangup(CallSession session, long delayMillis, Runnable callback) {
        if (!isActive(session) || session.getState() != CallState.ESTABLISHED) return false;
        return armTimer(session, TimerKind.AUTO_HANGUP, delayMillis, callback);
    }

    public synchronized boolean armTimer(final CallSession session, final TimerKind kind,
                                         long delayMillis, final Runnable callback) {
        return armRegistration(session, kind, delayMillis, callback, false);
    }

    /**
     * Bounded UAS cleanup after a locally ended 200 OK still waits for its ACK.
     * This timer may affect only its retained signalling binding, never active media.
     */
    public synchronized boolean armRetainedEndTimer(final CallSession session, long delayMillis,
                                                     final Runnable callback) {
        return armRegistration(session, TimerKind.END_DEADLINE, delayMillis, callback, true);
    }

    private boolean retainedEnding(CallSession session) {
        return !closed && known(session) && session != active
                && (session.getState() == CallState.ENDING || session.getState() == CallState.ENDED);
    }

    private boolean armRegistration(final CallSession session, final TimerKind kind,
                                    long delayMillis, final Runnable callback, final boolean retained) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(callback, "callback");
        if (delayMillis < 0) throw new IllegalArgumentException("Timer delay must not be negative");
        if (!(retained ? retainedEnding(session) : isActive(session)) || session.timers.containsKey(kind)) return false;
        if (kind == TimerKind.AUTO_HANGUP && session.getState() != CallState.ESTABLISHED) return false;
        final TimerRegistration registration = new TimerRegistration();
        session.timers.put(kind, registration);
        try {
            registration.handle = scheduler.schedule(() -> {
                synchronized (CallLifecycleManager.this) {
                    if (registration.cancelled || registration.fired
                            || !(retained ? retainedEnding(session) : isActive(session))
                            || session.timers.get(kind) != registration) return;
                    if (kind == TimerKind.AUTO_HANGUP && session.getState() != CallState.ESTABLISHED) return;
                    registration.fired = true;
                }
                callback.run();
            }, delayMillis);
            if (registration.handle == null) throw new IllegalStateException("Scheduler returned no timer handle");
            if (registration.cancelled) registration.handle.cancel();
            return true;
        } catch (RuntimeException failure) {
            session.timers.remove(kind);
            registration.cancel();
            throw failure;
        }
    }

    public synchronized boolean cancelTimer(CallSession session, TimerKind kind) {
        if (!known(session)) return false;
        TimerRegistration registration = session.timers.get(kind);
        if (registration == null || registration.cancelled) return false;
        registration.cancel();
        return true;
    }

    private void cancelAllTimers(CallSession session) {
        for (TimerRegistration registration : session.timers.values()) registration.cancel();
    }

    private void pruneHistory() {
        if (sessions.size() <= historyLimit) return;
        Iterator<Map.Entry<String, CallSession>> iterator = sessions.entrySet().iterator();
        while (sessions.size() > historyLimit && iterator.hasNext()) {
            CallSession candidate = iterator.next().getValue();
            if (candidate != active) {
                cancelAllTimers(candidate);
                iterator.remove();
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (CallSession session : sessions.values()) {
            cancelAllTimers(session);
            if (session.getState() != CallState.ENDED) session.ended(clock.getAsLong(), "shutdown");
        }
        active = null;
        scheduler.close();
    }

    private static final class DaemonScheduler implements Scheduler {
        private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                new ThreadFactory() {
                    @Override public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "SIP-Call-Lifecycle");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        DaemonScheduler() {
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }
        @Override public Cancellable schedule(Runnable task, long delayMillis) {
            final ScheduledFuture<?> future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
        @Override public void close() { executor.shutdownNow(); }
    }
}
