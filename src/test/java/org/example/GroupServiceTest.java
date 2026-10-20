package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** Three independent services connected only through serialized group envelopes; no network/device. */
public class GroupServiceTest {
    private static final class Packet {
        final String from, to, body; final GroupTransport.Completion completion;
        Packet(String from, String to, String body, GroupTransport.Completion completion) { this.from=from;this.to=to;this.body=body;this.completion=completion; }
        GroupMessage envelope() { return GroupProtocol.decode(body); }
    }
    private static final class Bus {
        final AtomicLong clock = new AtomicLong(1000);
        final Map<String, GroupService> clients = new HashMap<>();
        final Deque<Packet> queue = new ArrayDeque<>();
        final List<Packet> sent = new ArrayList<>();
        String offline, dropAckFrom, loseTextTo, deferCompletionTo, dropStateTo;
        final List<GroupTransport.Completion> deferred = new ArrayList<>();
        Runnable onTextSend;
        Bus() { for(String user:Arrays.asList("100","101","102","103")) {
            clients.put(user,new GroupService(user,(to,body,completion)->{
                Packet packet=new Packet(user,to,body,completion);sent.add(packet);queue.add(packet);
                if("TEXT".equals(packet.envelope().type)&&onTextSend!=null)onTextSend.run();
            },clock::get));
        } }
        GroupService user(String name) { return clients.get(name); }
        void pump() {
            int limit=0;
            while(!queue.isEmpty()){
                assertTrue("Unexpected protocol feedback loop",++limit<5000);
                Packet packet=queue.remove();GroupMessage envelope=packet.envelope();
                if(packet.to.equals(offline)){packet.completion.completed(false,"SIP_480");continue;}
                boolean dropped=("ACK".equals(envelope.type)&&packet.from.equals(dropAckFrom))
                        ||("TEXT".equals(envelope.type)&&packet.to.equals(loseTextTo))
                        ||("STATE".equals(envelope.type)&&packet.to.equals(dropStateTo));
                int status=dropped?200:user(packet.to).receive(packet.from,packet.body);
                if("TEXT".equals(envelope.type)&&packet.to.equals(deferCompletionTo))deferred.add(packet.completion);
                else packet.completion.completed(status>=200&&status<300,status==200?"SIP_ACCEPTED":"SIP_"+status);
            }
        }
        String activeGroup(String name) {
            String id=user("100").create(name,Arrays.asList("101","102"));pump();
            user("101").join(id);pump();user("102").join(id);pump();return id;
        }
        Packet lastText() { for(int i=sent.size()-1;i>=0;i--)if("TEXT".equals(sent.get(i).envelope().type))return sent.get(i);throw new AssertionError(); }
    }
    private static JsonObject group(GroupService service,String id){for(com.google.gson.JsonElement e:service.snapshot())if(id.equals(e.getAsJsonObject().get("id").getAsString()))return e.getAsJsonObject();throw new AssertionError("Missing group");}
    private static JsonArray history(GroupService service,String id){return group(service,id).getAsJsonArray("messages");}
    private static String delivery(GroupService service,String id,String messageId,String recipient){for(com.google.gson.JsonElement e:history(service,id)){JsonObject m=e.getAsJsonObject();if(messageId.equals(m.get("id").getAsString()))return m.getAsJsonObject("deliveries").getAsJsonObject(recipient).get("status").getAsString();}throw new AssertionError();}
    private static void rejects(Runnable operation){try{operation.run();fail("Expected explicit rejection");}catch(IllegalArgumentException|IllegalStateException expected){assertNotNull(expected.getMessage());}}

