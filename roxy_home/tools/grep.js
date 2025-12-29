try {
    // 'grep' is bound to GrepService in ToolExecutionService
    grep.grep(args.pattern, args.path, args.filePattern);
} catch (e) {
    "❌ Error executing grep: " + e.message;
}
