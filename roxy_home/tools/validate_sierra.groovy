import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.EntityResolver

def p = sandbox.resolve(args.path)
def xmlFile = p.toFile()

if (!xmlFile.exists()) {
    return "File not found: " + args.path
}

// Locate sierra.dtd
// Try relative to project root first
def dtdFile = new File(sandbox.getRoot().toFile(), "roxy_home/context/sierra.dtd")
if (!dtdFile.exists()) {
    // Try absolute path known from environment
    dtdFile = new File("/home/rlawson/code/roxycode/roxy_home/context/sierra.dtd")
    if (!dtdFile.exists()) {
        return "Error: sierra.dtd not found. Cannot validate."
    }
}

try {
    def factory = SAXParserFactory.newInstance()
    factory.setValidating(true)
    factory.setNamespaceAware(true)
    
    def parser = factory.newSAXParser()
    def reader = parser.getXMLReader()
    
    reader.setEntityResolver(new EntityResolver() {
        public InputSource resolveEntity(String publicId, String systemId) {
            if (systemId != null && systemId.endsWith("sierra.dtd")) {
                return new InputSource(new FileInputStream(dtdFile))
            }
            return null
        }
    })
    
    def errors = new StringBuilder()
    def errorHandler = new DefaultHandler() {
        void error(org.xml.sax.SAXParseException e) {
            errors.append("Error at line ${e.lineNumber}: ${e.message}\n")
        }
        
        void fatalError(org.xml.sax.SAXParseException e) {
            errors.append("Fatal Error at line ${e.lineNumber}: ${e.message}\n")
        }
        
        void warning(org.xml.sax.SAXParseException e) {
            errors.append("Warning at line ${e.lineNumber}: ${e.message}\n")
        }
    }
    reader.setErrorHandler(errorHandler)
    
    reader.parse(new InputSource(new FileInputStream(xmlFile)))
    
    if (errors.length() > 0) {
        return "Validation Errors:\n" + errors.toString()
    }
    
    return "✅ Validation Successful"

} catch (Exception e) {
    return "Validation Exception: " + e.message
}