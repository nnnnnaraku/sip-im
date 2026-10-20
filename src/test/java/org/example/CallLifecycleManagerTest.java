package org.example;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Deterministic state/timer tests: no SIP stack, network, audio device, sleeps, or wall-clock deadlines. */
public class CallLifecycleManagerTest {
    private FakeScheduler scheduler;
    private AtomicLong clock;
    private CallLifecycleManager manager;

    @Before public void setUp() {
        scheduler = new FakeScheduler();
        clock = new AtomicLong(1000);
        manager = new CallLifecycleManager(scheduler, clock::get, 4);
    }

    @After public void tearDown() { manager.close(); }

    private CallSession outgoing(String callId) {
        return manager.beginOutgoing(callId, 1, "z9hG4bK-" + callId, "101", true, null);
    }

    private CallSession incoming(String callId) {
        return manager.beginIncoming(callId, 1, "z9hG4bK-" + callId, "100", true, null);
    }

    private void establish(CallSession session) {
        assertEquals(CallLifecycleManager.AcceptResult.START_MEDIA, manager.accepted(session));
    }

    @Test public void identityAndMediaIntentAreImmutableSnapshots() {
        CallSession call = manager.beginOutgoing("call-a", 7, "branch-a", "101", true, "1");
        assertEquals("call-a", call.getCallId());
        assertEquals(CallSession.Direction.OUTGOING, call.getDirection());
        assertEquals("101", call.getPeer());
        assertTrue(call.isVideo());
        assertEquals("1", call.getConferenceRoom());
        assertEquals(1000, call.getCreatedAtMillis());
        assertEquals(CallState.DIALING, call.getState());
        assertSame(call, manager.active());
        assertSame(call, manager.find("call-a"));
        assertEquals(36, call.getId().length());
    }

    @Test public void distinctCallsHaveDistinctLocalIdentity() {
        CallSession first = outgoing("one");
        manager.finish(first, "done");
        CallSession second = outgoing("two");
        assertNotEquals(first.getId(), second.getId());
    }

    @Test public void normalOutgoingCallTransitionsOnceAndReleasesOwnership() {
        CallSession call = outgoing("normal");
        assertTrue(manager.provisional(call));
        assertEquals(CallState.DIALING, call.getState());
        assertTrue(manager.ringing(call));
        assertEquals(CallState.RINGING, call.getState());
        clock.set(1800);
        establish(call);
        assertEquals(CallState.ESTABLISHED, call.getState());
        assertEquals(1800, call.getEstablishedAtMillis());
        assertTrue(manager.claimMedia(call));
        assertFalse(manager.claimMedia(call));
        assertEquals(CallLifecycleManager.HangupAction.BYE, manager.requestHangup(call));
        assertEquals(CallState.ENDING, call.getState());
        assertNull(manager.active());
        clock.set(1900);
        assertTrue(manager.finish(call, "local-bye"));
        assertEquals(CallState.ENDED, call.getState());
        assertEquals(1900, call.getEndedAtMillis());
        assertEquals("local-bye", call.getEndReason());
        assertTrue(manager.claimCleanup(call));
        assertFalse(manager.claimCleanup(call));
    }

    @Test public void incomingCallWaitsForAckBeforeMedia() {
        CallSession call = incoming("incoming");
        assertEquals(CallState.RINGING, call.getState());
        assertFalse(manager.claimMedia(call));
        assertEquals(CallLifecycleManager.AcceptResult.IGNORE, manager.acknowledged(call));
        assertTrue(manager.awaitingAck(call));
        assertEquals(CallState.ACCEPTED, call.getState());
        assertFalse(manager.claimMedia(call));
        assertEquals(CallLifecycleManager.AcceptResult.START_MEDIA, manager.acknowledged(call));
        assertTrue(manager.claimMedia(call));
        assertEquals(CallLifecycleManager.AcceptResult.DUPLICATE, manager.acknowledged(call));
        assertFalse(manager.claimMedia(call));
    }

