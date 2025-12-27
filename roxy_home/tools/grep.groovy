// Delegate to Java Service
import org.roxycode.core.GrepService

try {
    GrepService grepService = ctx.getBean(GrepService.class)
    return grepService.grep(args.pattern, args.path, args.filePattern)
} catch (Exception e) {
    return "❌ Error executing grep service: " + e.message
}
