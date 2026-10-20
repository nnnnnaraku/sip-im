package org.example;

import javax.sound.sampled.*;
import java.net.*;

/** Owns its socket and playback device for exactly one receiver lifetime. */
public class RTPAudioReceiver {
    interface SpeakerFactory {
        SourceDataLine create(AudioFormat format) throws LineUnavailableException;
    }
    interface SocketFactory {
        DatagramSocket create(int port) throws SocketException;
    }

    private final DatagramSocket socket;
    private final SourceDataLine speaker;
    private volatile boolean isRunning;
    private boolean stopped;
    private Thread worker;
    private final Object speakerCloseLock = new Object();
    private boolean speakerClosed;
    private volatile long packetsReceived;
    private volatile int recvLevel;

    public RTPAudioReceiver(int localPort) throws Exception {
        this(localPort, format -> (SourceDataLine) AudioSystem.getLine(
                new DataLine.Info(SourceDataLine.class, format)), DatagramSocket::new);
    }

    /** Device/socket factories allow rollback testing without audio hardware. */
    RTPAudioReceiver(int localPort, SpeakerFactory speakers, SocketFactory sockets) throws Exception {
        if (speakers == null || sockets == null) throw new IllegalArgumentException("Media factories required");
        DatagramSocket allocatedSocket = sockets.create(localPort);
        SourceDataLine allocatedSpeaker = null;
        try {
            allocatedSocket.setSoTimeout(5000);
            AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, false);
            allocatedSpeaker = speakers.create(format);
            allocatedSpeaker.open(format, 3200);
            allocatedSpeaker.start();
        } catch (Exception | Error failure) {
            allocatedSocket.close();
            closeSpeaker(allocatedSpeaker);
            throw failure;
        }
        socket = allocatedSocket;
        speaker = allocatedSpeaker;
        System.out.println("RTP receiver ready: UDP " + socket.getLocalPort());
    }

    /** Sender reuses the receiver-owned socket for symmetric RTP. */
    public DatagramSocket getSocket() { return socket; }
    public long getPacketsReceived() { return packetsReceived; }
    public int getRecvLevel() { return recvLevel; }

    public synchronized void start() {
        if (stopped) throw new IllegalStateException("RTP receiver has stopped");
        if (isRunning) return;
        if (worker != null) throw new IllegalStateException("RTP receiver cannot restart an old worker");
        isRunning = true;
        worker = new Thread(this::receiveLoop, "RTPReceiver");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException | Error failure) {
            isRunning = false;
            stopped = true;
            socket.close();
            closeOwnedSpeaker();
            throw failure;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[512];
        int consecutiveErrors = 0;
        try {
            while (isRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    if (!isRunning) break;
                    consecutiveErrors = 0;

                    byte[] rtpData = packet.getData();
                    int packetLength = packet.getLength();
                    if (packetLength < 12) continue;

                    packetsReceived++;
                    if (packetsReceived == 1) {
                        System.out.println("First RTP packet received: " + packetLength + " bytes");
                    }

                    int rtpHeaderLength = 12;
                    int cc = rtpData[0] & 0x0F;
                    rtpHeaderLength += cc * 4;
                    if ((rtpData[0] & 0x10) != 0) {
                        if (packetLength < rtpHeaderLength + 4) continue;
                        int extLen = ((rtpData[rtpHeaderLength + 2] & 0xFF) << 8)
                                | (rtpData[rtpHeaderLength + 3] & 0xFF);
                        rtpHeaderLength += 4 + extLen * 4;
                    }
                    if (packetLength <= rtpHeaderLength) continue;

                    // Existing baseline PCMU behavior; negotiated codec changes belong to audio P0.
                    int payloadLength = packetLength - rtpHeaderLength;
                    byte[] mulawData = new byte[payloadLength];
                    System.arraycopy(rtpData, rtpHeaderLength, mulawData, 0, payloadLength);
                    byte[] pcmData = PCMUEncoder.decode(mulawData);
                    int sum = 0;
                    int n = pcmData.length / 2;
                    for (int i = 0; i < n; i++) {
                        int sample = (short) ((pcmData[2 * i + 1] << 8) | (pcmData[2 * i] & 0xFF));
                        sum += Math.abs(sample);
                    }
                    int average = n > 0 ? sum / n : 0;
                    recvLevel = (int) (recvLevel * 0.85 + average * 0.15);
                    if (isRunning) speaker.write(pcmData, 0, pcmData.length);
                } catch (SocketTimeoutException timeout) {
                    // A quiet session is not an error.
                } catch (Exception failure) {
                    if (isRunning) {
                        consecutiveErrors++;
                        if (consecutiveErrors < 5) System.err.println("RTP playback/receive error");
                        if (consecutiveErrors >= 10 || socket.isClosed()) break;
                    }
                }
            }
        } finally {
            isRunning = false;
            socket.close();
            closeOwnedSpeaker();
            synchronized (this) { stopped = true; }
        }
    }

    /** Close first, never drain: stale devices must not delay the next call. */
    public void stop() {
        final Thread receiving;
        synchronized (this) {
            stopped = true;
            isRunning = false;
            receiving = worker;
        }
        socket.close(); // Unblocks receive even if no RTP was ever received.
        closeOwnedSpeaker();
        if (receiving != null && receiving != Thread.currentThread()) {
            try { receiving.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private void closeOwnedSpeaker() {
        synchronized (speakerCloseLock) {
            if (speakerClosed) return;
            speakerClosed = true;
            closeSpeaker(speaker);
        }
    }

    private static void closeSpeaker(SourceDataLine line) {
        if (line == null) return;
        try { line.stop(); } catch (RuntimeException ignored) { }
        try { line.flush(); } catch (RuntimeException ignored) { }
        try { line.close(); } catch (RuntimeException ignored) { }
    }
}
