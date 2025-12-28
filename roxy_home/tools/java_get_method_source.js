var JavaAnalysisService = Java.type('org.roxycode.core.service.JavaAnalysisService');

try {
    var service = ctx.getBean(JavaAnalysisService.class);
    var path = sandbox.resolve(args.path);
    var source = service.getMethodSource(path, args.className, args.methodName);
    
    if (source.isPresent()) {
        source.get();
    } else {
        "❌ Method not found: " + args.className + "." + args.methodName;
    }
} catch (e) {
    "❌ Error getting method source: " + e.message;
}
