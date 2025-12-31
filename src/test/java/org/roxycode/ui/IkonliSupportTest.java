package org.roxycode.ui;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class IkonliSupportTest {

    @Test
    public void testIkonliAvailable() {
        // Just verifying classes are present
        MaterialDesignW icon = MaterialDesignW.WALL;
        assertNotNull(icon);
        FontIcon fontIcon = FontIcon.of(icon, 16, Color.RED);
        assertNotNull(fontIcon);
        System.out.println("Created icon: " + icon.getDescription());
    }

    @Test
    public void testLookupByString() {
        String iconName = "mdi2w-walk";
        MaterialDesignW found = null;
        for (MaterialDesignW b : MaterialDesignW.values()) {
            if (b.getDescription().equals(iconName)) {
                found = b;
                break;
            }
        }
        assertNotNull(found, "Should find 'mdi2w-walk'");
    }
}
