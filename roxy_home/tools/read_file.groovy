import java.nio.file.Files
// 'sandbox' is injected by the engine
def p = sandbox.resolve(args.path)
if (Files.exists(p)) {
    return Files.readString(p)
} else {
    return "Error: File not found at " + args.path
}