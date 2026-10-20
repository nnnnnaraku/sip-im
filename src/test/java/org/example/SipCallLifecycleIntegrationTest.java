package org.example;

import org.junit.*;
import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual client SIP dispatch with real JAIN message objects; no stack, sockets, devices or server. */
public class SipCallLifecycleIntegrationTest {
    private AddressFactory addresses;
    private HeaderFactory headers;
    private MessageFactory messages;
    private Rig rig;
    private FakeScheduler scheduler;
    private FakeClient client;

    @Before public void setup() throws Exception {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        addresses = factory.createAddressFactory();
        headers = factory.createHeaderFactory();
        messages = factory.createMessageFactory();
        scheduler = new FakeScheduler();
        client = new FakeClient(new CallLifecycleManager(scheduler));
        rig = new Rig();
        field("sipProvider", rig.provider);
        field("addressFactory", addresses);
        field("headerFactory", headers);
        field("messageFactory", messages);
        field("username", "100");
        field("password", "synthetic-test-password");
        field("serverIp", "127.0.0.1");
        field("localIp", "127.0.0.1");
        field("isRegistered", true);
        client.setUi(new SipVideoCallClient.Ui() {
            public void onMessage(String peer, String text, boolean outgoing) { }
            public void onFile(String peer, String type, String name, String path, boolean outgoing) { }
            public void onCallState(String state, String peer) { client.events.add(state + ":" + peer); }
            public void onVideoFrame(byte[] jpeg, boolean local) { fail("No real video device may run"); }
        });
    }

    @After public void teardown() { if (client != null) client.shutdown(); }

    @Test public void normalInviteRingingAnswerAckAndLocalBye() throws Exception {
        Tx invite = dial("101", false);
        respond(invite, 180);
        assertTrue(client.events.contains("ringing:101"));
        respond(invite, 200);
        assertEquals(1, invite.dialog.acks);
        assertEquals(1, client.starts);
        assertTrue(client.events.contains("active:101"));
        client.hangup();
        assertEquals(1, sent(Request.BYE));
        assertEquals(1, client.stops);
        assertEquals(1, count("ended:101"));
    }

    @Test public void duplicate200AlwaysAckButMediaAndTimerStartOnce() throws Exception {
        Tx invite = dial("101", true);
        respond(invite, 200);
        int timers = scheduler.tasks.size();
        respond(invite, 200);
        respond(invite, 200);
        assertEquals(3, invite.dialog.acks);
        assertEquals(1, client.starts);
        assertEquals(timers, scheduler.tasks.size());
        assertEquals(1, count("active:101"));
    }

    @Test public void cancelBeforeAnyProvisionalWaitsThenCancelsOldInviteAfterRedial() throws Exception {
        Tx old = dial("101", false);
        client.hangup();
        assertEquals(0, sent(Request.CANCEL));
        Tx fresh = dial("102", false);
        respond(old, 100);
        assertEquals(1, sent(Request.CANCEL));
        assertEquals(callId(old.request), callId(last(Request.CANCEL).request));
        respond(old, 180);
        assertEquals(1, sent(Request.CANCEL));
        respond(fresh, 200);
        assertEquals(1, client.starts);
        assertEquals(0, sent(Request.BYE));
        assertEquals(1, count("active:102"));
    }

    @Test public void cancelDuringRingingUsesCancelNotBye() throws Exception {
        Tx invite = dial("101", false);
        respond(invite, 180);
        client.hangup();
        assertEquals(1, sent(Request.CANCEL));
        assertEquals(0, sent(Request.BYE));
        assertEquals(0, client.starts);
        respond(invite, 487);
        assertEquals(1, count("ended:101"));
    }

    @Test public void cancelRace200IsAckedAndByedWithoutStartingMedia() throws Exception {
        Tx old = dial("101", false);
        respond(old, 180);
        client.hangup();
        Tx fresh = dial("102", false);
        respond(old, 200);
        assertEquals(1, old.dialog.acks);
        assertEquals(1, sent(Request.BYE));
        assertEquals(callId(old.request), callId(last(Request.BYE).request));
        assertEquals(0, client.starts);
        respond(fresh, 200);
        assertEquals(1, client.starts);
        int stopped = client.stops;
        respond(old, 200);
        assertEquals(2, old.dialog.acks);
        assertEquals(1, sent(Request.BYE));
        assertEquals(stopped, client.stops);
        assertEquals(1, count("active:102"));
    }

    @Test public void late487AndCancel200CannotStopNewCall() throws Exception {
        Tx old = dial("101", false);
        respond(old, 180);
        client.hangup();
        Tx cancel = last(Request.CANCEL);
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        int stopped = client.stops;
        respond(cancel, 200);
        respond(old, 487);
        assertEquals(stopped, client.stops);
        assertEquals(1, client.starts);
        assertFalse(client.events.contains("ended:102"));
    }

    @Test public void staleAutoTimerCannotHangUpANewCall() throws Exception {
        Tx old = dial("101", false);
        respond(old, 200);
        List<Runnable> oldTimers = new ArrayList<Runnable>(scheduler.tasks);
        client.hangup();
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        int byes = sent(Request.BYE), stops = client.stops;
        // Deliberately run cancelled callbacks to model an already-dispatched scheduler race.
        for (Runnable timer : oldTimers) timer.run();
        assertEquals(byes, sent(Request.BYE));
        assertEquals(stops, client.stops);
        assertFalse(client.events.contains("ended:102"));
    }

