package org.roxycode.ui.views;

import com.google.genai.types.CachedContent;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.cache.GeminiCacheService;
import org.roxycode.core.utils.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

@Singleton
public class GeminiOnlineCachesView extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(GeminiOnlineCachesView.class);

    private final GeminiCacheService geminiCacheService;

    private DefaultTableModel geminiCachesModel;

    @Outlet
    private JComponent viewGeminiOnlineCaches;

    @Outlet
    private JTable geminiCachesTable;

    @Outlet
    private JButton refreshGeminiCachesButton;

    @Outlet
    private JButton deleteAllGeminiCachesButton;

    @Inject
    public GeminiOnlineCachesView(GeminiCacheService geminiCacheService) {
        this.geminiCacheService = geminiCacheService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "GeminiOnlineCachesView.xml"));
        if (geminiCachesTable != null) {
            geminiCachesModel = new DefaultTableModel(new Object[] { "ID", "Model", "Created", "Expires", "Size (Tokens)" }, 0) {

                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            geminiCachesTable.setModel(geminiCachesModel);
            JPopupMenu popupMenu = new JPopupMenu();
            JMenuItem deleteItem = new JMenuItem("Delete Cache");
            deleteItem.addActionListener(e -> {
                int row = geminiCachesTable.getSelectedRow();
                if (row != -1) {
                    String id = (String) geminiCachesTable.getValueAt(row, 0);
                    onDeleteGeminiCache(id);
                }
            });
            popupMenu.add(deleteItem);
            geminiCachesTable.setComponentPopupMenu(popupMenu);
        }
        initListeners();
        refresh();
    }

    private void initListeners() {
        if (refreshGeminiCachesButton != null) {
            refreshGeminiCachesButton.addActionListener(e -> refresh());
        }
        if (deleteAllGeminiCachesButton != null) {
            deleteAllGeminiCachesButton.addActionListener(e -> onDeleteAllGeminiCaches());
        }
    }

    public void refresh() {
        if (geminiCachesModel == null)
            return;
        geminiCachesModel.setRowCount(0);
        new Thread(() -> {
            try {
                List<CachedContent> caches = geminiCacheService.listCaches();
                SwingUtilities.invokeLater(() -> {
                    for (CachedContent cache : caches) {
                        String id = cache.name().orElse("");
                        String model = cache.model().orElse("");
                        String created = cache.createTime().map(Object::toString).orElse("");
                        String expires = cache.expireTime().map(Object::toString).orElse("");
                        String size = cache.usageMetadata().flatMap(u -> u.totalTokenCount().map(String::valueOf)).orElse("0");
                        geminiCachesModel.addRow(new Object[] { id, model, created, expires, size });
                    }
                });
            } catch (Exception e) {
                log.error("Error listing Gemini caches", e);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane errPane = new JOptionPane("Error listing Gemini caches: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
                    JDialog errDialog = errPane.createDialog(this, "Error");
                    UIUtils.centerDialog(errDialog, this);
                    errDialog.setVisible(true);
                });
            }
        }).start();
    }

    private void onDeleteAllGeminiCaches() {
        JOptionPane pane = new JOptionPane("Are you sure you want to delete ALL online Gemini caches?", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        JDialog dialog = pane.createDialog(this, "Confirm Delete All");
        UIUtils.centerDialog(dialog, this);
        dialog.setVisible(true);
        Object selectedValue = pane.getValue();
        if (selectedValue instanceof Integer && (Integer) selectedValue == JOptionPane.YES_OPTION) {
            new Thread(() -> {
                try {
                    geminiCacheService.deleteAllCaches();
                    SwingUtilities.invokeLater(this::refresh);
                } catch (Exception e) {
                    log.error("Error deleting Gemini caches", e);
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane errPane = new JOptionPane("Error deleting Gemini caches: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
                        JDialog errDialog = errPane.createDialog(this, "Error");
                        UIUtils.centerDialog(errDialog, this);
                        errDialog.setVisible(true);
                    });
                }
            }).start();
        }
    }

    private void onDeleteGeminiCache(String id) {
        JOptionPane pane = new JOptionPane("Are you sure you want to delete cache: " + id + "?", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        JDialog dialog = pane.createDialog(this, "Confirm Delete");
        UIUtils.centerDialog(dialog, this);
        dialog.setVisible(true);
        Object selectedValue = pane.getValue();
        if (selectedValue instanceof Integer && (Integer) selectedValue == JOptionPane.YES_OPTION) {
            new Thread(() -> {
                try {
                    geminiCacheService.deleteCache(id);
                    SwingUtilities.invokeLater(this::refresh);
                } catch (Exception e) {
                    log.error("Error deleting Gemini cache: {}", id, e);
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane errPane = new JOptionPane("Error deleting Gemini cache: " + id + ": " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
                        JDialog errDialog = errPane.createDialog(this, "Error");
                        UIUtils.centerDialog(errDialog, this);
                        errDialog.setVisible(true);
                    });
                }
            }).start();
        }
    }
}
