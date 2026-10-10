package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 视频通话窗口：左侧显示本地摄像头画面，右侧显示对端画面。
 *
 * <p>所有界面更新都走 {@link SwingUtilities#invokeLater}，
 * 因为画面是在接收线程/采集线程里拿到的，Swing 组件只能在事件线程上更新。
 */
public class VideoWindow extends JFrame {

    private final JLabel localLabel = new JLabel("本地画面", SwingConstants.CENTER);
    private final JLabel remoteLabel = new JLabel("对端画面", SwingConstants.CENTER);

    public VideoWindow(int width, int height) {
        super("视频通话");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // 由通话流程负责关闭

        localLabel.setPreferredSize(new Dimension(width, height));
        remoteLabel.setPreferredSize(new Dimension(width, height));
        localLabel.setOpaque(true);
        remoteLabel.setOpaque(true);
        localLabel.setBackground(Color.DARK_GRAY);
        remoteLabel.setBackground(Color.BLACK);
        localLabel.setForeground(Color.LIGHT_GRAY);
        remoteLabel.setForeground(Color.LIGHT_GRAY);

        JPanel panel = new JPanel(new GridLayout(1, 2, 6, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(localLabel);
        panel.add(remoteLabel);

        setContentPane(panel);
        pack();
        setLocationRelativeTo(null);
    }

    /** 更新本地预览 */
    public void updateLocal(BufferedImage image) {
        if (image != null) SwingUtilities.invokeLater(() -> localLabel.setIcon(new ImageIcon(image)));
    }

    /** 更新对端画面 */
    public void updateRemote(BufferedImage image) {
        if (image != null) SwingUtilities.invokeLater(() -> remoteLabel.setIcon(new ImageIcon(image)));
    }

    /** 在界面线程上显示/关闭窗口 */
    public void showWindow() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }

    public void closeWindow() {
        SwingUtilities.invokeLater(() -> {
            setVisible(false);
            dispose();
        });
    }
}
