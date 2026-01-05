package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

@Singleton
public class FilesView extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(FilesView.class);

    private final SettingsService settingsService;

    @Outlet
    private JComponent viewFiles;

    @Outlet
    private JTree fileTree;

    @Inject
    public FilesView(SettingsService settingsService) {
        this.settingsService = settingsService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "FilesView.xml"));
        refresh();
    }

    public void refresh() {
        Path currentProjectRoot = settingsService.getCurrentProjectPath();
        if (currentProjectRoot != null) {
            populateFileTree(currentProjectRoot.toFile());
        }
    }

    private void populateFileTree(File rootDir) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootDir.getName().equals(".") ? "Project" : rootDir.getName());
        buildTreeNodes(root, rootDir);
        fileTree.setModel(new DefaultTreeModel(root));
    }

    private void buildTreeNodes(DefaultMutableTreeNode node, File file) {
        File[] files = file.listFiles();
        if (files == null)
            return;
        Arrays.sort(files, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory())
                return -1;
            if (!f1.isDirectory() && f2.isDirectory())
                return 1;
            return f1.getName().compareToIgnoreCase(f2.getName());
        });
        for (File child : files) {
            if (child.isHidden() || child.getName().startsWith("."))
                continue;
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child.getName());
            node.add(childNode);
            if (child.isDirectory())
                buildTreeNodes(childNode, child);
        }
    }
}