    @Test public void earlyHangupThenImmediateRedialUsesFreshDialog() throws Exception {
        Tx old = dial("101", false);
        respond(old, 200);
        client.hangup();
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        client.hangup();
        assertEquals(2, sent(Request.BYE));
        assertEquals(1, old.dialog.byes);
        assertEquals(1, fresh.dialog.byes);
        assertNotEquals(callId(old.request), callId(fresh.request));
        assertEquals(2, client.starts);
        assertEquals(2, client.stops);
    }

    @Test public void simultaneousSecondOutgoingCallIsRejectedWithoutChangingPeer() throws Exception {
        Tx first = dial("101", false);
        try { client.makeCall(false, "102"); fail("Second active call must fail"); }
        catch (IllegalStateException expected) { }
        assertEquals(1, sent(Request.INVITE));
        respond(first, 200);
        assertEquals(1, count("active:101"));
        assertEquals(1, client.starts);
    }

    @Test public void rejectBusyRedirectAndServerFailureAllReleaseCallSlot() throws Exception {
        for (int status : new int[]{486, 603, 408, 480, 500, 302, 488}) {
            Tx invite = dial("101", false);
            respond(invite, status);
            assertEquals(0, client.starts);
        }
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        assertEquals(1, client.starts);
        assertEquals(7, count("ended:101"));
    }

    @Test public void inviteTransactionTimeoutAllowsRedialAndOldTimeoutIsHarmless() throws Exception {
        Tx old = dial("101", false);
        client.processTimeout(new TimeoutEvent(rig.provider, old.client, Timeout.TRANSACTION));
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        int stopped = client.stops;
        client.processTimeout(new TimeoutEvent(rig.provider, old.client, Timeout.TRANSACTION));
        assertEquals(stopped, client.stops);
        assertFalse(client.events.contains("ended:102"));
    }

    @Test public void mediaStartupFailureClosesPartialResourcesAndTerminatesItsDialog() throws Exception {
        client.failStart = true;
        Tx failed = dial("101", false);
        respond(failed, 200);
        assertEquals(1, failed.dialog.acks);
        assertEquals(1, failed.dialog.byes);
        assertEquals(1, client.stops);
        assertEquals(1, count("ended:101"));
        client.failStart = false;
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        assertEquals(2, client.starts);
        assertEquals(1, count("active:102"));
    }

    @Test public void remoteByeClosesOnlyMatchingDialogAndDuplicateIsHarmless() throws Exception {
        Tx invite = dial("101", false);
        respond(invite, 200);
        Request bye = remoteRequest(invite.dialog, Request.BYE, 2);
        incoming(bye, invite.dialog);
        assertEquals(1, client.stops);
        assertEquals(200, rig.lastServer().lastStatus());
        incoming(bye, invite.dialog);
        assertEquals(1, client.stops);
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        incoming(bye, invite.dialog);
        assertEquals(1, client.stops);
        assertFalse(client.events.contains("ended:102"));
    }

    @Test public void foreignDialogOrForgedByeCannotStopCurrentMedia() throws Exception {
        Tx invite = dial("101", false);
        respond(invite, 200);
        Request forged = remoteRequest(invite.dialog, Request.BYE, 2);
        ((FromHeader) forged.getHeader(FromHeader.NAME)).setTag("wrong-remote-tag");
        incoming(forged, null);
        assertEquals(481, rig.lastServer().lastStatus());
        assertEquals(0, client.stops);
        assertFalse(client.events.contains("ended:101"));
    }

    @Test public void oldDialogTerminatedEventDoesNotAffectNewDialog() throws Exception {
        Tx old = dial("101", false);
        respond(old, 200);
        client.hangup();
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        int stopped = client.stops;
        client.processDialogTerminated(new DialogTerminatedEvent(rig.provider, old.dialog.proxy));
        assertEquals(stopped, client.stops);
        assertFalse(client.events.contains("ended:102"));
        client.processDialogTerminated(new DialogTerminatedEvent(rig.provider, fresh.dialog.proxy));
        assertEquals(stopped + 1, client.stops);
        assertEquals(1, count("ended:102"));
    }

    @Test public void incomingInviteWaitsForAckBeforeMediaAndDuplicateAckDoesNotRestart() throws Exception {
        Request offer = inboundInvite("incoming-1", "101", "remote-in");
        Tx server = incoming(offer, null);
        assertEquals(200, server.lastStatus());
        assertNotNull(((ToHeader) server.responses.get(server.responses.size()-1).getHeader(ToHeader.NAME)).getTag());
        assertEquals(0, client.starts);
        Request ack = remoteRequest(server.dialog, Request.ACK, 1);
        incoming(ack, server.dialog);
        incoming(ack, server.dialog);
        assertEquals(1, client.starts);
        assertEquals(1, count("active:101"));
        incoming(remoteRequest(server.dialog, Request.BYE, 2), server.dialog);
        assertEquals(1, client.stops);
    }

    @Test public void secondIncomingInviteReturnsBusyWithoutMutatingCurrentCall() throws Exception {
        Tx original = dial("101", false);
        respond(original, 200);
        Tx other = incoming(inboundInvite("incoming-foreign", "102", "remote-other"), null);
        assertEquals(486, other.lastStatus());
        assertEquals(1, client.starts);
        assertEquals(0, client.stops);
        assertFalse(client.events.contains("active:102"));
    }