    @Test public void threeIndependentUsersHaveInvitationThenMatchingAuthorityMembership(){
        Bus bus=new Bus();String id=bus.user("100").create("课程小组",Arrays.asList("101","102"));bus.pump();
        assertEquals("INVITED",group(bus.user("101"),id).get("localStatus").getAsString());
        rejects(()->bus.user("101").send(id,"not joined"));
        bus.user("101").join(id);bus.pump();bus.user("102").join(id);bus.pump();
        for(String user:Arrays.asList("100","101","102")){JsonObject g=group(bus.user(user),id);assertEquals(3,g.get("revision").getAsInt());assertTrue(g.get("canSend").getAsBoolean());assertEquals(3,g.getAsJsonArray("members").size());for(com.google.gson.JsonElement m:g.getAsJsonArray("members"))assertEquals("ACTIVE",m.getAsJsonObject().get("status").getAsString());}
    }
    @Test public void oneMemberReallyFansOutToTwoRecipientsAndNeedsApplicationAcks(){
        Bus bus=new Bus();String id=bus.activeGroup("fanout");bus.sent.clear();
        String messageId=bus.user("100").send(id,"你好 English <>& \"quoted\"\n😀");
        assertEquals("PENDING",delivery(bus.user("100"),id,messageId,"101"));
        assertEquals(2,bus.sent.size());assertEquals("101",bus.sent.get(0).to);assertEquals("102",bus.sent.get(1).to);bus.pump();
        for(String peer:Arrays.asList("101","102")){assertEquals(1,history(bus.user(peer),id).size());assertEquals(messageId,history(bus.user(peer),id).get(0).getAsJsonObject().get("id").getAsString());assertEquals("DELIVERED",delivery(bus.user("100"),id,messageId,peer));}
    }
    @Test public void everyMemberCanSendToTheOtherTwo(){Bus bus=new Bus();String id=bus.activeGroup("all");for(String user:Arrays.asList("100","101","102")){bus.user(user).send(id,"from "+user);bus.pump();}for(String user:Arrays.asList("100","101","102"))assertEquals(3,history(bus.user(user),id).size());}
    @Test public void twoGroupsAndIdenticalTextStaySeparated(){Bus bus=new Bus();String first=bus.activeGroup("first"),second=bus.activeGroup("second");assertNotEquals(first,second);bus.user("101").send(first,"same");bus.pump();for(String user:Arrays.asList("100","101","102")){assertEquals(1,history(bus.user(user),first).size());assertEquals(0,history(bus.user(user),second).size());}bus.user("102").send(second,"same");bus.pump();assertEquals(1,history(bus.user("100"),second).size());}
    @Test public void sameMessageIdCanExistInDifferentGroupsWithoutCrossGroupDedup(){Bus bus=new Bus();String a=bus.activeGroup("a"),b=bus.activeGroup("b");bus.user("100").send(a,"one");bus.pump();GroupMessage copy=bus.lastText().envelope();String sameId=copy.messageId;bus.user("100").send(b,"two");bus.pump();GroupMessage other=bus.lastText().envelope();other.messageId=sameId;assertEquals(200,bus.user("101").receive("100",GroupProtocol.encode(other)));assertEquals(2,history(bus.user("101"),b).size());}
    @Test public void privateAndFilePayloadsAreNotGroupEnvelopes(){Bus bus=new Bus();String id=bus.activeGroup("private");assertEquals(400,bus.user("101").receive("100","私聊文字"));assertEquals(400,bus.user("101").receive("100","#FILE|IMG|id|name|0|1|abc"));assertEquals(0,history(bus.user("101"),id).size());}
    @Test public void duplicateTextIsAckedButDisplayedOnce(){Bus bus=new Bus();String id=bus.activeGroup("dedup");bus.user("100").send(id,"once");bus.pump();Packet packet=bus.lastText();assertEquals(200,bus.user(packet.to).receive(packet.from,packet.body));bus.pump();assertEquals(1,history(bus.user(packet.to),id).size());}
    @Test public void sameIdDifferentContentIsRejected(){Bus bus=new Bus();String id=bus.activeGroup("conflict");bus.user("100").send(id,"original");bus.pump();Packet p=bus.lastText();GroupMessage altered=p.envelope();altered.text="changed";assertEquals(409,bus.user(p.to).receive(p.from,GroupProtocol.encode(altered)));assertEquals("original",history(bus.user(p.to),id).get(0).getAsJsonObject().get("text").getAsString());}
    @Test public void nonMemberAndSpoofedSipSenderAreRejected(){Bus bus=new Bus();String id=bus.activeGroup("identity");bus.user("100").send(id,"legit");bus.pump();GroupMessage m=bus.lastText().envelope();m.messageId=java.util.UUID.randomUUID().toString();m.senderId="103";assertEquals(403,bus.user("101").receive("103",GroupProtocol.encode(m)));m.senderId="100";assertEquals(403,bus.user("101").receive("102",GroupProtocol.encode(m)));assertEquals(1,history(bus.user("101"),id).size());}
    @Test public void wrongCapabilityAndOwnerCannotReplaceKnownGroup(){Bus bus=new Bus();String id=bus.activeGroup("capability");bus.user("100").send(id,"legit");bus.pump();GroupMessage m=bus.lastText().envelope();m.messageId=java.util.UUID.randomUUID().toString();m.token=java.util.UUID.randomUUID().toString();assertEquals(403,bus.user("101").receive("100",GroupProtocol.encode(m)));assertFalse(bus.user("101").snapshot().toString().contains(m.token));}
    @Test public void switchingCurrentContactDuringFanoutDoesNotChangeExplicitRecipients(){Bus bus=new Bus();String id=bus.activeGroup("switch");final String[] currentContact={"101"};bus.onTextSend=()->currentContact[0]="103";bus.sent.clear();bus.user("100").send(id,"fixed group");assertEquals("103",currentContact[0]);assertEquals("101",bus.sent.get(0).to);assertEquals("102",bus.sent.get(1).to);bus.pump();assertEquals(0,bus.user("103").snapshot().size());}
    @Test public void departedMemberIsExcludedAndCannotSendOrReceiveText(){Bus bus=new Bus();String id=bus.activeGroup("leave");bus.user("102").leave(id);bus.pump();assertEquals("LEFT",group(bus.user("102"),id).get("localStatus").getAsString());rejects(()->bus.user("102").send(id,"forbidden"));bus.sent.clear();bus.user("100").send(id,"remaining");bus.pump();assertFalse(bus.sent.stream().anyMatch(p->p.to.equals("102")&&p.envelope().type.equals("TEXT")));assertEquals(0,history(bus.user("102"),id).size());assertEquals(1,history(bus.user("101"),id).size());}
    @Test public void ownerExitClosesAllClientsWithoutChangingConferenceServices(){Bus bus=new Bus();String id=bus.activeGroup("close");bus.user("100").leave(id);bus.pump();for(String user:Arrays.asList("100","101","102")){assertEquals("CLOSED",group(bus.user(user),id).get("localStatus").getAsString());rejects(()->bus.user(user).send(id,"closed"));}}
    @Test public void localLeaveIsImmediateAndSyncRetriesWhenOwnerReturns(){Bus bus=new Bus();String id=bus.activeGroup("offline owner");bus.offline="100";bus.user("102").leave(id);bus.pump();assertEquals("LEAVE_PENDING",group(bus.user("102"),id).get("localStatus").getAsString());assertFalse(group(bus.user("102"),id).get("canSend").getAsBoolean());bus.offline=null;bus.user("102").sync(id);bus.pump();assertEquals("LEFT",group(bus.user("102"),id).get("localStatus").getAsString());}
    @Test public void syncRetriesALostJoinInsteadOfLeavingJoiningForever(){Bus bus=new Bus();String id=bus.user("100").create("join retry",Arrays.asList("101","102"));bus.pump();bus.offline="100";bus.user("101").join(id);bus.pump();assertEquals("JOINING",group(bus.user("101"),id).get("localStatus").getAsString());bus.offline=null;bus.user("101").sync(id);bus.pump();assertEquals("ACTIVE",group(bus.user("101"),id).get("localStatus").getAsString());}
    @Test public void partialFailureRetryKeepsIdAndDoesNotResendDeliveredRecipient(){Bus bus=new Bus();String id=bus.activeGroup("retry");bus.offline="102";String messageId=bus.user("100").send(id,"retry me");bus.pump();assertEquals("DELIVERED",delivery(bus.user("100"),id,messageId,"101"));assertEquals("FAILED",delivery(bus.user("100"),id,messageId,"102"));bus.offline=null;bus.sent.clear();bus.user("100").retry(id,messageId);bus.pump();assertFalse(bus.sent.stream().anyMatch(p->p.to.equals("101")&&p.envelope().type.equals("TEXT")));assertEquals("DELIVERED",delivery(bus.user("100"),id,messageId,"102"));assertEquals(1,history(bus.user("101"),id).size());assertEquals(1,history(bus.user("102"),id).size());}
    @Test public void sipAcceptanceWithoutAppAckTimesOutAndRetryDeduplicates(){Bus bus=new Bus();String id=bus.activeGroup("lost ack");bus.dropAckFrom="101";String messageId=bus.user("100").send(id,"once only");bus.pump();assertEquals("SIP_ACCEPTED",delivery(bus.user("100"),id,messageId,"101"));bus.clock.addAndGet(GroupService.ACK_TIMEOUT_MS);assertEquals("TIMEOUT",delivery(bus.user("100"),id,messageId,"101"));bus.dropAckFrom=null;bus.user("100").retry(id,messageId);bus.pump();assertEquals("DELIVERED",delivery(bus.user("100"),id,messageId,"101"));assertEquals(1,history(bus.user("101"),id).size());}
    @Test public void lateTransportFailureDoesNotDowngradeApplicationDelivery(){Bus bus=new Bus();String id=bus.activeGroup("late response");bus.deferCompletionTo="102";String messageId=bus.user("100").send(id,"late");bus.pump();assertEquals("DELIVERED",delivery(bus.user("100"),id,messageId,"102"));for(GroupTransport.Completion c:bus.deferred)c.completed(false,"SIP_TIMEOUT");assertEquals("DELIVERED",delivery(bus.user("100"),id,messageId,"102"));}
    @Test public void duplicateAcceptDoesNotBroadcastOrConsumeOperationBudget(){Bus bus=new Bus();String id=bus.activeGroup("control dedup");Packet accept=bus.sent.stream().filter(p->p.from.equals("101")&&p.envelope().type.equals("ACCEPT")).findFirst().get();bus.sent.clear();for(int i=0;i<1100;i++)assertEquals(200,bus.user("100").receive("101",accept.body));bus.pump();assertFalse(bus.sent.stream().anyMatch(p->p.envelope().type.equals("STATE")||p.envelope().type.equals("INVITE")));assertEquals(3,group(bus.user("100"),id).get("revision").getAsInt());bus.user("100").send(id,"still works");bus.pump();assertEquals(1,history(bus.user("102"),id).size());}
    @Test public void staleMembershipRejectsTextThenSyncAndRetryRecover(){Bus bus=new Bus();String id=bus.activeGroup("resync");bus.dropStateTo="101";bus.user("102").leave(id);bus.pump();assertEquals(3,group(bus.user("101"),id).get("revision").getAsInt());bus.dropStateTo=null;String messageId=bus.user("100").send(id,"after leave");bus.pump();assertEquals("FAILED",delivery(bus.user("100"),id,messageId,"101"));assertEquals(4,group(bus.user("101"),id).get("revision").getAsInt());assertEquals(0,history(bus.user("101"),id).size());bus.user("100").retry(id,messageId);bus.pump();assertEquals("DELIVERED",delivery(bus.user("100"),id,messageId,"101"));assertEquals(1,history(bus.user("101"),id).size());}
    @Test public void publicSnapshotsAreDetachedAndNeverExposeCapability(){Bus bus=new Bus();String id=bus.activeGroup("snapshot");JsonObject copy=group(bus.user("100"),id);copy.addProperty("name","fake");copy.getAsJsonArray("members").remove(0);assertEquals("snapshot",group(bus.user("100"),id).get("name").getAsString());assertEquals(3,group(bus.user("100"),id).getAsJsonArray("members").size());assertFalse(copy.has("token"));assertFalse(bus.user("100").snapshot().toString().contains("\"token\""));}
    @Test public void invalidCreationDoesNotInstallPartialGroup(){Bus bus=new Bus();rejects(()->bus.user("100").create("few",Arrays.asList("100","101","101")));rejects(()->bus.user("100").create("many",Arrays.asList("101","102","103","104","105","106","107","108")));rejects(()->bus.user("100").create("bad",Arrays.asList("101","bad@host")));assertEquals(0,bus.user("100").snapshot().size());}
    @Test public void unknownGroupAndMalformedBodiesHaveExplicitFailures(){Bus bus=new Bus();assertEquals(400,bus.user("100").receive("101","{}"));String id=bus.activeGroup("known");bus.user("100").send(id,"known");bus.pump();GroupMessage m=bus.lastText().envelope();m.groupId=java.util.UUID.randomUUID().toString();assertEquals(404,bus.user("101").receive("100",GroupProtocol.encode(m)));}
    @Test public void failedRecipientWhoLeavesIsNeverRetried(){Bus bus=new Bus();String id=bus.activeGroup("removed");bus.offline="102";String messageId=bus.user("100").send(id,"before leave");bus.pump();bus.offline=null;bus.user("102").leave(id);bus.pump();assertEquals("REMOVED",delivery(bus.user("100"),id,messageId,"102"));bus.sent.clear();bus.user("100").retry(id,messageId);bus.pump();assertFalse(bus.sent.stream().anyMatch(p->p.envelope().type.equals("TEXT")));}
    @Test public void historyLimitPreservesDedupAndNeverSilentlyEvicts(){Bus bus=new Bus();String id=bus.activeGroup("bounded");Packet first=null;for(int i=0;i<GroupService.MAX_MESSAGES;i++){bus.user("100").send(id,"message "+i);bus.pump();if(i==0)first=bus.lastText();}rejects(()->bus.user("100").send(id,"overflow"));assertEquals(200,bus.user(first.to).receive(first.from,first.body));bus.pump();assertEquals(GroupService.MAX_MESSAGES,history(bus.user("101"),id).size());}
    @Test @SuppressWarnings("unchecked") public void revisionLimitDoesNotPartiallyCloseOwner() throws Exception {Bus bus=new Bus();String id=bus.activeGroup("revision limit");Field field=GroupService.class.getDeclaredField("groups");field.setAccessible(true);Group internal=((Map<String,Group>)field.get(bus.user("100"))).get(id);internal.revision=1000000000;rejects(()->bus.user("100").leave(id));assertFalse(internal.closed);assertFalse(internal.localLeft);assertEquals("ACTIVE",internal.members.get("100"));}
    @SuppressWarnings("unchecked") private static Group internal(GroupService service, String id) throws Exception {
        Field field=GroupService.class.getDeclaredField("groups");field.setAccessible(true);return ((Map<String,Group>)field.get(service)).get(id);
    }
    private static void fillOperations(Group group,int count){
        while(group.operations.size()<count){GroupMessage m=new GroupMessage();m.type="SYNC";group.operations.put(java.util.UUID.randomUUID().toString(),new Group.Operation(m));}
    }
    @Test public void ownerLeaveCapacityIsCheckedBeforeAnyStateChange() throws Exception {
        Bus bus=new Bus();String id=bus.activeGroup("atomic close");Group owner=internal(bus.user("100"),id);fillOperations(owner,GroupService.MAX_OPERATIONS-1);bus.sent.clear();
        rejects(()->bus.user("100").leave(id));assertFalse(owner.closed);assertFalse(owner.localLeft);assertEquals(3,owner.revision);assertEquals("ACTIVE",owner.members.get("100"));assertTrue(bus.sent.isEmpty());
        assertEquals("ACTIVE",group(bus.user("101"),id).get("localStatus").getAsString());
    }
    @Test public void exactlyEnoughBroadcastSlotsCloseAllThreeClients() throws Exception {
        Bus bus=new Bus();String id=bus.activeGroup("exact capacity");fillOperations(internal(bus.user("100"),id),GroupService.MAX_OPERATIONS-2);bus.user("100").leave(id);bus.pump();
        for(String user:Arrays.asList("100","101","102"))assertEquals("CLOSED",group(bus.user(user),id).get("localStatus").getAsString());
    }
    @Test public void memberJoinAndLeaveCapacityDoNotCommitLocalFlags() throws Exception {
        Bus bus=new Bus();String invited=bus.user("100").create("full join",Arrays.asList("101","102"));bus.pump();Group member=internal(bus.user("101"),invited);fillOperations(member,GroupService.MAX_OPERATIONS);
        rejects(()->bus.user("101").join(invited));assertFalse(member.joining);rejects(()->bus.user("101").leave(invited));assertFalse(member.localLeft);
    }
    @Test public void ownerAcceptCapacityCannotCommitPhantomMembership() throws Exception {
        Bus bus=new Bus();String id=bus.user("100").create("full owner",Arrays.asList("101","102"));bus.pump();Group owner=internal(bus.user("100"),id);fillOperations(owner,GroupService.MAX_OPERATIONS-1);bus.user("101").join(id);bus.pump();
        assertEquals(1,owner.revision);assertEquals("INVITED",owner.members.get("101"));assertFalse(group(bus.user("101"),id).get("canSend").getAsBoolean());
    }
    @Test public void ownerSyncRecoversAnInitiallyUndeliveredInvitation(){
        Bus bus=new Bus();bus.offline="102";String id=bus.user("100").create("late invite",Arrays.asList("101","102"));bus.pump();assertEquals(0,bus.user("102").snapshot().size());bus.user("101").join(id);bus.pump();bus.offline=null;bus.user("100").sync(id);bus.pump();assertEquals("INVITED",group(bus.user("102"),id).get("localStatus").getAsString());bus.user("102").join(id);bus.pump();assertTrue(group(bus.user("102"),id).get("canSend").getAsBoolean());
    }
    @Test public void rosterUpdateMustComeFromOwnerAndCannotReviveLeftMember(){
        Bus bus=new Bus();String id=bus.activeGroup("authority");bus.user("102").leave(id);bus.pump();Packet state=bus.sent.stream().filter(p->p.to.equals("101")&&p.envelope().type.equals("STATE")&&p.envelope().revision==4).findFirst().get();GroupMessage update=state.envelope();update.messageId=java.util.UUID.randomUUID().toString();update.revision=5;update.senderId="101";assertEquals(403,bus.user("102").receive("101",GroupProtocol.encode(update)));update.senderId="100";update.members.put("102","ACTIVE");assertEquals(409,bus.user("101").receive("100",GroupProtocol.encode(update)));assertEquals(4,group(bus.user("101"),id).get("revision").getAsInt());
    }
    @Test public void controlStatusShowsEveryRecipientIncludingAckTimeout(){
        Bus bus=new Bus();String id=bus.activeGroup("visible controls");bus.dropAckFrom="101";bus.user("100").sync(id);bus.pump();bus.clock.addAndGet(GroupService.ACK_TIMEOUT_MS);String status=group(bus.user("100"),id).get("controlStatus").getAsString();assertTrue(status.contains("101=TIMEOUT"));assertTrue(status.contains("102=DELIVERED"));
    }
    @Test public void concurrentExportsHaveUniqueIncreasingSequenceWithinEpoch() throws Exception {
        Bus bus=new Bus();bus.activeGroup("export order");java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {List<java.util.concurrent.Future<JsonObject>> futures=new ArrayList<>();for(int i=0;i<100;i++)futures.add(pool.submit(()->bus.user("100").snapshotEnvelope()));java.util.Set<Long> seq=new java.util.HashSet<>();java.util.Set<String> epochs=new java.util.HashSet<>();for(java.util.concurrent.Future<JsonObject> f:futures){JsonObject result=f.get();seq.add(result.get("snapshotSequence").getAsLong());epochs.add(result.get("snapshotEpoch").getAsString());}assertEquals(100,seq.size());assertEquals(1,epochs.size());assertEquals(1L,(long)java.util.Collections.min(seq));assertEquals(100L,(long)java.util.Collections.max(seq));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
