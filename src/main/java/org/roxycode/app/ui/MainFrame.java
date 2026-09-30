package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

/**
 * Main application window for RoxyCode.
 */
public class MainFrame extends JFrame {

    public MainFrame() {
        setupLaf();
        setupWindow();
        initComponents();
    }

    private void setupLaf() {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception ex) {
            System.err.println("Failed to initialize LaF");
        }
    }

    private void setupWindow() {
        setTitle("RoxyCode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1024, 768);
        setLocationRelativeTo(null);
    }

    private void initComponents() {
        setLayout(new MigLayout("fill, insets 0", "[200!]0[fill, grow]", "[fill, grow]"));

        // Sidebar
        JPanel sidebar = new JPanel(new MigLayout("wrap, fillx, insets 10", "[fill, grow]", "[]10[]10[]10[]push[]"));
        sidebar.setBackground(new Color(45, 45, 45));

        sidebar.add(new JLabel("🤖 RoxyCode"), "h 40!, gapbottom 20");
        sidebar.add(createNavButton("Chat"));
        sidebar.add(createNavButton("Plan"));
        sidebar.add(createNavButton("Context"));
        sidebar.add(createNavButton("Settings"), "pushy, bottom");

        // Main Content
        JPanel contentArea = new JPanel(new MigLayout("fill", "[center]", "[center]"));
        contentArea.add(new JLabel("Welcome to RoxyCode Agent UI"), "");

        add(sidebar, "grow");
        add(contentArea, "grow");
    }

    private JButton createNavButton(String text) {
        JButton btn = new JButton(text);
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setFocusPainted(false);
        return btn;
    }
}