    @Test public void duplicateIncomingInviteDoesNotCreateSecondSessionOrChangeLocalTag() throws Exception {
        Request offer = inboundInvite("incoming-duplicate", "101", "remote-in");
        Tx first = incoming(offer, null);
        String localTag = first.dialog.localTag;
        incoming(offer, first.dialog);
        assertEquals(localTag, rig.lastServer().dialog.localTag);
        incoming(remoteRequest(first.dialog, Request.ACK, 1), first.dialog);
        assertEquals(1, client.starts);
        assertEquals(1, count("active:101"));
    }

    @Test public void authenticationRetryKeepsIdentityAndFrozenPeerWhileIncrementingCseq() throws Exception {
        Tx original = dial("101", false);
        client.setTargetUser("102");
        Response challenge = response(original, 407);
        ProxyAuthenticateHeader auth = headers.createProxyAuthenticateHeader("Digest");
        auth.setRealm("synthetic-realm");
        auth.setNonce("synthetic-nonce");
        auth.setAlgorithm("MD5");
        challenge.addHeader(auth);
        client.processResponse(new ResponseEvent(rig.provider, original.client, original.dialog.proxy, challenge));
        Tx retry = last(Request.INVITE);
        assertNotSame(original, retry);
        assertEquals(callId(original.request), callId(retry.request));
        assertEquals(((FromHeader) original.request.getHeader(FromHeader.NAME)).getTag(),
                     ((FromHeader) retry.request.getHeader(FromHeader.NAME)).getTag());
        assertEquals(cseq(original.request) + 1, cseq(retry.request));
        assertEquals(((SipURI)original.request.getRequestURI()).getUser(),
                     ((SipURI)retry.request.getRequestURI()).getUser());
        assertNotEquals(original.branch(), retry.branch());
        assertNotNull(retry.request.getHeader(ProxyAuthorizationHeader.NAME));
        respond(retry, 200);
        assertEquals(1, count("active:101"));
        assertEquals(0, count("active:102"));
        client.processTimeout(new TimeoutEvent(rig.provider, original.client, Timeout.TRANSACTION));
        assertEquals(0, client.stops);
    }

    @Test public void switchingContactsDuringCallDoesNotChangeCallPeerOrByeDialog() throws Exception {
        Tx invite = dial("101", false);
        client.setTargetUser("102");
        respond(invite, 180);
        respond(invite, 200);
        client.hangup();
        assertEquals(1, count("active:101"));
        assertEquals(1, count("ended:101"));
        assertFalse(client.events.contains("active:102"));
        assertEquals(callId(invite.request), callId(last(Request.BYE).request));
    }

    @Test public void ordinaryConferenceOrdinaryTransitionsResetMediaMode() throws Exception {
        for (String peer : new String[]{"101", "*6*1", "102"}) {
            Tx invite = dial(peer, true);
            respond(invite, 200);
            client.hangup();
        }
        assertEquals(Arrays.asList(null, "1", null), client.rooms);
        assertEquals(3, client.starts);
        assertEquals(3, client.stops);
    }

    @Test public void twentyCallsReleaseExactlyOnceAndOldResponsesDoNotRestartMedia() throws Exception {
        List<Tx> old = new ArrayList<Tx>();
        for (int i=0; i<20; i++) {
            Tx invite = dial("101", false);
            old.add(invite);
            respond(invite, 200);
            client.hangup();
            respond(invite, 200);
        }
        assertEquals(20, client.starts);
        assertEquals(20, client.stops);
        assertEquals(20, sent(Request.BYE));
        assertEquals(20, count("ended:101"));
        Tx current = dial("102", false);
        respond(current, 200);
        for (Tx prior : old) {
            respond(prior, 200);
            client.processDialogTerminated(new DialogTerminatedEvent(rig.provider, prior.dialog.proxy));
        }
        assertEquals(21, client.starts);
        assertEquals(20, client.stops);
    }

    @Test public void byeSendFailureStillReleasesResourcesAndAllowsNewCall() throws Exception {
        Tx old = dial("101", false);
        respond(old, 200);
        old.dialog.failBye = true;
        try { client.hangup(); } catch (SipException expected) { }
        assertEquals(1, client.stops);
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        assertEquals(2, client.starts);
    }

    @Test public void inviteSendFailureReleasesSlotWithoutStartingMedia() throws Exception {
        rig.failNextInvite = true;
        try { client.makeCall(false, "101"); fail("Send failure must be reported"); }
        catch (SipException expected) { }
        assertEquals(0, client.starts);
        Tx fresh = dial("102", false);
        respond(fresh, 200);
        assertEquals(1, client.starts);
    }


    @Test public void incomingCancelBeforeFinal200Returns200And487WithoutMedia() throws Exception {
        Request offer = inboundInvite("incoming-cancel-early", "101", "remote-in");
        rig.onRinging = () -> {
            try {
                Request cancel = (Request) offer.clone();
                cancel.setMethod(Request.CANCEL);
                ((CSeqHeader) cancel.getHeader(CSeqHeader.NAME)).setMethod(Request.CANCEL);
                cancel.removeContent();
                incoming(cancel, null);
            } catch (Exception e) { throw new AssertionError(e); }
        };
        Tx original = incoming(offer, null);
        assertEquals(487, original.lastStatus());
        assertFalse(original.responses.stream().anyMatch(r -> r.getStatusCode() == 200));
        assertEquals(200, rig.lastServer().lastStatus());
        assertEquals(0, client.starts);
        assertEquals(1, count("ended:101"));
        assertEquals("ended:101", client.events.get(client.events.size()-1));
        dial("102", false);
    }

