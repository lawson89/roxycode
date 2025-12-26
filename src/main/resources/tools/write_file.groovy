import java.nio.file.Files
import java.nio.file.StandardOpenOption

def p = sandbox.resolve(args.path)
Files.createDirectories(p.getParent())
Files.writeString(p, args.content,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)

return "Successfully wrote to " + args.path