try {
    // 'buildTool' is bound to BuildToolService
    var tool = buildTool.detect();
    tool.toString();
} catch (e) {
    "❌ Error detecting build tool: " + e.message;
}