    @Test public void incomingCancelAfterFinal200CannotRevokeAcceptedDialog() throws Exception {
        Request offer = inboundInvite("incoming-cancel-late", "101", "remote-in");
        Tx original = incoming(offer, null);
        Request cancel = (Request)offer.clone();
        cancel.setMethod(Request.CANCEL);
        ((CSeqHeader)cancel.getHeader(CSeqHeader.NAME)).setMethod(Request.CANCEL);
        cancel.removeContent();
        incoming(cancel, null);
        assertEquals(200, rig.lastServer().lastStatus());
        assertEquals(0, count("ended:101"));
        incoming(remoteRequest(original.dialog,Request.ACK,1),original.dialog);
        assertEquals(1, client.starts);
        incoming(remoteRequest(original.dialog,Request.BYE,2),original.dialog);
        assertEquals(1, client.stops);
    }

    @Test public void incomingAckTimeoutSendsByeWithoutStartingMedia() throws Exception {
        Tx original = incoming(inboundInvite("incoming-no-ack","101","remote-in"),null);
        scheduler.tasks.get(scheduler.tasks.size()-1).run();
        assertEquals(1,original.dialog.byes);
        assertEquals(0,client.starts);
        assertEquals(1,count("ended:101"));
        Tx fresh=dial("102",false);
        respond(fresh,200);
        assertEquals(1,client.starts);
    }

    @Test public void localHangupBeforeIncomingAckWaitsThenByesOldDialogAfterRedial() throws Exception {
        Tx original = incoming(inboundInvite("incoming-hangup","101","remote-in"),null);
        client.hangup();
        assertEquals(0,original.dialog.byes);
        Tx fresh=dial("102",false);
        respond(fresh,200);
        int stopped=client.stops;
        incoming(remoteRequest(original.dialog,Request.ACK,1),original.dialog);
        assertEquals(1,original.dialog.byes);
        assertEquals(1,client.starts);
        assertEquals(stopped,client.stops);
        assertFalse(client.events.contains("ended:102"));
    }

    @Test public void cancelledIncomingAckFallbackTerminatesOnlyOldDialog() throws Exception {
        Tx original=incoming(inboundInvite("incoming-fallback","101","remote-in"),null);
        client.hangup();
        Runnable fallback=scheduler.tasks.get(scheduler.tasks.size()-1);
        Tx fresh=dial("102",false);
        respond(fresh,200);
        int stopped=client.stops;
        fallback.run();
        assertEquals(1,original.dialog.byes);
        assertEquals(0,fresh.dialog.byes);
        assertEquals(stopped,client.stops);
        incoming(remoteRequest(original.dialog,Request.ACK,1),original.dialog);
        assertEquals(1,original.dialog.byes);
    }

    @Test public void responseWithWrongFromTagCannotStartOrEndCurrentCall() throws Exception {
        Tx original=dial("101",false);
        Response forged=response(original,200);
        ((FromHeader)forged.getHeader(FromHeader.NAME)).setTag("forged-local-tag");
        client.processResponse(new ResponseEvent(rig.provider,original.client,original.dialog.proxy,forged));
        assertEquals(0,client.starts);
        assertEquals(0,original.dialog.acks);
        respond(original,200);
        assertEquals(1,client.starts);
    }

    @Test public void duplicate200WithoutClientTransactionStillAcknowledgesSameDialog() throws Exception {
        Tx original=dial("101",false);
        respond(original,200);
        client.processResponse(new ResponseEvent(rig.provider,null,original.dialog.proxy,response(original,200)));
        assertEquals(2,original.dialog.acks);
        assertEquals(1,client.starts);
    }

    @Test public void forked2xxIsAcknowledgedAndTerminatedWithoutReplacingMedia() throws Exception {
        Tx original=dial("101",false);
        respond(original,200);
        FakeDialog fork=new FakeDialog(original.request,false);
        Response forked=response(original,200);
        ((ToHeader)forked.getHeader(ToHeader.NAME)).setTag(fork.remoteTag);
        client.processResponse(new ResponseEvent(rig.provider,original.client,fork.proxy,forked));
        client.processResponse(new ResponseEvent(rig.provider,original.client,fork.proxy,forked));
        assertEquals(2,fork.acks);
        assertEquals(1,fork.byes);
        assertEquals(0,original.dialog.byes);
        assertEquals(1,client.starts);
        assertEquals(0,client.stops);
    }


    @Test public void establishedCallIgnoresLateInviteTimeoutAndErrorFinal() throws Exception {
        Tx original=dial("101",false);
        respond(original,200);
        client.processTimeout(new TimeoutEvent(rig.provider,original.client,Timeout.TRANSACTION));
        respond(original,486);
        respond(original,180);
        assertEquals(0,client.stops);
        assertEquals(0,original.dialog.byes);
        assertEquals(0,count("ended:101"));
        assertEquals("active:101",client.events.get(client.events.size()-1));
    }

