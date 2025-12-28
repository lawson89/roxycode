import org.roxycode.core.service.TikaService
import java.nio.file.Files

try {
    def tika = ctx.getBean(TikaService.class)
    def path = sandbox.resolve(args.path)
    
    if (!Files.exists(path)) {
        return "Error: File not found: " + args.path
    }
    
    if (Files.isDirectory(path)) {
        return "Error: Path is a directory: " + args.path
    }

    path.toFile().withInputStream { is ->
        return tika.extractText(is)
    }

} catch (Exception e) {
    return "Error processing document: " + e.message
}
