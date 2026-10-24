package org.example;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class FileMessageSenderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path source(byte[] bytes) throws Exception {
        Path path = temp.newFile().toPath(); Files.write(path, bytes); return path;
    }
    @Test public void legacyDefaultChunksReassembleExactlyAndPreserveDisplayName() throws Exception {
        byte[] data = new byte[2401]; new java.util.Random(7).nextBytes(data);
        List<String> frames = new ArrayList<>();
        FileMessageSender.SentFile sent = FileMessageSender.send(source(data), "中文 100% &#+.png", "IMG", 800, "101",
                (to, frame) -> { assertEquals("101", to); frames.add(frame); });
        StringBuilder encoded = new StringBuilder();
        for (int i=0;i<frames.size();i++) {
            String[] p = frames.get(i).split("\\|",7);
            assertEquals("#FILE",p[0]); assertEquals("IMG",p[1]); assertEquals(sent.getId(),p[2]);
            assertEquals("中文 100% &#+.png",new String(Base64.getDecoder().decode(p[3]),StandardCharsets.UTF_8));
            assertEquals(i,Integer.parseInt(p[4])); assertEquals(frames.size(),Integer.parseInt(p[5]));
            assertTrue(p[6].length()<=800); encoded.append(p[6]);
        }
        assertArrayEquals(data,Base64.getDecoder().decode(encoded.toString()));
        assertEquals(data.length,sent.getSize()); assertEquals(frames.size(),sent.getChunks());
    }
    @Test public void emptyFileHasOneValidEmptyFrame() throws Exception {
        List<String> frames=new ArrayList<>();
        FileMessageSender.SentFile result=FileMessageSender.send(source(new byte[0]),"empty.wav","VOICE",800,"101",(t,b)->frames.add(b));
        assertEquals(1,result.getChunks());assertEquals(0,result.getSize());
        String[] p=frames.get(0).split("\\|",7);assertEquals("0",p[4]);assertEquals("1",p[5]);assertEquals("",p[6]);
    }
    @Test public void nonMultipleOfFourChunkBoundariesRemainLegacyCompatible() throws Exception {
        byte[] data={0,1,2,3,4,5,6};List<String> frames=new ArrayList<>();
        FileMessageSender.send(source(data),"clip.mp4","VIDEO",3,"102",(t,b)->frames.add(b));
        StringBuilder encoded=new StringBuilder();for(String frame:frames)encoded.append(frame.split("\\|",7)[6]);
        assertArrayEquals(data,Base64.getDecoder().decode(encoded.toString()));
        assertEquals(4,frames.size());
    }
    @Test public void receiverSnapshotSurvivesContactSwitch() throws Exception {
        AtomicReference<String> current=new AtomicReference<>("101");List<String> recipients=new ArrayList<>();
        FileMessageSender.send(source(new byte[2000]),"image.png","IMG",800,current.get(),
                (to,frame)->{recipients.add(to);current.set("102");});
        assertTrue(recipients.size()>1);for(String recipient:recipients)assertEquals("101",recipient);
    }
    @Test public void eachTransferUsesUniqueProtocolId() throws Exception {
        Path file=source(new byte[]{1});
        FileMessageSender.SentFile a=FileMessageSender.send(file,"a.png","IMG",800,"101",(t,b)->{});
        FileMessageSender.SentFile b=FileMessageSender.send(file,"a.png","IMG",800,"101",(t,x)->{});
        assertNotEquals(a.getId(),b.getId());
    }
    @Test public void exactMaximumChunkSizeWorks() throws Exception {
        List<String> frames=new ArrayList<>();FileMessageSender.send(source(new byte[4000]),"a.png","IMG",4096,"101",(t,b)->frames.add(b));
        assertEquals(4096,frames.get(0).split("\\|",7)[6].length());assertEquals(2,frames.size());
    }
    @Test public void invalidNamesAndTypesProduceNoFrame() throws Exception {
        Path file=source(new byte[1]);int[] sends={0};
        for(String name:new String[]{"../a.png","..\\a.png","C:\\a.png","CON.png"}) {
            try{FileMessageSender.send(file,name,"IMG",800,"101",(t,b)->sends[0]++);fail();}catch(IllegalArgumentException expected){}
        }
        try{FileMessageSender.send(file,"a.png","BAD",800,"101",(t,b)->sends[0]++);fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,sends[0]);
    }
    @Test public void invalidChunkSettingsProduceNoFrame() throws Exception {
        Path file=source(new byte[1]);
        for(int size:new int[]{0,-1,4097})try{FileMessageSender.send(file,"a.png","IMG",size,"101",(t,b)->fail());fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void missingFileFailsRatherThanReportingSuccess() throws Exception {
        try{FileMessageSender.send(temp.getRoot().toPath().resolve("missing"),"a.png","IMG",800,"101",(t,b)->fail());fail();}
        catch(java.io.IOException expected){}
    }
    @Test public void tooManyTinyChunksRejectedBeforeTransport() throws Exception {
        try{FileMessageSender.send(source(new byte[30000]),"a.png","IMG",1,"101",(t,b)->fail());fail();}
        catch(IllegalArgumentException expected){assertEquals("TOO_MANY_FILE_CHUNKS",expected.getMessage());}
    }
    @Test public void oversizedFileRejectedBeforeTransport() throws Exception {
        Path path=temp.newFile().toPath();
        try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(path.toFile(),"rw")){file.setLength(ReceivedFileStore.MAX_FILE_BYTES+1);}
        try{FileMessageSender.send(path,"a.mp4","VIDEO",800,"101",(t,b)->fail());fail();}
        catch(ReceivedFileStore.SizeLimitException expected){}
    }
    @Test public void transportFailureDoesNotComplete() throws Exception {
        try{FileMessageSender.send(source(new byte[2000]),"a.png","IMG",800,"101",(t,b)->{throw new java.io.IOException("fixture");});fail();}
        catch(java.io.IOException expected){assertEquals("FILE_FRAME_SEND_FAILED",expected.getMessage());}
    }
    @Test public void sourceMutationWhileSendingFails() throws Exception {
        Path file=source(new byte[2000]);int[] sent={0};
        try{FileMessageSender.send(file,"a.png","IMG",800,"101",(t,b)->{if(sent[0]++==0)Files.write(file,new byte[]{3});});fail();}
        catch(java.io.IOException expected){assertEquals("FILE_CHANGED_DURING_SEND",expected.getMessage());}
    }
    @Test public void sourceShrinkNeverPublishesReceiverFinalFile() throws Exception {
        Path file=source(new byte[1200]);
        ReceivedFileStore received=new ReceivedFileStore(temp.getRoot().toPath().resolve("receive-shrink"));
        FileTransferReceiver receiver=new FileTransferReceiver(received);
        List<FileTransferReceiver.Result> results=new ArrayList<>();
        try {
            FileMessageSender.send(file,"a.png","IMG",800,"101",(to,body)->{
                results.add(receiver.accept("100",body));Files.write(file,new byte[]{3});
            });
            fail("Source shrink must fail");
        } catch(java.io.IOException expected){assertEquals("FILE_CHANGED_DURING_SEND",expected.getMessage());}
        assertEquals(1,results.size());assertFalse(results.get(0).isComplete());
        assertFalse(Files.exists(received.getRoot()));receiver.close();
    }
    @Test public void sourceChangeAfterLastReadNeverPublishesReceiverFinalFile() throws Exception {
        Path file=source(new byte[1200]);
        java.nio.file.attribute.FileTime original=Files.getLastModifiedTime(file);
        ReceivedFileStore received=new ReceivedFileStore(temp.getRoot().toPath().resolve("receive-change"));
        FileTransferReceiver receiver=new FileTransferReceiver(received);
        List<FileTransferReceiver.Result> results=new ArrayList<>();
        try {
            FileMessageSender.send(file,"a.png","IMG",800,"101",(to,body)->{
                results.add(receiver.accept("100",body));
                Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.fromMillis(original.toMillis()+10000));
            });
            fail("Source metadata change must fail");
        } catch(java.io.IOException expected){assertEquals("FILE_CHANGED_DURING_SEND",expected.getMessage());}
        assertEquals(1,results.size());assertFalse(results.get(0).isComplete());
        assertFalse(Files.exists(received.getRoot()));receiver.close();
    }
    @Test public void maximumFileCompletesWithinDefaultReceiveBudgets() throws Exception {
        Path file=temp.newFile().toPath();
        try(java.io.RandomAccessFile handle=new java.io.RandomAccessFile(file.toFile(),"rw")){handle.setLength(ReceivedFileStore.MAX_FILE_BYTES);}
        ReceivedFileStore received=new ReceivedFileStore(temp.getRoot().toPath().resolve("maximum-file"));
        FileTransferReceiver receiver=new FileTransferReceiver(received);
        java.util.concurrent.atomic.AtomicReference<FileTransferReceiver.Result> last=new java.util.concurrent.atomic.AtomicReference<>();
        FileMessageSender.SentFile sent=FileMessageSender.send(file,"maximum.mp4","VIDEO",800,"101",(to,body)->{
            FileTransferReceiver.Result result=receiver.accept("100",body);assertEquals(200,result.getSipStatus());last.set(result);
        });
        assertEquals(ReceivedFileStore.MAX_FILE_BYTES,sent.getSize());assertTrue(last.get().isComplete());
        assertEquals(ReceivedFileStore.MAX_FILE_BYTES,last.get().getStoredFile().getSize());
        java.security.MessageDigest expected=java.security.MessageDigest.getInstance("SHA-256");byte[] zeros=new byte[8192];
        for(long left=ReceivedFileStore.MAX_FILE_BYTES;left>0;left-=zeros.length)expected.update(zeros,0,(int)Math.min(left,zeros.length));
        StringBuilder hash=new StringBuilder();for(byte b:expected.digest())hash.append(String.format("%02x",b&255));
        assertEquals(hash.toString(),last.get().getStoredFile().getSha256());assertEquals(0,receiver.activeTransferCount());receiver.close();
    }
}