    @Test public void incomingEstablishedCallIgnoresLateServerTransactionTimeout() throws Exception {
        Tx original=incoming(inboundInvite("incoming-late-timeout","101","remote-in"),null);
        incoming(remoteRequest(original.dialog,Request.ACK,1),original.dialog);
        client.processTimeout(new TimeoutEvent(rig.provider,original.serverTx,Timeout.TRANSACTION));
        assertEquals(1,client.starts);
        assertEquals(0,client.stops);
        assertEquals(0,count("ended:101"));
    }

    @Test public void duplicateAckSendFailureDoesNotTerminateAlreadyEstablishedCall() throws Exception {
        Tx original=dial("101",false);
        respond(original,200);
        original.dialog.failAck=true;
        respond(original,200);
        assertEquals(1,client.starts);
        assertEquals(0,client.stops);
        assertEquals(0,original.dialog.byes);
        assertEquals(0,count("ended:101"));
        original.dialog.failAck=false;
        respond(original,200);
        assertEquals(2,original.dialog.acks);
        assertEquals(1,client.starts);
    }

    @Test public void failedForkByeDoesNotTerminatePrimaryDialog() throws Exception {
        Tx original=dial("101",false);
        respond(original,200);
        FakeDialog fork=new FakeDialog(original.request,false);
        fork.failBye=true;
        Response forked=response(original,200);
        ((ToHeader)forked.getHeader(ToHeader.NAME)).setTag(fork.remoteTag);
        client.processResponse(new ResponseEvent(rig.provider,original.client,fork.proxy,forked));
        assertEquals(1,fork.acks);
        assertEquals(0,original.dialog.byes);
        assertEquals(0,client.stops);
        assertEquals(0,count("ended:101"));
        fork.failBye=false;
        client.processResponse(new ResponseEvent(rig.provider,original.client,fork.proxy,forked));
        assertEquals(1,fork.byes);
        assertEquals(1,client.starts);
    }

    @Test public void initialAckSendFailureReleasesSlotAndLateSuccessDoesNotStartOldMedia() throws Exception {
        Tx old=dial("101",false);
        old.dialog.failAck=true;
        respond(old,200);
        assertEquals(0,client.starts);
        assertEquals(1,count("ended:101"));
        Tx fresh=dial("102",false);
        respond(fresh,200);
        old.dialog.failAck=false;
        respond(old,200);
        assertEquals(1,old.dialog.acks);
        assertEquals(1,old.dialog.byes);
        assertEquals(1,client.starts);
        assertEquals(0,client.stops);
    }

    @Test public void inviteDeadlineCancelsItsPendingCallAndAllowsNextCall() throws Exception {
        Tx old=dial("101",false);
        Runnable deadline=scheduler.tasks.get(scheduler.tasks.size()-1);
        respond(old,180);
        deadline.run();
        assertEquals(1,sent(Request.CANCEL));
        assertEquals(0,client.starts);
        assertEquals(1,count("ended:101"));
        Tx fresh=dial("102",false);
        respond(fresh,200);
        deadline.run();
        assertEquals(0,fresh.dialog.byes);
        assertEquals(1,client.starts);
    }

    @Test public void autoHangupTerminatesOnlyItsEstablishedCallOnce() throws Exception {
        Tx original=dial("101",false);
        respond(original,200);
        Runnable auto=scheduler.tasks.get(scheduler.tasks.size()-1);
        auto.run();
        auto.run();
        assertEquals(1,original.dialog.byes);
        assertEquals(1,client.stops);
        assertEquals(1,count("ended:101"));
    }

    @Test public void privateMessagesDoNotWaitForCallLifecycleMonitor() throws Exception {
        Request message=inboundInvite("message-independent","101","remote-msg");
        message.setMethod(Request.MESSAGE);
        ((CSeqHeader)message.getHeader(CSeqHeader.NAME)).setMethod(Request.MESSAGE);
        message.setContent("中文消息".getBytes(StandardCharsets.UTF_8),headers.createContentTypeHeader("text","plain"));
        Tx incoming=rig.newTx(message,true,null);
        Field monitor=SipVideoCallClient.class.getDeclaredField("calls");
        monitor.setAccessible(true);
        Object lock=monitor.get(client);
        java.util.concurrent.CountDownLatch completed=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread=new Thread(()->{
            try{client.processRequest(new RequestEvent(rig.provider,incoming.serverTx,null,message));}
            catch(Throwable failure){error.set(failure);}
            finally{completed.countDown();}
        },"Synthetic-MESSAGE");
        thread.setDaemon(true);
        boolean independent;
        synchronized(lock){
            thread.start();
            independent=completed.await(2,java.util.concurrent.TimeUnit.SECONDS);
        }
        thread.join(2000);
        assertFalse(thread.isAlive());
        if(error.get()!=null)throw new AssertionError(error.get());
        assertTrue("MESSAGE must remain outside the call lifecycle monitor",independent);
        assertEquals(200,incoming.lastStatus());
        assertEquals(0,client.starts);
    }


