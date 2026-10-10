package org.example;

import com.github.sarxos.webcam.Webcam;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

/**
 * 摄像头采集：按指定分辨率打开默认摄像头，提供"取当前帧"的接口。
 *
 * <p>底层用 webcam-capture 库（纯 Java API，内含 Windows 原生桥）。
 * 若请求的分辨率不被支持，会自动退到最接近的可用分辨率。
 */
public class VideoCapture {

    private Webcam webcam;
    private final int wantWidth;
    private final int wantHeight;

    public VideoCapture(int wantWidth, int wantHeight) {
        this.wantWidth = wantWidth;
        this.wantHeight = wantHeight;
    }

    /** 打开摄像头 */
    public void start() {
        webcam = Webcam.getDefault();
        if (webcam == null) {
            throw new IllegalStateException("没有检测到摄像头");
        }
        Dimension size = pickSize();
        webcam.setViewSize(size);
        webcam.open();
        System.out.println("✓ 摄像头已打开: " + webcam.getName()
                + "  " + size.width + "x" + size.height);
    }

    /** 在设备支持的分辨率里挑一个最接近请求值的（优先精确匹配，否则取不超过请求的最大者） */
    private Dimension pickSize() {
        Dimension[] sizes = webcam.getViewSizes();
        for (Dimension d : sizes) {
            if (d.width == wantWidth && d.height == wantHeight) return d;
        }
        Dimension best = null;
        for (Dimension d : sizes) {
            if (d.width <= wantWidth && d.height <= wantHeight) {
                if (best == null || d.width * d.height > best.width * best.height) best = d;
            }
        }
        return (best != null) ? best : sizes[0];
    }

    /** 取一帧画面；未打开时返回 null */
    public BufferedImage grab() {
        if (webcam == null || !webcam.isOpen()) return null;
        return webcam.getImage();
    }

    public boolean isOpen() {
        return webcam != null && webcam.isOpen();
    }

    public void stop() {
        if (webcam != null && webcam.isOpen()) {
            webcam.close();
            System.out.println("摄像头已关闭");
        }
    }
}