    @Test public void duplicateOutgoing200CannotStartMediaOrTimerTwice() {
        CallSession call = outgoing("duplicate");
        establish(call);
        assertTrue(manager.claimMedia(call));
        assertTrue(manager.armAutoHangup(call, 180000, () -> { }));
        for (int i = 0; i < 10; i++) {
            assertEquals(CallLifecycleManager.AcceptResult.DUPLICATE, manager.accepted(call));
            assertFalse(manager.claimMedia(call));
            assertFalse(manager.armAutoHangup(call, 180000, () -> fail("duplicate timer")));
        }
        assertEquals(1, scheduler.tasks.size());
    }

    @Test public void secondOutgoingCallIsRejectedWhileFirstIsActive() {
        CallSession first = outgoing("first");
        expect(IllegalStateException.class, () -> outgoing("second"));
        assertSame(first, manager.active());
        assertEquals(1, manager.retainedSessionCount());
    }

    @Test public void incomingCallCannotReplaceAnActiveCall() {
        CallSession first = outgoing("first");
        expect(IllegalStateException.class, () -> incoming("second"));
        assertSame(first, manager.active());
    }

    @Test public void simultaneousDialersHaveExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread a = new Thread(() -> beginAtOnce("a", start, accepted, busy, unexpected));
        Thread b = new Thread(() -> beginAtOnce("b", start, accepted, busy, unexpected));
        a.start();
        b.start();
        start.countDown();
        a.join(3000);
        b.join(3000);
        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
        assertNull(unexpected.get());
        assertEquals(1, accepted.get());
        assertEquals(1, busy.get());
        assertEquals(1, manager.retainedSessionCount());
    }

    private void beginAtOnce(String id, CountDownLatch start, AtomicInteger accepted,
                             AtomicInteger busy, AtomicReference<Throwable> unexpected) {
        try {
            if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("test barrier");
            outgoing(id);
            accepted.incrementAndGet();
        } catch (IllegalStateException expected) {
            busy.incrementAndGet();
        } catch (Throwable error) {
            unexpected.set(error);
        }
    }

    @Test public void cancelBeforeFirstProvisionalWaitsThenPermitsImmediateRedial() {
        CallSession old = outgoing("old");
        assertEquals(CallLifecycleManager.HangupAction.WAIT, manager.requestHangup(old));
        assertEquals(CallState.ENDING, old.getState());
        assertNull(manager.active());
        CallSession next = outgoing("next");
        assertSame(next, manager.active());
        assertEquals(CallLifecycleManager.AcceptResult.TERMINATE_LATE, manager.accepted(old));
        assertSame(next, manager.active());
        assertEquals(CallState.DIALING, next.getState());
        assertFalse(manager.claimMedia(old));
    }

    @Test public void anyProvisionalAllowsCancelWithoutMisreportingRinging() {
        CallSession call = outgoing("trying");
        assertTrue(manager.provisional(call));
        assertEquals(CallState.DIALING, call.getState());
        assertEquals(CallLifecycleManager.HangupAction.CANCEL, manager.requestHangup(call));
        assertNull(manager.active());
    }

    @Test public void ringingCancelDoesNotRequireEstablishedDialog() {
        CallSession call = outgoing("ringing");
        manager.ringing(call);
        assertEquals(CallLifecycleManager.HangupAction.CANCEL, manager.requestHangup(call));
        assertEquals(CallLifecycleManager.HangupAction.IGNORE, manager.requestHangup(call));
        assertFalse(manager.isActive(call));
    }

    @Test public void locallyEndedInvite200RequiresAckByeWithoutMedia() {
        CallSession old = outgoing("old");
        manager.requestHangup(old);
        manager.finish(old, "cancel");
        CallSession current = outgoing("current");
        for (int i = 0; i < 3; i++) {
            assertEquals(CallLifecycleManager.AcceptResult.TERMINATE_LATE, manager.accepted(old));
            assertFalse(manager.claimMedia(old));
        }
        assertSame(current, manager.active());
        assertEquals(CallState.DIALING, current.getState());
    }

    @Test public void hangupBeforeIncomingAckCannotAllocateMediaOnLateAck() {
        CallSession call = incoming("incoming");
        manager.awaitingAck(call);
        assertEquals(CallLifecycleManager.HangupAction.WAIT, manager.requestHangup(call));
        assertEquals(CallLifecycleManager.AcceptResult.TERMINATE_LATE, manager.acknowledged(call));
        assertFalse(manager.claimMedia(call));
        assertNull(manager.active());
    }

    @Test public void rejectedCallCleanupFreesSlot() {
        CallSession call = outgoing("rejected");
        assertTrue(manager.finish(call, "rejected-603"));
        assertEquals("rejected-603", call.getEndReason());
        assertTrue(manager.claimCleanup(call));
        assertFalse(manager.claimCleanup(call));
        assertNotNull(outgoing("next"));
    }

    @Test public void busyCallCleanupFreesSlot() {
        CallSession call = outgoing("busy");
        assertTrue(manager.finish(call, "busy-486"));
        assertEquals(CallState.ENDED, call.getState());
        assertNull(manager.active());
        assertFalse(manager.finish(call, "duplicate"));
        assertEquals("busy-486", call.getEndReason());
    }

    @Test public void timeoutCleanupCancelsPendingTimer() {
        CallSession call = outgoing("timeout");
        AtomicInteger count = new AtomicInteger();
        manager.armTimer(call, CallLifecycleManager.TimerKind.INVITE_DEADLINE, 30000, count::incrementAndGet);
        assertTrue(manager.finish(call, "timeout"));
        assertTrue(scheduler.tasks.get(0).cancelled);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(0, count.get());
    }

    @Test public void remoteByeEndsCurrentAndClaimsCleanupOnlyOnce() {
        CallSession call = outgoing("remote-bye");
        establish(call);
        assertTrue(manager.claimMedia(call));
        assertTrue(manager.finish(call, "remote-bye"));
        assertTrue(manager.claimCleanup(call));
        assertFalse(manager.finish(call, "duplicate-bye"));
        assertFalse(manager.claimCleanup(call));
        assertEquals(CallState.ENDED, call.getState());
    }

    @Test public void mediaStartupFailureCanUnwindPartialAllocationOnce() {
        CallSession call = outgoing("media-failure");
        establish(call);
        assertTrue(manager.claimMedia(call));
        assertTrue(manager.finish(call, "media-startup-failed"));
        assertTrue(manager.claimCleanup(call));
        assertFalse(manager.claimCleanup(call));
        assertFalse(manager.claimMedia(call));
        assertNull(manager.active());
        assertNotNull(outgoing("retry"));
    }

    @Test public void finishingAnOldCallCannotClearNewActiveCall() {
        CallSession old = outgoing("old");
        manager.requestHangup(old);
        CallSession next = outgoing("new");
        assertTrue(manager.finish(old, "late-487"));
        assertSame(next, manager.active());
        assertEquals(CallState.DIALING, next.getState());
        assertFalse(manager.ringing(old));
        assertFalse(manager.provisional(old));
    }

    @Test public void autoHangupFiresOnceForItsEstablishedIdentity() {
        CallSession call = outgoing("auto");
        establish(call);
        AtomicInteger count = new AtomicInteger();
        assertTrue(manager.armAutoHangup(call, 100, () -> {
            synchronized (manager) {
                if (manager.isActive(call)) {
                    count.incrementAndGet();
                    manager.requestHangup(call);
                    manager.finish(call, "duration");
                }
            }
        }));
        assertEquals(100, scheduler.tasks.get(0).delay);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(1, count.get());
        assertEquals(CallState.ENDED, call.getState());
    }

    @Test public void oldAutoTimerCannotEndNewEstablishedCallEvenIfCancellationLostRace() {
        CallSession old = outgoing("old");
        establish(old);
        AtomicInteger oldCallback = new AtomicInteger();
        manager.armAutoHangup(old, 100, oldCallback::incrementAndGet);
        FakeTask timer = scheduler.tasks.get(0);
        manager.requestHangup(old);
        manager.finish(old, "early-end");
        CallSession next = outgoing("new");
        establish(next);
        timer.fireEvenIfCancelled();
        assertEquals(0, oldCallback.get());
        assertEquals(CallState.ESTABLISHED, next.getState());
        assertSame(next, manager.active());
    }

    @Test public void timerCallbackRechecksIdentityAfterDispatchRace() throws Exception {
        CallSession old = outgoing("old");
        establish(old);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch continueCallback = new CountDownLatch(1);
        AtomicInteger wronglyEnded = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        manager.armAutoHangup(old, 1, () -> {
            callbackEntered.countDown();
            try {
                if (!continueCallback.await(2, TimeUnit.SECONDS)) throw new AssertionError("callback barrier");
                synchronized (manager) {
                    if (manager.isActive(old)) {
                        wronglyEnded.incrementAndGet();
                        manager.finish(old, "timer");
                    }
                }
            } catch (Throwable e) { error.set(e); }
        });
        Thread timerThread = new Thread(scheduler.tasks.get(0)::fireEvenIfCancelled);
        timerThread.start();
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
        manager.requestHangup(old);
        manager.finish(old, "manual");
        CallSession next = outgoing("new");
        continueCallback.countDown();
        timerThread.join(3000);
        assertFalse(timerThread.isAlive());
        assertNull(error.get());
        assertEquals(0, wronglyEnded.get());
        assertSame(next, manager.active());
    }

    @Test public void establishedCallCancelsInviteDeadline() {
        CallSession call = outgoing("deadline");
        AtomicInteger fired = new AtomicInteger();
        manager.armTimer(call, CallLifecycleManager.TimerKind.INVITE_DEADLINE, 20, fired::incrementAndGet);
        establish(call);
        assertTrue(scheduler.tasks.get(0).cancelled);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(0, fired.get());
    }

    @Test public void incomingAckCancelsAckDeadline() {
        CallSession call = incoming("ack");
        manager.awaitingAck(call);
        AtomicInteger fired = new AtomicInteger();
        manager.armTimer(call, CallLifecycleManager.TimerKind.ACK_DEADLINE, 20, fired::incrementAndGet);
        manager.acknowledged(call);
        assertTrue(scheduler.tasks.get(0).cancelled);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(0, fired.get());
    }

    @Test public void timerRequiresActiveAndAppropriateState() {
        CallSession call = outgoing("state");
        assertFalse(manager.armAutoHangup(call, 1, () -> fail("not established")));
        assertFalse(manager.armTimer(call, CallLifecycleManager.TimerKind.AUTO_HANGUP, 1,
                () -> fail("not established")));
        assertEquals(0, scheduler.tasks.size());
        manager.finish(call, "done");
        assertFalse(manager.armTimer(call, CallLifecycleManager.TimerKind.INVITE_DEADLINE, 1,
                () -> fail("ended")));
    }

    @Test public void cancelledTimerCannotBeArmedAgainByDuplicateResponse() {
        CallSession call = outgoing("cancel-timer");
        establish(call);
        assertTrue(manager.armAutoHangup(call, 1, () -> { }));
        assertTrue(manager.cancelTimer(call, CallLifecycleManager.TimerKind.AUTO_HANGUP));
        assertFalse(manager.cancelTimer(call, CallLifecycleManager.TimerKind.AUTO_HANGUP));
        assertFalse(manager.armAutoHangup(call, 2, () -> fail("rearmed")));
        assertEquals(1, scheduler.tasks.size());
    }

    @Test public void negativeTimerDelayIsRejected() {
        CallSession call = outgoing("negative");
        establish(call);
        expect(IllegalArgumentException.class, () -> manager.armAutoHangup(call, -1, () -> { }));
        assertEquals(0, scheduler.tasks.size());
    }

    @Test public void schedulerFailureDoesNotLeavePhantomTimer() {
        CallSession call = outgoing("schedule-fail");
        establish(call);
        scheduler.reject = true;
        expect(IllegalStateException.class, () -> manager.armAutoHangup(call, 1, () -> { }));
        scheduler.reject = false;
        assertTrue(manager.armAutoHangup(call, 1, () -> { }));
        assertEquals(1, scheduler.tasks.size());
    }

    @Test public void nullSchedulerHandleIsRejectedAndCanBeRetried() {
        CallSession call = outgoing("null-handle");
        establish(call);
        scheduler.nullHandle = true;
        expect(IllegalStateException.class, () -> manager.armAutoHangup(call, 1, () -> { }));
        scheduler.nullHandle = false;
        assertTrue(manager.armAutoHangup(call, 1, () -> { }));
    }

    @Test public void authRetryKeepsCallAndUpdatesTransactionBinding() {
        CallSession call = outgoing("auth");
        String identity = call.getId();
        manager.provisional(call);
        manager.ringing(call);
        assertTrue(manager.bindInvite(call, 2, "z9hG4bK-auth-retry"));
        assertEquals(identity, call.getId());
        assertEquals("auth", call.getCallId());
        assertEquals("101", call.getPeer());
        assertEquals(CallState.DIALING, call.getState());
        assertFalse(manager.matchesInvite(call, 1, "z9hG4bK-auth"));
        assertTrue(manager.matchesInvite(call, 2, "z9hG4bK-auth-retry"));
        assertEquals(CallLifecycleManager.HangupAction.WAIT, manager.requestHangup(call));
    }

    @Test public void authRetryRequiresIncreasingCseqAndFreshNonEmptyBranch() {
        CallSession call = outgoing("auth");
        expect(IllegalArgumentException.class, () -> manager.bindInvite(call, 1, "other"));
        expect(IllegalArgumentException.class, () -> manager.bindInvite(call, 0, "other"));
        expect(IllegalArgumentException.class, () -> manager.bindInvite(call, 2, ""));
        expect(IllegalArgumentException.class, () -> manager.bindInvite(call, 0x80000000L, "other"));
        assertEquals(1, call.getInviteCSeq());
    }

    @Test public void cannotRebindEstablishedOrEndedInvite() {
        CallSession call = outgoing("bound");
        establish(call);
        assertFalse(manager.bindInvite(call, 2, "next"));
        manager.finish(call, "done");
        assertFalse(manager.bindInvite(call, 2, "next"));
    }

    @Test public void inviteMatchingRequiresIdentityCseqAndAvailableBranch() {
        CallSession call = outgoing("match");
        assertTrue(manager.matchesInvite(call, 1, "z9hG4bK-match"));
        assertTrue(manager.matchesInvite(call, 1, null));
        assertFalse(manager.matchesInvite(call, 2, null));
        assertFalse(manager.matchesInvite(call, 1, "wrong"));
        CallSession impostor = new CallSession("match", 1, "z9hG4bK-match",
                CallSession.Direction.OUTGOING, "101", false, null, 0);
        assertFalse(manager.matchesInvite(impostor, 1, null));
        assertFalse(manager.isActive(impostor));
        assertEquals(CallLifecycleManager.AcceptResult.IGNORE, manager.accepted(impostor));
        assertFalse(manager.finish(impostor, "forged"));
    }

    @Test public void incomingAndOutgoingEventsCannotBeConfused() {
        CallSession out = outgoing("out");
        assertFalse(manager.awaitingAck(out));
        assertEquals(CallLifecycleManager.AcceptResult.IGNORE, manager.acknowledged(out));
        manager.finish(out, "done");
        CallSession in = incoming("in");
        assertEquals(CallLifecycleManager.AcceptResult.IGNORE, manager.accepted(in));
        assertFalse(manager.bindInvite(in, 2, "retry"));
        assertFalse(manager.provisional(in));
    }

    @Test public void staleProvisionalCannotRegressEstablishedState() {
        CallSession call = outgoing("established");
        establish(call);
        assertFalse(manager.ringing(call));
        assertFalse(manager.provisional(call));
        assertEquals(CallState.ESTABLISHED, call.getState());
    }

    @Test public void finishIsIdempotentAndPreservesFirstReasonAndTimestamp() {
        CallSession call = outgoing("done");
        clock.set(1234);
        assertTrue(manager.finish(call, "first"));
        clock.set(5678);
        assertFalse(manager.finish(call, "second"));
        assertEquals(1234, call.getEndedAtMillis());
        assertEquals("first", call.getEndReason());
    }

    @Test public void emptyFinishReasonIsNormalizedWithoutNull() {
        CallSession call = outgoing("done");
        manager.finish(call, null);
        assertEquals("", call.getEndReason());
    }

    @Test public void unknownSessionsCannotClaimResourcesOrTimers() {
        assertNull(manager.find("missing"));
        assertFalse(manager.isActive(null));
        assertFalse(manager.finish(null, "missing"));
        assertFalse(manager.claimMedia(null));
        assertFalse(manager.claimCleanup(null));
        assertFalse(manager.cancelTimer(null, CallLifecycleManager.TimerKind.AUTO_HANGUP));
        assertEquals(CallLifecycleManager.HangupAction.IGNORE, manager.requestHangup(null));
        assertEquals(CallLifecycleManager.AcceptResult.IGNORE, manager.accepted(null));
        assertEquals(CallLifecycleManager.AcceptResult.IGNORE, manager.acknowledged(null));
    }

    @Test public void normalCallThenConferenceKeepsSeparateIntentAndCleanup() {
        CallSession normal = outgoing("normal");
        establish(normal);
        assertTrue(manager.claimMedia(normal));
        manager.requestHangup(normal);
        manager.finish(normal, "switch");
        assertTrue(manager.claimCleanup(normal));
        CallSession conference = manager.beginOutgoing("room", 1, "room-branch", "*6*1", true, "1");
        establish(conference);
        assertTrue(manager.claimMedia(conference));
        assertEquals("101", normal.getPeer());
        assertNull(normal.getConferenceRoom());
        assertEquals("*6*1", conference.getPeer());
        assertEquals("1", conference.getConferenceRoom());
        assertFalse(manager.claimMedia(normal));
        assertFalse(manager.claimCleanup(normal));
        assertSame(conference, manager.active());
    }

    @Test public void conferenceThenAudioOnlyNormalCallDoesNotReuseVideoIntent() {
        CallSession conference = manager.beginOutgoing("room", 1, "room-branch", "*6*1", true, "1");
        establish(conference);
        manager.requestHangup(conference);
        manager.finish(conference, "leave");
        CallSession normal = manager.beginOutgoing("plain", 1, "plain-branch", "102", false, null);
        establish(normal);
        assertFalse(normal.isVideo());
        assertNull(normal.getConferenceRoom());
        assertEquals("102", normal.getPeer());
    }

    @Test public void repeatedCallsDoNotRetainUnboundedStateOrTimers() {
        for (int i = 0; i < 100; i++) {
            CallSession call = outgoing("loop-" + i);
            establish(call);
            manager.armAutoHangup(call, 10, () -> fail("old timer"));
            manager.requestHangup(call);
            manager.finish(call, "done");
            assertTrue(manager.claimCleanup(call));
            assertTrue(manager.retainedSessionCount() <= 4);
        }
        assertNull(manager.active());
        assertNull(manager.find("loop-0"));
        assertNotNull(manager.find("loop-99"));
        for (FakeTask task : scheduler.tasks) {
            assertTrue(task.cancelled);
            task.fireEvenIfCancelled();
        }
    }

    @Test public void boundedHistoryKeepsCurrentCallWhenLimitIsOne() {
        manager.close();
        manager = new CallLifecycleManager(scheduler = new FakeScheduler(), clock::get, 1);
        CallSession first = outgoing("first");
        manager.requestHangup(first);
        CallSession current = outgoing("current");
        assertEquals(1, manager.retainedSessionCount());
        assertNull(manager.find("first"));
        assertSame(current, manager.active());
        assertSame(current, manager.find("current"));
        assertEquals(CallLifecycleManager.AcceptResult.IGNORE, manager.accepted(first));
    }

    @Test public void reusedSipCallIdIsRejectedWhileRetained() {
        CallSession first = outgoing("same");
        manager.finish(first, "done");
        expect(IllegalArgumentException.class, () -> outgoing("same"));
        assertNull(manager.active());
    }

    @Test public void invalidCallInputsDoNotOccupySlot() {
        expect(IllegalArgumentException.class, () -> outgoing(""));
        expect(IllegalArgumentException.class, () -> outgoing("bad\ncall"));
        expect(IllegalArgumentException.class, () -> manager.beginOutgoing("id", 0, "branch", "101", false, null));
        expect(IllegalArgumentException.class, () -> manager.beginOutgoing("id", 0x80000000L, "branch", "101", false, null));
        expect(IllegalArgumentException.class, () -> manager.beginOutgoing("id", 1, "", "101", false, null));
        expect(IllegalArgumentException.class, () -> manager.beginOutgoing("id", 1, "branch", "", false, null));
        expect(IllegalArgumentException.class, () -> manager.beginOutgoing("id", 1, "branch", "101", false, ""));
        assertNull(manager.active());
        assertEquals(0, manager.retainedSessionCount());
    }

    @Test public void invalidManagerConfigurationIsRejected() {
        expect(NullPointerException.class, () -> new CallLifecycleManager(null));
        expect(NullPointerException.class, () -> new CallLifecycleManager(scheduler, null, 4));
        expect(IllegalArgumentException.class, () -> new CallLifecycleManager(scheduler, clock::get, 0));
    }

    @Test public void closeCancelsEveryTimerAndRejectsNewCalls() {
        CallSession call = outgoing("closing");
        AtomicInteger count = new AtomicInteger();
        manager.armTimer(call, CallLifecycleManager.TimerKind.INVITE_DEADLINE, 1, count::incrementAndGet);
        manager.close();
        assertTrue(scheduler.closed);
        assertEquals(1, scheduler.closeCount);
        assertNull(manager.active());
        assertEquals(CallState.ENDED, call.getState());
        assertEquals("shutdown", call.getEndReason());
        assertTrue(scheduler.tasks.get(0).cancelled);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(0, count.get());
        expect(IllegalStateException.class, () -> outgoing("after-close"));
        manager.close();
        assertEquals(1, scheduler.closeCount);
    }

    @Test public void callbackRunsOutsideManagerMonitor() {
        CallSession call = outgoing("outside");
        establish(call);
        AtomicInteger checked = new AtomicInteger();
        manager.armAutoHangup(call, 1, () -> {
            assertFalse(Thread.holdsLock(manager));
            checked.incrementAndGet();
        });
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(1, checked.get());
    }

    @Test public void callbacksCanSafelyReenterTheLifecycleMonitor() {
        CallSession call = outgoing("reenter");
        establish(call);
        manager.armAutoHangup(call, 1, () -> {
            synchronized (manager) {
                if (manager.isActive(call)) {
                    manager.requestHangup(call);
                    manager.finish(call, "timer");
                }
            }
        });
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(CallState.ENDED, call.getState());
        assertNull(manager.active());
    }


    @Test public void retainedEndTimerRunsForOldSignallingWithoutTouchingNewMedia() {
        CallSession old = incoming("old-in");
        manager.awaitingAck(old);
        manager.requestHangup(old);
        manager.finish(old, "local-end-before-ack");
        AtomicInteger fallback = new AtomicInteger();
        assertTrue(manager.armRetainedEndTimer(old, 32000, fallback::incrementAndGet));
        CallSession next = outgoing("new-out");
        establish(next);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(1, fallback.get());
        assertSame(next, manager.active());
        assertEquals(CallState.ESTABLISHED, next.getState());
        assertFalse(manager.claimMedia(old));
    }

    @Test public void retainedEndTimerCannotBeAttachedToActiveCall() {
        CallSession call = incoming("active-in");
        manager.awaitingAck(call);
        assertFalse(manager.armRetainedEndTimer(call, 1, () -> fail("active fallback")));
        assertEquals(0, scheduler.tasks.size());
    }

    @Test public void lateAckCanCancelRetainedEndTimer() {
        CallSession old = incoming("late-ack");
        manager.awaitingAck(old);
        manager.requestHangup(old);
        manager.finish(old, "local-end");
        AtomicInteger fallback = new AtomicInteger();
        assertTrue(manager.armRetainedEndTimer(old, 32000, fallback::incrementAndGet));
        assertEquals(CallLifecycleManager.AcceptResult.TERMINATE_LATE, manager.acknowledged(old));
        assertTrue(manager.cancelTimer(old, CallLifecycleManager.TimerKind.END_DEADLINE));
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(0, fallback.get());
    }

    @Test public void retainedEndTimerSurvivesDuplicateFinishButCannotBeDuplicated() {
        CallSession old = incoming("old-in");
        manager.awaitingAck(old);
        manager.requestHangup(old);
        manager.finish(old, "first");
        AtomicInteger fallback = new AtomicInteger();
        assertTrue(manager.armRetainedEndTimer(old, 32000, fallback::incrementAndGet));
        assertFalse(manager.finish(old, "duplicate"));
        assertFalse(manager.armRetainedEndTimer(old, 32000, () -> fail("duplicate")));
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(1, fallback.get());
    }

    @Test public void historyPruningCancelsRetainedEndTimer() {
        CallSession old = incoming("pruned-in");
        manager.awaitingAck(old);
        manager.requestHangup(old);
        manager.finish(old, "local-end");
        AtomicInteger fallback = new AtomicInteger();
        assertTrue(manager.armRetainedEndTimer(old, 32000, fallback::incrementAndGet));
        for (int i = 0; i < 4; i++) {
            CallSession later = outgoing("later-" + i);
            manager.finish(later, "done");
        }
        assertNull(manager.find(old.getCallId()));
        assertTrue(scheduler.tasks.get(0).cancelled);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(0, fallback.get());
    }

    @Test public void shutdownCancelsRetainedEndTimer() {
        CallSession old = incoming("shutdown-in");
        manager.awaitingAck(old);
        manager.requestHangup(old);
        manager.finish(old, "local-end");
        AtomicInteger fallback = new AtomicInteger();
        manager.armRetainedEndTimer(old, 32000, fallback::incrementAndGet);
        manager.close();
        assertTrue(scheduler.tasks.get(0).cancelled);
        scheduler.tasks.get(0).fireEvenIfCancelled();
        assertEquals(0, fallback.get());
        assertFalse(manager.armRetainedEndTimer(old, 32000, () -> fail("closed")));
    }

    @Test public void retainedEndTimerRejectsInvalidDelayAndUnknownIdentity() {
        assertFalse(manager.armRetainedEndTimer(null, 1, () -> fail("unknown")));
        CallSession old = incoming("old-in");
        manager.awaitingAck(old);
        manager.requestHangup(old);
        manager.finish(old, "local-end");
        expect(IllegalArgumentException.class, () -> manager.armRetainedEndTimer(old, -1, () -> { }));
        assertEquals(0, scheduler.tasks.size());
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
            fail("Expected " + expected.getSimpleName());
        } catch (Throwable actual) {
            if (!expected.isInstance(actual)) {
                AssertionError failure = new AssertionError("Expected " + expected.getSimpleName()
                        + " but got " + actual);
                failure.initCause(actual);
                throw failure;
            }
        }
    }

    static final class FakeTask implements CallLifecycleManager.Cancellable {
        final Runnable action;
        final long delay;
        boolean cancelled;
        FakeTask(Runnable action, long delay) { this.action = action; this.delay = delay; }
        @Override public void cancel() { cancelled = true; }
        void fireEvenIfCancelled() { action.run(); }
    }

    static final class FakeScheduler implements CallLifecycleManager.Scheduler {
        final List<FakeTask> tasks = new ArrayList<>();
        boolean closed;
        boolean reject;
        boolean nullHandle;
        int closeCount;
        @Override public CallLifecycleManager.Cancellable schedule(Runnable action, long delay) {
            if (closed || reject) throw new IllegalStateException("scheduler unavailable");
            if (nullHandle) return null;
            FakeTask task = new FakeTask(action, delay);
            tasks.add(task);
            return task;
        }
        @Override public void close() { closed = true; closeCount++; }
    }
}