    @Test public void incomingCancelWithWrongToTagIsRejectedAndCannotEndCall() throws Exception {
        Request offer=inboundInvite("incoming-bad-cancel","101","remote-in");
        Tx original=incoming(offer,null);
        Request cancel=(Request)offer.clone();
        cancel.setMethod(Request.CANCEL);
        ((CSeqHeader)cancel.getHeader(CSeqHeader.NAME)).setMethod(Request.CANCEL);
        ((ToHeader)cancel.getHeader(ToHeader.NAME)).setTag("wrong-local-tag");
        cancel.removeContent();
        incoming(cancel,null);
        assertEquals(481,rig.lastServer().lastStatus());
        assertEquals(0,count("ended:101"));
        incoming(remoteRequest(original.dialog,Request.ACK,1),original.dialog);
        assertEquals(1,client.starts);
    }

    @Test public void duplicateIncomingInviteWithWrongToTagDoesNotReplaySuccess() throws Exception {
        Request offer=inboundInvite("incoming-bad-duplicate","101","remote-in");
        Tx original=incoming(offer,null);
        Request malformed=(Request)offer.clone();
        ((ToHeader)malformed.getHeader(ToHeader.NAME)).setTag("wrong-local-tag");
        Tx rejected=incoming(malformed,null);
        assertEquals(481,rejected.lastStatus());
        assertFalse(rejected.responses.stream().anyMatch(r->r.getStatusCode()==200));
        incoming(remoteRequest(original.dialog,Request.ACK,1),original.dialog);
        assertEquals(1,client.starts);
        assertEquals(0,client.stops);
    }


    @Test public void incomingLocalDeclineThenCancelNeverSendsTwoFinalResponses() throws Exception {
        Request offer=inboundInvite("incoming-local-decline","101","remote-in");
        rig.onRinging=()->{
            try{client.hangup();}catch(Exception e){throw new AssertionError(e);}
        };
        Tx original=incoming(offer,null);
        assertEquals(603,original.lastStatus());
        Request cancel=(Request)offer.clone();
        cancel.setMethod(Request.CANCEL);
        ((CSeqHeader)cancel.getHeader(CSeqHeader.NAME)).setMethod(Request.CANCEL);
        cancel.removeContent();
        incoming(cancel,null);
        assertEquals(200,rig.lastServer().lastStatus());
        assertEquals(1,original.responses.stream().filter(r->r.getStatusCode()>=200).count());
        assertEquals(603,original.lastStatus());
        assertEquals(0,client.starts);
        dial("102",false);
    }

    @Test public void nativeMediaLinkageErrorCleansResourcesAndStillPropagates() throws Exception {
        client.startError=new UnsatisfiedLinkError("Synthetic native loader failure");
        Tx original=dial("101",false);
        try{respond(original,200);fail("Native linkage error must not be swallowed");}
        catch(UnsatisfiedLinkError expected){assertSame(client.startError,expected);}
        assertEquals(1,client.stops);
        assertEquals(1,count("ended:101"));
        client.startError=null;
        Tx fresh=dial("102",false);
        respond(fresh,200);
        assertEquals(2,client.starts);
        assertEquals(1,count("active:102"));
    }

    private Tx dial(String peer, boolean video) throws Exception {
        client.makeCall(video, peer);
        return last(Request.INVITE);
    }
    private void field(String name, Object value) throws Exception {
        Field field = SipVideoCallClient.class.getDeclaredField(name);
        field.setAccessible(true); field.set(client, value);
    }
    private int count(String event) { return Collections.frequency(client.events, event); }
    private int sent(String method) {
        int count = 0;
        for (Tx tx : rig.transactions) if (!tx.server && method.equals(tx.request.getMethod()) && tx.sent > 0) count++;
        return count;
    }
    private Tx last(String method) {
        for (int i=rig.transactions.size()-1; i>=0; i--) {
            Tx tx=rig.transactions.get(i);
            if (!tx.server && method.equals(tx.request.getMethod())) return tx;
        }
        throw new AssertionError("No transaction for "+method);
    }
    private static String callId(Message message) { return ((CallIdHeader)message.getHeader(CallIdHeader.NAME)).getCallId(); }
    private static long cseq(Message message) { return ((CSeqHeader)message.getHeader(CSeqHeader.NAME)).getSeqNumber(); }
    private Response response(Tx tx, int status) throws Exception {
        Response response = messages.createResponse(status, tx.request);
        if (Request.INVITE.equals(tx.request.getMethod()) && status >= 180) {
            ((ToHeader) response.getHeader(ToHeader.NAME)).setTag(tx.dialog.remoteTag);
        }
        if (status >= 200 && status <300 && Request.INVITE.equals(tx.request.getMethod())) {
            response.addHeader(headers.createContactHeader(addresses.createAddress("sip:101@127.0.0.1:5072")));
            response.setContent(sdp().getBytes(StandardCharsets.UTF_8), headers.createContentTypeHeader("application","sdp"));
        }
        return response;
    }
    private void respond(Tx tx, int status) throws Exception {
        if (status < 200) tx.state = TransactionState.PROCEEDING;
        else tx.state = TransactionState.COMPLETED;
        if (status >=200 && status <300 && Request.INVITE.equals(tx.request.getMethod())) tx.dialog.state=DialogState.CONFIRMED;
        client.processResponse(new ResponseEvent(rig.provider, tx.client, tx.dialog.proxy, response(tx,status)));
    }
    private Request inboundInvite(String id,String user,String fromTag) throws Exception {
        List<ViaHeader> via = Collections.singletonList(headers.createViaHeader("127.0.0.1",5072,"udp","z9hG4bK-"+id));
        Request r=messages.createRequest(addresses.createURI("sip:100@127.0.0.1:5061"),Request.INVITE,
            headers.createCallIdHeader(id), headers.createCSeqHeader(1,Request.INVITE),
            headers.createFromHeader(addresses.createAddress("sip:"+user+"@127.0.0.1"),fromTag),
            headers.createToHeader(addresses.createAddress("sip:100@127.0.0.1"),null),via,headers.createMaxForwardsHeader(70));
        r.addHeader(headers.createContactHeader(addresses.createAddress("sip:"+user+"@127.0.0.1:5072")));
        r.setContent(sdp().getBytes(StandardCharsets.UTF_8),headers.createContentTypeHeader("application","sdp"));
        return r;
    }
    private Tx incoming(Request request, FakeDialog dialog) throws Exception {
        Tx tx=null;
        if (!Request.ACK.equals(request.getMethod())) {
            tx=rig.newTx(request,true,dialog);
        }
        client.processRequest(new RequestEvent(rig.provider,tx==null?null:tx.serverTx,dialog==null?(tx==null?null:tx.dialog.proxy):dialog.proxy,request));
        return tx;
    }
    private Request remoteRequest(FakeDialog d,String method,long sequence) throws Exception {
        List<ViaHeader> via=Collections.singletonList(headers.createViaHeader("127.0.0.1",5072,"udp","z9hG4bK-remote-"+sequence+"-"+method));
        Address local = d.server ? ((ToHeader)d.original.getHeader(ToHeader.NAME)).getAddress()
                                 : ((FromHeader)d.original.getHeader(FromHeader.NAME)).getAddress();
        Address remote = d.server ? ((FromHeader)d.original.getHeader(FromHeader.NAME)).getAddress()
                                  : ((ToHeader)d.original.getHeader(ToHeader.NAME)).getAddress();
        return messages.createRequest(addresses.createURI("sip:100@127.0.0.1:5061"),method,
            headers.createCallIdHeader(callId(d.original)),headers.createCSeqHeader(sequence,method),
            headers.createFromHeader(remote,d.remoteTag),headers.createToHeader(local,d.localTag),via,headers.createMaxForwardsHeader(70));
    }
    private static String sdp() {
        return "v=0\r\no=test 1 1 IN IP4 127.0.0.1\r\ns=test\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 8002 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n";
    }

