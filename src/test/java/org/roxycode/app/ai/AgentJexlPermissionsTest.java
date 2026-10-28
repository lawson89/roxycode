package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.BuildResult;
import org.roxycode.app.ai.services.EditorResult;

class AgentJexlPermissionsTest {

    @AgentService(value = "baseService", phases = {"*"})
    public static class BaseAgentService {
        @AgentDoc("Documented method")
        public String documentedMethod() {
            return "ok";
        }

        public String undocumentedMethod() {
            return "hidden";
        }
    }

    public static class SubclassAgentService extends BaseAgentService {
        @Override
        public String documentedMethod() {
            return "sub-ok";
        }

        public String subclassUndocumentedMethod() {
            return "sub-hidden";
        }
    }

    public static class PlainHelper {
        public String helperMethod() {
            return "plain";
        }
    }

    @Test
    void testNullSafety() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;
        Method method = BaseAgentService.class.getMethod("documentedMethod");

        assertFalse(permissions.allow((Constructor<?>) null));
        assertFalse(permissions.allow((Method) null));
        assertFalse(permissions.allow(null, method));
        assertFalse(permissions.allow(BaseAgentService.class, (Method) null));
        assertFalse(AgentJexlPermissions.isAllowedNonServiceType(null));
    }

    @Test
    void testConstructorsAreAlwaysDenied() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;
        assertFalse(permissions.allow(String.class.getConstructor()));
        assertFalse(permissions.allow(ArrayList.class.getConstructor()));
        assertFalse(permissions.allow(ProcessBuilder.class.getConstructor(String[].class)));
        assertFalse(permissions.allow(PlainHelper.class.getDeclaredConstructor()));
    }

    @Test
    void testAgentServiceMethodRequiresAgentDoc() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;
        Method documented = BaseAgentService.class.getMethod("documentedMethod");
        Method undocumented = BaseAgentService.class.getMethod("undocumentedMethod");

        assertTrue(permissions.allow(documented));
        assertTrue(permissions.allow(BaseAgentService.class, documented));

        assertFalse(permissions.allow(undocumented));
        assertFalse(permissions.allow(BaseAgentService.class, undocumented));
    }

    @Test
    void testSubclassAndInheritedAnnotationsSupportedViaSpringAnnotationUtils() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;
        Method overriddenDocumented = SubclassAgentService.class.getMethod("documentedMethod");
        Method subUndocumented = SubclassAgentService.class.getMethod("subclassUndocumentedMethod");

        assertTrue(AgentJexlPermissions.isAgentServiceClass(SubclassAgentService.class));
        assertTrue(AgentJexlPermissions.hasAgentDoc(overriddenDocumented));
        assertTrue(permissions.allow(SubclassAgentService.class, overriddenDocumented));
        assertFalse(permissions.allow(SubclassAgentService.class, subUndocumented));
    }

    @Test
    void testObjectGetClassIsDeniedWhileSafeObjectAndAppMethodsAllowed() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;
        Method toStringMethod = Object.class.getMethod("toString");
        Method getClassMethod = Object.class.getMethod("getClass");
        Method plainMethod = PlainHelper.class.getMethod("helperMethod");
        Method buildSuccess = BuildResult.class.getMethod("success");
        Method editorContent = EditorResult.class.getMethod("content");

        assertTrue(permissions.allow(BaseAgentService.class, toStringMethod));
        assertFalse(permissions.allow(BaseAgentService.class, getClassMethod));
        assertFalse(permissions.allow(String.class, getClassMethod));
        assertFalse(permissions.allow(getClassMethod));

        assertTrue(permissions.allow(PlainHelper.class, plainMethod));
        assertTrue(permissions.allow(plainMethod));
        assertTrue(permissions.allow(BuildResult.class, buildSuccess));
        assertTrue(permissions.allow(EditorResult.class, editorContent));
    }

    @Test
    void testSafeJdkValueAndCollectionTypesAllowed() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;

        assertTrue(permissions.allow(String.class, String.class.getMethod("trim")));
        assertTrue(permissions.allow(Integer.class, Integer.class.getMethod("intValue")));
        assertTrue(permissions.allow(Math.class, Math.class.getMethod("max", int.class, int.class)));
        assertTrue(permissions.allow(ArrayList.class, List.class.getMethod("size")));
        assertTrue(permissions.allow(HashMap.class, Map.class.getMethod("get", Object.class)));
        assertTrue(permissions.allow(WorkflowPhase.class, Enum.class.getMethod("name")));
    }

    @Test
    void testDangerousJdkClassesAreDenied() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;

        assertFalse(permissions.allow(ProcessBuilder.class, ProcessBuilder.class.getMethod("start")));
        assertFalse(permissions.allow(Runtime.class, Runtime.class.getMethod("getRuntime")));
        assertFalse(permissions.allow(System.class, System.class.getMethod("exit", int.class)));
        assertFalse(permissions.allow(System.class, System.class.getMethod("getenv", String.class)));
        assertFalse(permissions.allow(Thread.class, Thread.class.getMethod("currentThread")));
        assertFalse(permissions.allow(Class.class, Class.class.getMethod("forName", String.class)));
        assertFalse(permissions.allow(File.class, File.class.getMethod("delete")));
        assertFalse(permissions.allow(Files.class, Files.class.getMethod("readString", Path.class)));
        assertFalse(permissions.allow(Path.class, Path.class.getMethod("of", String.class, String[].class)));
    }
}
