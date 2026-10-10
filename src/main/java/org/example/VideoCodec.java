package org.example;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * 视频编解码：BufferedImage 与 JPEG 字节之间的转换。
 *
 * <p>用 JDK 自带的 ImageIO，不需要任何额外的编解码库。
 * 选 JPEG 而不是 H.264，是因为纯 Java 环境下 H.264 实现又重又复杂，
 * 而 JPEG 编码简单、CPU 开销可控，在局域网带宽下完全够用。
 */
public class VideoCodec {

    /**
     * 把画面编码成 JPEG 字节。
     *
     * @param image   画面
     * @param quality 压缩质量 0.0~1.0（越小文件越小、越糊）
     */
    public static byte[] encode(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IllegalStateException("没有可用的 JPEG 编码器");
        ImageWriter writer = writers.next();

        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.05f, Math.min(1.0f, quality)));
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(mos);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** 把 JPEG 字节解码成画面；失败返回 null */
    public static BufferedImage decode(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (Exception e) {
            return null;
        }
    }
}
