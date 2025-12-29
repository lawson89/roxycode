// 'preview' is bound to PreviewService
try {
    preview.launchAndScreenshot();
} catch (e) {
    throw new Error("Error launching preview: " + e.message);
}
