package org.example;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real JAIN message factories plus proxy transactions: no Stack, sockets, devices, or HTTP server. */
public class FileReceiveIntegrationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private ReceivedFileStore store;
    private SipVideoCallClient client;
    private MessageFactory messages;
    private HeaderFactory headers;
    private AddressFactory addresses;
    private List<Request> sent;
    private RecordingUi ui;
    private boolean switchContact;

    @Before public void setup() throws Exception {
        SipFactory f=SipFactory.getInstance();f.setPathName("gov.nist");
        messages=f.createMessageFactory();headers=f.createHeaderFactory();addresses=f.createAddressFactory();
        store=new ReceivedFileStore(temp.getRoot().toPath().resolve("received"));
        client=new SipVideoCallClient(store);ui=new RecordingUi();client.setUi(ui);sent=new ArrayList<>();
        set("username","100");set("serverIp","example.invalid");set("localIp","127.0.0.1");
        set("messageFactory",messages);set("headerFactory",headers);
        set("addressFactory",proxy(AddressFactory.class,(p,m,a)->{
            if(switchContact && "createAddress".equals(m.getName()) && a!=null && a.length==1
                    && "sip:101@example.invalid".equals(a[0]))client.setTargetUser("102");
            try{return m.invoke(addresses,a);}catch(InvocationTargetException e){throw e.getCause();}
        }));
        set("sipProvider",proxy(SipProvider.class,(p,m,a)->{
            if("getNewCallId".equals(m.getName()))return headers.createCallIdHeader("offline-file-"+sent.size());
            if("getNewClientTransaction".equals(m.getName())){
                Request request=(Request)a[0];sent.add(request);
                return proxy(ClientTransaction.class,(tx,method,args)->{
                    if("getRequest".equals(method.getName()))return request;
                    return defaultValue(method.getReturnType());
                });
            }
            return defaultValue(m.getReturnType());
        }));
    }
    private void set(String name,Object value)throws Exception{Field f=SipVideoCallClient.class.getDeclaredField(name);f.setAccessible(true);f.set(client,value);}
    private String frame(String id,String name,String data){return "#FILE|IMG|"+id+"|"+Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8))+"|0|1|"+data;}
    private int receive(String peer,byte[] body)throws Exception{
        Request r=messages.createRequest("MESSAGE sip:100@example.invalid SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:15061;branch=z9hG4bKtest\r\nFrom: <sip:"+peer+"@example.invalid>;tag=test\r\nTo: <sip:100@example.invalid>\r\nCall-ID: offline-receive\r\nCSeq: 1 MESSAGE\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n");
        r.setContent(body,headers.createContentTypeHeader("text","plain"));
        List<Integer> codes=new ArrayList<>();
        ServerTransaction tx=proxy(ServerTransaction.class,(p,m,a)->{
            if("sendResponse".equals(m.getName()))codes.add(((Response)a[0]).getStatusCode());
            return defaultValue(m.getReturnType());
        });
        client.processRequest(new RequestEvent(this,tx,null,r));
        assertEquals("Exactly one SIP response",1,codes.size());return codes.get(0);
    }
    private int receive(String peer,String body)throws Exception{return receive(peer,body.getBytes(StandardCharsets.UTF_8));}

    @Test public void ordinaryImageGetsSafePathAndOriginalDisplay()throws Exception{
        assertEquals(200,receive("101",frame("a","中文.png","AQID")));
        assertEquals(1,ui.files);assertEquals("中文.png",ui.name);
        assertNotEquals(ui.name,Paths.get(ui.path).getFileName().toString());
        assertEquals(store.getRoot(),Paths.get(ui.path).getParent());
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(Paths.get(ui.path)));
    }
    @Test public void traversalGetsFailureNotSuccessOrAttachment()throws Exception{
        assertEquals(400,receive("101",frame("a","../escape.png","AQID")));
        assertEquals(0,ui.files);assertEquals(1,ui.errors);
        assertFalse(Files.exists(temp.getRoot().toPath().resolve("escape.png")));
    }
    @Test public void incompleteTransferDoesNotPublishFile()throws Exception{
        String name=Base64.getEncoder().encodeToString("a.png".getBytes(StandardCharsets.UTF_8));
        assertEquals(200,receive("101","#FILE|IMG|a|"+name+"|0|2|AQID"));
        assertEquals(0,ui.files);assertFalse(Files.exists(store.getRoot()));
    }
    @Test public void completedDuplicateDoesNotRepublishAttachment()throws Exception{
        String b=frame("a","a.png","AQID");assertEquals(200,receive("101",b));assertEquals(200,receive("101",b));assertEquals(1,ui.files);
    }
    @Test public void completedConflictingChunkFails()throws Exception{
        assertEquals(200,receive("101",frame("a","a.png","AQID")));
        assertEquals(409,receive("101",frame("a","a.png","BAUG")));assertEquals(1,ui.files);
    }
    @Test public void differentSendersSameIdAndNameStaySeparate()throws Exception{
        assertEquals(200,receive("101",frame("a","same.png","AQID")));String first=ui.path;
        assertEquals(200,receive("102",frame("a","same.png","BAUG")));
        assertNotEquals(first,ui.path);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(Paths.get(first)));
    }
    @Test public void oversizedRawFileFrameReturns413()throws Exception{
        byte[] body=new byte[9000];Arrays.fill(body,(byte)'A');System.arraycopy("#FILE|".getBytes(StandardCharsets.US_ASCII),0,body,0,6);
        assertEquals(413,receive("101",body));assertEquals(0,ui.files);
    }
    @Test public void invalidUtf8DoesNotBecomeReplacementText()throws Exception{
        byte[] prefix="#FILE|IMG|a|".getBytes(StandardCharsets.US_ASCII);byte[] raw=Arrays.copyOf(prefix,prefix.length+2);
        raw[prefix.length]=(byte)0xc3;raw[prefix.length+1]=0x28;
        assertEquals(400,receive("101",raw));assertEquals(0,ui.messages);assertEquals(0,ui.files);
    }
    @Test public void invalidBase64Returns400()throws Exception{
        assertEquals(400,receive("101",frame("a","a.png","!!!!")));assertEquals(0,ui.files);
    }
    @Test public void reservedWindowsNameReturns400()throws Exception{
        assertEquals(400,receive("101",frame("a","CON.png","AQID")));assertEquals(0,ui.files);
    }
    @Test public void emptyFilePublishesExactlyOnce()throws Exception{
        assertEquals(200,receive("101",frame("a","empty.png","")));assertEquals(1,ui.files);assertEquals(0,Files.size(Paths.get(ui.path)));
    }
    @Test public void ordinaryPrivateTextStillUsesOriginalUiPath()throws Exception{
        assertEquals(200,receive("101","private 中文 #FILE text"));assertEquals(1,ui.messages);assertEquals(0,ui.files);
    }
    @Test public void failedUiCallbackCannotHideSipFailure()throws Exception{
        client.setUi(new RecordingUi(){@Override public void onFileError(String p,String e){throw new IllegalStateException("fixture");}});
        assertEquals(400,receive("101",frame("a","../a.png","AQID")));
    }
    @Test public void videoArrivalDoesNotLaunchExternalProgram()throws Exception{
        String b=frame("a","clip.mp4","AQID").replace("#FILE|IMG|","#FILE|VIDEO|");
        assertEquals(200,receive("101",b));assertEquals(1,ui.files);assertTrue(Files.exists(Paths.get(ui.path)));
        // No Desktop/media invocation exists on this receive path: only the event and stored file.
    }
    @Test public void wholeSendStaysOnCapturedRecipientAndOriginalName()throws Exception{
        Path path=temp.newFile().toPath();Files.write(path,new byte[1800]);client.setTargetUser("101");switchContact=true;
        client.sendFile(path.toFile(),"IMG","original 中文.png");
        assertTrue(sent.size()>1);
        for(Request r:sent){
            assertEquals("101",((SipURI)r.getRequestURI()).getUser());
            ContentTypeHeader mime=(ContentTypeHeader)r.getHeader(ContentTypeHeader.NAME);
            assertEquals("text",mime.getContentType());assertEquals("plain",mime.getContentSubType());
            String[] parts=new String(r.getRawContent(),StandardCharsets.UTF_8).split("\\|",7);
            assertEquals("original 中文.png",new String(Base64.getDecoder().decode(parts[3]),StandardCharsets.UTF_8));
        }
        assertEquals("101",ui.peer);assertEquals("102",client.getTargetUser());
    }
    @Test public void threeMediaKindsKeepLegacyWireAndSha256Content()throws Exception{
        for(String[] kind:new String[][]{{"IMG","图片.png"},{"VOICE","voice.wav"},{"VIDEO","clip.mp4"}}){
            byte[] bytes=new byte[1701];new Random(42).nextBytes(bytes);Path path=temp.newFile().toPath();Files.write(path,bytes);
            FileTransferReceiver receiver=new FileTransferReceiver(store);List<FileTransferReceiver.Result> results=new ArrayList<>();
            FileMessageSender.send(path,kind[1],kind[0],799,"101",(to,body)->results.add(receiver.accept("100",body)));
            for(FileTransferReceiver.Result r:results)assertEquals(200,r.getSipStatus());
            FileTransferReceiver.Result finalResult=results.get(results.size()-1);
            assertTrue(finalResult.isComplete());assertArrayEquals(bytes,Files.readAllBytes(finalResult.getStoredFile().getPath()));
            assertEquals(hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),finalResult.getStoredFile().getSha256());
            receiver.close();
        }
    }
    @Test public void attachmentEventUsesSafeStorageUrlButOriginalName()throws Exception{
        ReceivedFileStore.StoredFile saved=store.save("中文 100% &#+.png",new java.io.ByteArrayInputStream(new byte[]{1}));
        com.google.gson.JsonObject event=WebClientServer.attachmentEvent(store,"101","IMG",saved.getOriginalName(),saved.getPath().toString(),false);
        assertEquals(saved.getOriginalName(),event.get("name").getAsString());
        assertEquals(FileHttpApi.fileUrl(saved.getStorageName()),event.get("url").getAsString());
        assertFalse(event.get("url").getAsString().contains("中文"));
    }
    @Test public void attachmentEventRefusesOutsideDirectory()throws Exception{
        try{WebClientServer.attachmentEvent(store,"101","IMG","a.png",temp.newFile().toString(),false);fail();}
        catch(IllegalArgumentException expected){assertEquals("FILE_OUTSIDE_RECEIVE_ROOT",expected.getMessage());}
    }
    @Test public void receiverConstructionDoesNotCreateDirectory()throws Exception{
        assertFalse(Files.exists(store.getRoot()));assertNotNull(client.getFileStore());assertFalse(Files.exists(store.getRoot()));
    }
    private static String hex(byte[] hash){StringBuilder b=new StringBuilder();for(byte x:hash)b.append(String.format("%02x",x&255));return b.toString();}
    private static class RecordingUi implements SipVideoCallClient.Ui {
        int files,errors,messages;String name,path,peer;
        public void onMessage(String p,String text,boolean out){messages++;}
        public void onFile(String p,String type,String n,String saved,boolean out){files++;name=n;path=saved;peer=p;}
        public void onFileError(String p,String error){errors++;}
        public void onCallState(String state,String peer){}
        public void onVideoFrame(byte[] jpeg,boolean local){}
    }
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static Object defaultValue(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    @Test public void recordingLimitsRejectBeforeOpeningAudioDevice()throws Exception{
        for(int seconds:new int[]{-1,0,61})try{client.recordAndSendVoice(seconds);fail();}
        catch(IllegalArgumentException expected){}
        assertFalse(Files.exists(store.getRoot()));
    }
    @Test public void stoppingAbsentRecordingDoesNotCreateFilesOrOpenDevices()throws Exception{
        try{client.stopVoiceRecordAndSend();fail();}
        catch(Exception expected){assertEquals("当前没有在录音",expected.getMessage());}
        assertFalse(Files.exists(store.getRoot()));
    }
}
