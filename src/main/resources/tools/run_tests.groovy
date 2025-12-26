import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.engine.discovery.DiscoverySelectors
import java.io.PrintWriter
import java.io.StringWriter

// Use the sandbox root to find test classes (simplified for MVP: assumes compiled classes on classpath)
// In a real IDE, we might need to compile first or point to build/classes.
// For this MVP "Dog-fooding", we assume the app itself is the classpath.

def request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectPackage("org.roxycode"))
        .build()

def launcher = LauncherFactory.create()
def listener = new SummaryGeneratingListener()

launcher.registerTestExecutionListeners(listener)
launcher.execute(request)

def summary = listener.getSummary()
def writer = new StringWriter()
summary.printTo(new PrintWriter(writer))

return writer.toString()