    private final class Rig {
        final List<Tx> transactions=new ArrayList<Tx>();
        int ids;
        boolean failNextInvite;
        Runnable onRinging;
        final SipProvider provider=proxy(SipProvider.class,(p,m,a)->{
            switch(m.getName()){
                case "getNewCallId":return headers.createCallIdHeader("test-call-"+(++ids));
                case "getNewClientTransaction":{
                    Request request=(Request)a[0];
                    FakeDialog d=null;
                    if (!Request.INVITE.equals(request.getMethod())) {
                        String to=((ToHeader)request.getHeader(ToHeader.NAME)).getTag();
                        for(Tx prior:transactions) if(callId(prior.request).equals(callId(request))
                                && (to==null || to.equals(prior.dialog.remoteTag))) {d=prior.dialog;break;}
                    }
                    Tx tx=newTx(request,false,d);
                    if(Request.INVITE.equals(request.getMethod())&&failNextInvite){tx.failSend=true;failNextInvite=false;}
                    return tx.client;
                }
                case "getNewServerTransaction":return newTx((Request)a[0],true,null).serverTx;
                default:return defaultValue(p,m,a);
            }
        });
        Tx newTx(Request request,boolean server,FakeDialog d)throws Exception{
            if(d==null)d=new FakeDialog(request,server);
            Tx tx=new Tx(request,server,d);
            transactions.add(tx);
            return tx;
        }
        Tx lastServer(){
            for(int i=transactions.size()-1;i>=0;i--)if(transactions.get(i).server)return transactions.get(i);
            throw new AssertionError("No server transaction");
        }
    }

    private final class Tx implements InvocationHandler {
        final Request request; final boolean server; final FakeDialog dialog;
        final ClientTransaction client; final ServerTransaction serverTx;
        final List<Response> responses=new ArrayList<Response>();
        int sent; boolean failSend; Object applicationData;
        TransactionState state=TransactionState.CALLING;
        Tx(Request request,boolean server,FakeDialog dialog)throws Exception{
            this.request=request;this.server=server;this.dialog=dialog;
            ViaHeader via=(ViaHeader)request.getHeader(ViaHeader.NAME);
            if(via.getBranch()==null)via.setBranch("z9hG4bK-tx-"+UUID.randomUUID());
            client=server?null:proxy(ClientTransaction.class,this);
            serverTx=server?proxy(ServerTransaction.class,this):null;
        }
        String branch(){return ((ViaHeader)request.getHeader(ViaHeader.NAME)).getBranch();}
        int lastStatus(){return responses.isEmpty()?-1:responses.get(responses.size()-1).getStatusCode();}
        public Object invoke(Object p,Method m,Object[] a)throws Throwable{
            switch(m.getName()){
                case "getRequest":return request;
                case "getDialog":return dialog.proxy;
                case "getBranchId":return branch();
                case "getState":return state;
                case "setApplicationData":applicationData=a[0];return null;
                case "getApplicationData":return applicationData;
                case "sendRequest":if(failSend)throw new SipException("Synthetic send failure");sent++;return null;
                case "createCancel":case "createAck":{
                    String method=m.getName().equals("createCancel")?Request.CANCEL:Request.ACK;
                    Request r=(Request)request.clone();r.setMethod(method);
                    ((CSeqHeader)r.getHeader(CSeqHeader.NAME)).setMethod(method);r.removeContent();return r;
                }
                case "sendResponse":{
                    Response response=(Response)a[0];responses.add((Response)response.clone());
                    ToHeader to=(ToHeader)response.getHeader(ToHeader.NAME);
                    if(to!=null&&to.getTag()!=null)dialog.localTag=to.getTag();
                    if(response.getStatusCode()>=200&&response.getStatusCode()<300)dialog.state=DialogState.CONFIRMED;
                    if(response.getStatusCode()==180&&rig.onRinging!=null){Runnable hook=rig.onRinging;rig.onRinging=null;hook.run();}
                    return null;
                }
                default:return defaultValue(p,m,a);
            }
        }
    }

