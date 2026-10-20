package org.example;

import org.junit.Test;
import javax.sound.sampled.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Device-free lifecycle tests. Real sockets, where used, bind only ephemeral loopback ports. */
public class MediaResourceCleanupTest {
    @Test public void receiverOpenFailureClosesSocketAndPartialSpeaker() throws Exception {
        FakeLine line = new FakeLine();
        line.failOpen = true;
        assertReceiverFailureCleans(line);
    }

    @Test public void receiverStartFailureClosesSocketAndSpeaker() throws Exception {
        FakeLine line = new FakeLine();
        line.failStart = true;
        assertReceiverFailureCleans(line);
    }

    @Test public void receiverDeviceLookupFailureClosesSocket() throws Exception {
        AtomicReference<DatagramSocket> allocated = new AtomicReference<>();
        try {
            new RTPAudioReceiver(0, format -> { throw new LineUnavailableException("fake unavailable"); },
                    port -> { DatagramSocket socket = loopback(); allocated.set(socket); return socket; });
            fail("Expected unavailable device");
        } catch (LineUnavailableException expected) {
            assertNotNull(allocated.get());
            assertTrue(allocated.get().isClosed());
        }
    }

    @Test public void captureOpenFailureClosesPartialMicrophone() throws Exception {
        FakeLine line = new FakeLine();
        line.failOpen = true;
        AudioCapture capture = new AudioCapture(format -> line.target());
        try { capture.start((data, length) -> fail("No frame expected")); fail("Expected open failure"); }
        catch (LineUnavailableException expected) { assertTrue(line.closed); }
        capture.stop();
    }

    @Test public void captureStartFailureClosesOpenedMicrophone() throws Exception {
        FakeLine line = new FakeLine();
        line.failStart = true;
        AudioCapture capture = new AudioCapture(format -> line.target());
        try { capture.start((data, length) -> fail("No frame expected")); fail("Expected start failure"); }
        catch (IllegalStateException expected) { assertTrue(line.closed); }
    }

    @Test public void captureStopReleasesReadAndRejectsLateFrame() throws Exception {
        FakeLine line = new FakeLine();
        AtomicInteger delivered = new AtomicInteger();
        AudioCapture capture = new AudioCapture(format -> line.target());
        capture.start((data, length) -> delivered.incrementAndGet());
        assertTrue(line.readEntered.await(2, TimeUnit.SECONDS));
        capture.stop();
        assertTrue(line.closed);
        assertEquals("A read returning during close is not a new audio callback", 0, delivered.get());
        capture.stop();
        assertEquals("Resource closure must be owned once, not repeated by a late worker", 1, line.closes.get());
    }

    @Test public void captureRejectsDuplicateStartWithoutOpeningAnotherDevice() throws Exception {
        FakeLine line = new FakeLine();
        AtomicInteger factories = new AtomicInteger();
        AudioCapture capture = new AudioCapture(format -> { factories.incrementAndGet(); return line.target(); });
        capture.start((data, length) -> { });
        try {
            try { capture.start((data, length) -> { }); fail("Duplicate capture must be rejected"); }
            catch (IllegalStateException expected) { assertEquals(1, factories.get()); }
        } finally { capture.stop(); }
    }

    @Test public void captureCanRestartOnlyWithItsNewRunAndListener() throws Exception {
        FakeLine first = new FakeLine();
        FakeLine second = new FakeLine();
        Queue<FakeLine> devices = new ArrayDeque<>(Arrays.asList(first, second));
        AudioCapture capture = new AudioCapture(format -> devices.remove().target());
        AtomicInteger firstCallbacks = new AtomicInteger();
        AtomicInteger secondCallbacks = new AtomicInteger();
        capture.start((data, length) -> firstCallbacks.incrementAndGet());
        assertTrue(first.readEntered.await(2, TimeUnit.SECONDS));
        capture.stop();
        capture.start((data, length) -> secondCallbacks.incrementAndGet());
        assertTrue(second.readEntered.await(2, TimeUnit.SECONDS));
        assertTrue(first.closed);
        assertFalse("Old worker cannot close or read a newly opened device", second.closed);
        capture.stop();
        assertEquals(0, firstCallbacks.get());
        assertEquals(0, secondCallbacks.get());
    }

    @Test public void captureMayStopFromItsOwnCallback() throws Exception {
        FakeLine line = new FakeLine();
        line.immediateFirstRead = true;
        AtomicReference<AudioCapture> reference = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AudioCapture capture = new AudioCapture(format -> line.target());
        reference.set(capture);
        capture.start((data, length) -> {
            assertEquals(4, length);
            reference.get().stop();
            done.countDown();
        });
        assertTrue("stop must not join itself", done.await(2, TimeUnit.SECONDS));
        assertTrue(line.closed);
    }

