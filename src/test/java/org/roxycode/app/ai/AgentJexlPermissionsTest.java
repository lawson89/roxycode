package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AgentJexlPermissionsTest {

    @AgentService(
            value = "baseService",
            phases = {"*"})
    static class BaseAgentService {
        @AgentDoc("Documented method")
        public String documentedMethod() {
            return "ok";
        }

        public String undocumentedMethod() {
            return "hidden";
        }
    }

    static class SubclassAgentService extends BaseAgentService {
        @Override
        public String documentedMethod() {
            return "sub-ok";
        }

        public String subclassUndocumentedMethod() {
            return "sub-hidden";
        }
    }

    static class PlainHelper {
        public String helperMethod() {
            return "plain";
        }
    }

    @Test
    void testNullSafety() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;
        Method method = BaseAgentService.class.getMethod("documentedMethod");

        assertFalse(permissions.allow((Method) null));
        assertFalse(permissions.allow(null, method));
        assertFalse(permissions.allow(BaseAgentService.class, (Method) null));
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
    void testObjectMethodsAndNonAgentClassesAllowed() throws Exception {
        AgentJexlPermissions permissions = AgentJexlPermissions.INSTANCE;
        Method toStringMethod = Object.class.getMethod("toString");
        Method plainMethod = PlainHelper.class.getMethod("helperMethod");

        assertTrue(permissions.allow(BaseAgentService.class, toStringMethod));
        assertTrue(permissions.allow(PlainHelper.class, plainMethod));
        assertTrue(permissions.allow(plainMethod));
    }
}
