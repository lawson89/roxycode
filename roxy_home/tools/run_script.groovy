import groovy.lang.Binding
import groovy.lang.GroovyShell

def scriptContent = args.script
if (!scriptContent) return "❌ No script content provided."

// Create a new binding, explicitly passing the 'sandbox' object
// so the inner script can use sandbox.resolve() and adhere to safety protocols.
def binding = new Binding()
binding.setVariable("sandbox", sandbox)

// Use the current classloader to ensure visibility of project classes
def shell = new GroovyShell(getClass().getClassLoader(), binding)

try {
    def result = shell.evaluate(scriptContent)
    // Convert result to string for display, handling nulls gracefully
    return result != null ? result.toString() : "Script executed successfully (no result)."
} catch (Exception e) {
    // Provide a helpful error message
    return "❌ Exception during script execution: " + e.toString()
}