    @Test public void receiverStopReleasesBlockedReceiveAndPortWithoutDrain() throws Exception {
        FakeLine line = new FakeLine();
        RTPAudioReceiver receiver = receiver(line);
        int port = receiver.getSocket().getLocalPort();
        receiver.start();
        receiver.stop();
        receiver.stop();
        assertTrue(receiver.getSocket().isClosed());
        assertTrue(line.closed);
        assertEquals(0, line.drains.get());
        assertEquals("Stop and receive-finally must not double-close the audio device", 1, line.closes.get());
        try (DatagramSocket rebound = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
            assertEquals(port, rebound.getLocalPort());
        }
        try { receiver.start(); fail("A closed receiver cannot restart"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void duplicateReceiverStartKeepsTheSameWorker() throws Exception {
        FakeLine line = new FakeLine();
        RTPAudioReceiver receiver = receiver(line);
        try {
            receiver.start();
            Object first = field(receiver, "worker");
            receiver.start();
            assertSame(first, field(receiver, "worker"));
        } finally { receiver.stop(); }
    }

    @Test public void receiverStopUnblocksPlaybackAndNeverDrains() throws Exception {
        FakeLine line = new FakeLine();
        RTPAudioReceiver receiver = receiver(line);
        try {
            receiver.start();
            send(receiver.getSocket().getLocalPort(), new byte[] {
                    (byte) 0x80, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, (byte) 0xff });
            assertTrue(line.writeEntered.await(2, TimeUnit.SECONDS));
            receiver.stop();
            assertTrue(line.closed);
            assertEquals(0, line.drains.get());
        } finally { receiver.stop(); }
    }

    @Test public void speakerStopExceptionStillClosesSocketAndDevice() throws Exception {
        FakeLine line = new FakeLine();
        line.failStop = true;
        RTPAudioReceiver receiver = receiver(line);
        receiver.start();
        receiver.stop();
        assertTrue(receiver.getSocket().isClosed());
        assertTrue(line.closed);
    }

    @Test public void videoStopReleasesBlockedReceiveAndPort() throws Exception {
        DatagramSocket socket = loopback();
        int port = socket.getLocalPort();
        RtpVideoReceiver receiver = new RtpVideoReceiver(socket, frame -> fail("No video expected"));
        receiver.start();
        Object first = field(receiver, "thread");
        receiver.start();
        assertSame(first, field(receiver, "thread"));
        receiver.stop();
        assertTrue(socket.isClosed());
        assertFalse(((Thread) first).isAlive());
        try (DatagramSocket rebound = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
            assertEquals(port, rebound.getLocalPort());
        }
        try { receiver.start(); fail("Video receiver cannot restart its closed socket"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void videoCanStopFromItsOwnCallback() throws Exception {
        DatagramSocket socket = loopback();
        AtomicReference<RtpVideoReceiver> reference = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        RtpVideoReceiver receiver = new RtpVideoReceiver(socket, frame -> {
            assertArrayEquals(new byte[] { 1, 2 }, frame);
            reference.get().stop();
            completed.countDown();
        });
        reference.set(receiver);
        try {
            receiver.start();
            send(socket.getLocalPort(), new byte[] {
                    (byte) 0x80, (byte) 0x80, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 2 });
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(socket.isClosed());
        } finally { receiver.stop(); }
    }

    @Test public void conferenceSecondSocketFailureRollsBackFirstBeforeRunning() throws Exception {
        FakeSocket first = new FakeSocket();
        AtomicInteger sockets = new AtomicInteger();
        AtomicBoolean cameraCreated = new AtomicBoolean();
        ConferenceVideo video = conference(new ConferenceVideo.ResourceFactory() {
            public DatagramSocket socket() throws Exception {
                if (sockets.incrementAndGet() == 2) throw new SocketException("fake allocation failure");
                return first;
            }
            public VideoCapture capture(int width, int height) {
                cameraCreated.set(true);
                throw new AssertionError("No camera should be touched");
            }
        });
        try { video.start(); fail("Expected second socket failure"); }
        catch (SocketException expected) {
            assertTrue(first.isClosed());
            assertFalse(cameraCreated.get());
            assertEquals(0, first.sent.get());
        }
        video.stop();
    }

    @Test public void conferenceCameraPartialFailureIsClosedBeforeReceiveOnlyMode() throws Exception {
        FakeCapture camera = new FakeCapture(true);
        FakeConferenceResources resources = new FakeConferenceResources(camera);
        ConferenceVideo video = conference(resources);
        try {
            video.start();
            assertEquals(1, camera.stops.get());
            assertEquals("Camera failure must not silently omit the partial resource close", 1, camera.starts.get());
            assertTrue(resources.sockets.get(0).receiving.await(2, TimeUnit.SECONDS));
        } finally { video.stop(); }
        assertTrue(resources.sockets.get(0).isClosed());
        assertTrue(resources.sockets.get(1).isClosed());
    }

    @Test public void conferenceStopClosesCameraSocketsAndWorkersAndRejectsRestart() throws Exception {
        FakeCapture camera = new FakeCapture(false);
        FakeConferenceResources resources = new FakeConferenceResources(camera);
        ConferenceVideo video = conference(resources);
        video.start();
        assertTrue(resources.sockets.get(0).receiving.await(2, TimeUnit.SECONDS));
        video.stop();
        video.stop();
        assertEquals(1, camera.stops.get());
        assertEquals(2, resources.sockets.size());
        for (FakeSocket socket : resources.sockets) assertTrue(socket.isClosed());
        assertFalse(((Thread) field(video, "receiveThread")).isAlive());
        assertFalse(((Thread) field(video, "sendThread")).isAlive());
        assertFalse(((Thread) field(video, "heartbeatThread")).isAlive());
        try { video.start(); fail("A conference instance cannot adopt a later call"); }
        catch (IllegalStateException expected) { assertEquals(2, resources.sockets.size()); }
    }

    private static ConferenceVideo conference(ConferenceVideo.ResourceFactory resources) {
        return new ConferenceVideo("127.0.0.1", 9101, "*6*1", "102", 320, 240, 5, 0.7f, null, resources);
    }

    private static final class FakeCapture extends VideoCapture {
        final boolean failStart;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        FakeCapture(boolean failStart) { super(320, 240); this.failStart = failStart; }
        @Override public void start() {
            starts.incrementAndGet();
            if (failStart) throw new IllegalStateException("fake partial camera open failure");
        }
        @Override public BufferedImage grab() { return null; }
        @Override public void stop() { stops.incrementAndGet(); }
    }

    private static final class FakeConferenceResources implements ConferenceVideo.ResourceFactory {
        final FakeCapture camera;
        final List<FakeSocket> sockets = new ArrayList<>();
        FakeConferenceResources(FakeCapture camera) { this.camera = camera; }
        public DatagramSocket socket() throws Exception {
            FakeSocket socket = new FakeSocket();
            sockets.add(socket);
            return socket;
        }
        public VideoCapture capture(int width, int height) { return camera; }
    }

    /** An unbound fake socket: no relay/port is started or contacted by conference tests. */
    private static final class FakeSocket extends DatagramSocket {
        final CountDownLatch receiving = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger sent = new AtomicInteger();
        FakeSocket() throws SocketException { super((SocketAddress) null); }
        @Override public void receive(DatagramPacket packet) throws java.io.IOException {
            receiving.countDown();
            await(closed);
            throw new SocketException("closed fake socket");
        }
        @Override public void send(DatagramPacket packet) throws java.io.IOException {
            if (isClosed()) throw new SocketException("closed fake socket");
            sent.incrementAndGet();
        }
        @Override public int getLocalPort() { return 10000; }
        @Override public void close() { super.close(); closed.countDown(); }
    }

    private static RTPAudioReceiver receiver(FakeLine line) throws Exception {
        return new RTPAudioReceiver(0, format -> line.source(), port -> loopback());
    }

    private static void assertReceiverFailureCleans(FakeLine line) throws Exception {
        AtomicReference<DatagramSocket> socket = new AtomicReference<>();
        try {
            new RTPAudioReceiver(0, format -> line.source(), port -> {
                DatagramSocket created = loopback();
                socket.set(created);
                return created;
            });
            fail("Expected playback initialization failure");
        } catch (LineUnavailableException | IllegalStateException expected) {
            assertNotNull(socket.get());
            assertTrue(socket.get().isClosed());
            assertTrue(line.closed);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static DatagramSocket loopback() throws SocketException {
        return new DatagramSocket(0, InetAddress.getLoopbackAddress());
    }

    private static void send(int port, byte[] bytes) throws Exception {
        try (DatagramSocket sender = loopback()) {
            sender.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fake I/O was not released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static final class FakeLine implements InvocationHandler {
        volatile boolean closed;
        volatile boolean failOpen;
        volatile boolean failStart;
        volatile boolean failStop;
        volatile boolean immediateFirstRead;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger drains = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        TargetDataLine target() { return proxy(TargetDataLine.class); }
        SourceDataLine source() { return proxy(SourceDataLine.class); }
        @SuppressWarnings("unchecked")
        private <T> T proxy(Class<T> type) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, this);
        }

        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            switch (method.getName()) {
                case "open":
                    if (failOpen) throw new LineUnavailableException("fake partial open failure");
                    return null;
                case "start":
                    if (failStart) throw new IllegalStateException("fake start failure");
                    return null;
                case "stop":
                    if (failStop) throw new IllegalStateException("fake stop failure");
                    return null;
                case "flush": return null;
                case "close": closes.incrementAndGet(); closed = true; release.countDown(); return null;
                case "drain": drains.incrementAndGet(); throw new AssertionError("Shutdown must not drain");
                case "read":
                    readEntered.countDown();
                    if (!immediateFirstRead || reads.getAndIncrement() > 0) await(release);
                    byte[] bytes = (byte[]) arguments[0];
                    Arrays.fill(bytes, (byte) 0);
                    return 4; // Simulates a device returning a last frame during close.
                case "write":
                    writeEntered.countDown();
                    await(release);
                    return (Integer) arguments[2];
                case "isOpen": return !closed;
                case "toString": return "Device-free audio line";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == arguments[0];
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
            }
        }
    }
}
