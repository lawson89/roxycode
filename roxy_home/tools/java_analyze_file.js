var JavaAnalysisService = Java.type('org.roxycode.core.service.JavaAnalysisService');
var ObjectMapper = Java.type('com.fasterxml.jackson.databind.ObjectMapper');

try {
    var service = ctx.getBean(JavaAnalysisService.class);
    var mapper = ctx.getBean(ObjectMapper.class);
    
    var path = sandbox.resolve(args.path);
    var summary = service.analyzeFile(path);
    
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
} catch (e) {
    "❌ Error analyzing Java file: " + e.message;
}
