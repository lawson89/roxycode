try {
    // 'java' is bound to JavaAnalysisService
    var path = sandbox.resolve(args.path);
    java.replaceMethod(path, args.className, args.methodName, args.newMethodSource);
    "✅ Method " + args.className + "." + args.methodName + " replaced successfully.";
} catch (e) {
    "❌ Error replacing method: " + e.message;
}
