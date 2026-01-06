package org.roxycode.core.tools.service;

import jakarta.inject.Singleton;
import org.roxycode.core.tools.ScriptService;
import org.roxycode.core.utils.ComponentScreenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

@ScriptService("sierraPreviewService")
@Singleton
public class SierraPreviewService {

    private SierraPreviewFrame frame;

    public String previewSierra(String path) {
        if (frame == null) {
            frame = new SierraPreviewFrame(Path.of(path));
        }

        try {
            frame.preview();
            frame.revalidate();
            BufferedImage image = ComponentScreenshot.captureComponent(frame);

            // Save to temp file
            File outputFile = File.createTempFile("sierra_preview_", ".png");
            ImageIO.write(image, "png", outputFile);
//            outputFile.deleteOnExit();
            close();
            return outputFile.getAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (frame != null) {
            frame.close();
            frame = null;
        }
    }
}
