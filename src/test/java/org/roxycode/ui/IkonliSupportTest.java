package org.roxycode.ui;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.bootstrapicons.BootstrapIcons;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IkonliSupportTest {

    @Test
    public void testIkonliAvailable() {
        // Just verifying classes are present
        BootstrapIcons icon = BootstrapIcons.ALARM;
        assertNotNull(icon);
        FontIcon fontIcon = FontIcon.of(icon, 16, Color.RED);
        assertNotNull(fontIcon);
        System.out.println("Created icon: " + icon.getDescription());
    }

    @Test
    public void testLookupByString() {
        String iconName = "bi-alarm";
        BootstrapIcons found = null;
        for (BootstrapIcons b : BootstrapIcons.values()) {
            if (b.getDescription().equals(iconName)) {
                found = b;
                break;
            }
        }
        assertNotNull(found, "Should find 'bi-alarm'");
    }
}
