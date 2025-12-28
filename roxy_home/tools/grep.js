var GrepService = Java.type('org.roxycode.core.GrepService');
var Exception = Java.type('java.lang.Exception');

try {
    var grepService = ctx.getBean(GrepService.class);
    grepService.grep(args.pattern, args.path, args.filePattern);
} catch (e) {
    "❌ Error executing grep service: " + e.message;
}