    private final class FakeDialog implements InvocationHandler {
        final Request original; final boolean server; final Dialog proxy;
        String localTag,remoteTag; int acks,byes; boolean failBye,failAck;
        Object data; DialogState state=DialogState.EARLY;
        FakeDialog(Request original,boolean server){
            this.original=original;this.server=server;
            localTag=server?((ToHeader)original.getHeader(ToHeader.NAME)).getTag():((FromHeader)original.getHeader(FromHeader.NAME)).getTag();
            remoteTag=server?((FromHeader)original.getHeader(FromHeader.NAME)).getTag():"remote-"+UUID.randomUUID();
            proxy=proxy(Dialog.class,this);
        }
        public Object invoke(Object p,Method m,Object[] a)throws Throwable{
            switch(m.getName()){
                case "getCallId":return original.getHeader(CallIdHeader.NAME);
                case "getLocalTag":return localTag;
                case "getRemoteTag":return remoteTag;
                case "getDialogId":return callId(original)+":"+localTag+":"+remoteTag;
                case "getState":return state;
                case "isServer":return server;
                case "getLocalParty":return (server?(ToHeader)original.getHeader(ToHeader.NAME):(FromHeader)original.getHeader(FromHeader.NAME)).getAddress();
                case "getRemoteParty":return (server?(FromHeader)original.getHeader(FromHeader.NAME):(ToHeader)original.getHeader(ToHeader.NAME)).getAddress();
                case "getLocalSeqNumber":return cseq(original);
                case "getRemoteSeqNumber":return cseq(original);
                case "setApplicationData":data=a[0];return null;
                case "getApplicationData":return data;
                case "createAck":case "createRequest":{
                    String method=m.getName().equals("createAck")?Request.ACK:(String)a[0];
                    Request r=(Request)original.clone();r.setMethod(method);r.removeContent();
                    ((FromHeader)r.getHeader(FromHeader.NAME)).setTag(localTag);
                    ((ToHeader)r.getHeader(ToHeader.NAME)).setTag(remoteTag);
                    CSeqHeader cs=(CSeqHeader)r.getHeader(CSeqHeader.NAME);
                    cs.setMethod(method);cs.setSeqNumber(m.getName().equals("createAck")?(Long)a[0]:cseq(original)+1);
                    return r;
                }
                case "sendAck":if(failAck)throw new SipException("Synthetic ACK send failure");acks++;return null;
                case "sendRequest":{
                    Tx tx=null;for(Tx t:rig.transactions)if(t.client==a[0])tx=t;
                    if(tx==null)throw new AssertionError("Unknown fake dialog transaction");
                    if(Request.BYE.equals(tx.request.getMethod())){
                        if(failBye)throw new SipException("Synthetic BYE send failure");
                        byes++;
                    }
                    tx.sent++;return null;
                }
                case "delete":state=DialogState.TERMINATED;return null;
                default:return defaultValue(p,m,a);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T>T proxy(Class<T> type,InvocationHandler handler){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    private static Object defaultValue(Object p,Method m,Object[] a){
        if(m.getName().equals("equals"))return p==a[0];
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
        if(m.getName().equals("toString"))return "Synthetic-"+m.getDeclaringClass().getSimpleName();
        Class<?> t=m.getReturnType();
        if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;
        if(t==short.class)return (short)0;if(t==byte.class)return(byte)0;
        return null;
    }
    private static final class FakeScheduler implements CallLifecycleManager.Scheduler {
        final List<Runnable> tasks=new ArrayList<Runnable>();
        public CallLifecycleManager.Cancellable schedule(Runnable action,long delayMillis){
            tasks.add(action);return ()->{};
        }
        public void close(){}
    }
    private static class FakeClient extends SipVideoCallClient {
        int starts,stops; boolean failStart; Error startError;
        final List<String> events=new ArrayList<String>();
        final List<String> rooms=new ArrayList<String>();
        FakeClient(CallLifecycleManager manager){super(manager);}
        @Override void startMedia(String sdp){
            starts++;
            try{
                Field f=SipVideoCallClient.class.getDeclaredField("conferenceRoom");f.setAccessible(true);
                rooms.add((String)f.get(this));
            }catch(Exception e){throw new AssertionError(e);}
            if(startError!=null)throw startError;
            if(failStart)throw new IllegalStateException("Synthetic media startup failure");
        }
        @Override void stopMedia(){stops++;}
    }
}
