package org.roxycode.cache;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class BinaryCheck {

    /**
     * Determines if a file is binary by checking for:
     * 1. PDF Magic Numbers (Treat as Binary)
     * 2. Byte Order Marks (Treat as Text, ignoring Nulls)
     * 3. Presence of Null Bytes (Treat as Binary)
     * 4. Ratio of non-printable characters (Treat as Binary)
     */
    public static boolean isBinaryFile(File file) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int size = 8000; // Check the first 8KB
            byte[] data = new byte[size];
            int bytesRead = in.read(data);

            // Empty files are treated as text
            if (bytesRead == -1) return false;

            // --- 1. PDF Check (Magic Number: %PDF) ---
            // PDF files start with text header but are binary. Return true immediately.
            if (bytesRead >= 4 &&
                data[0] == 0x25 && // %
                data[1] == 0x50 && // P
                data[2] == 0x44 && // D
                data[3] == 0x46) { // F
                return true;
            }

            // --- 2. BOM Check (Byte Order Mark) ---
            // If a BOM is found, it is explicitly a text encoding (UTF-16/32).
            // We return FALSE (Text) immediately to prevent the Null Byte check
            // from incorrectly flagging these as binary.
            if (bytesRead >= 2) {
                int b0 = data[0] & 0xFF;
                int b1 = data[1] & 0xFF;

                // UTF-16BE (FE FF) or UTF-16LE (FF FE)
                if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) {
                    return false;
                }

                // UTF-32 checks (require 4 bytes)
                if (bytesRead >= 4) {
                    int b2 = data[2] & 0xFF;
                    int b3 = data[3] & 0xFF;

                    // UTF-32BE (00 00 FE FF)
                    if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) return false;

                    // UTF-32LE (FF FE 00 00)
                    if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00) return false;
                }
            }

            // --- 3. Standard Null Byte & Control Char Heuristic ---
            int nonPrintableCount = 0;

            for (int i = 0; i < bytesRead; i++) {
                byte b = data[i];

                // A single Null byte usually indicates binary
                // (Unless we found a BOM earlier, which we didn't)
                if (b == 0) return true;

                // Check for non-printable characters
                // ASCII printable is 32-126. We allow Tab(9), LF(10), CR(13)
                if ((b < 32 || b > 126) && b != 9 && b != 10 && b != 13) {
                    nonPrintableCount++;
                }
            }

            // If more than 30% of chars are "garbage" (non-printable), it's binary
            return ((double) nonPrintableCount / bytesRead) > 0.30;
        }
    }
}
