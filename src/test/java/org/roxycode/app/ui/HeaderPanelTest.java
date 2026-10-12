package org.roxycode.app.ui;
 
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;
import org.springframework.context.ApplicationEventPublisher;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.*;
 
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import javax.swing.*;
 
class HeaderPanelTest {
    @TempDir
    Path tempDir;
 
    private SettingsService createSettingsService() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        return settingsService;
    }
 
    private ProjectService createProjectService(SettingsService ss) {
        return new ProjectService(ss);
    }
 
    private GitService createGitService(ProjectService ps) {
        return new GitService(ps);
    }
 
    private WorkflowService createWorkflowService() {
        return new WorkflowService(mock(PlanService.class), mock(EnvironmentService.class), mock(ApplicationEventPublisher.class));
    }
 
    @Test
    void testHeaderPanelInstantiation() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, createWorkflowService(), mock(PlanService.class), name -> {});
        assertNotNull(header);
    }
 
    @Test
    void testModeTogglesAreRemoved() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, createWorkflowService(), mock(PlanService.class), name -> {});
        
        Component toggle = findComponent(header, JToggleButton.class, c -> true);
        assertNull(toggle, "Mode toggle buttons should be removed from HeaderPanel");
    }
 
    @Test
    void testMentorBadgeIsPresent() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, createWorkflowService(), mock(PlanService.class), name -> {});
        
        JLabel badge = (JLabel) findComponent(header, JLabel.class, c -> "READ-ONLY MENTOR MODE".equals(((JLabel)c).getText()));
        assertNotNull(badge, "Mentor badge should be present in HeaderPanel");
    }
 
    @Test
    void testOpenButtonIsPresent() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, createWorkflowService(), mock(PlanService.class), name -> {});
        
        JButton openButton = (JButton) findComponent(header, JButton.class, c -> "Open Project".equals(((JButton)c).getToolTipText()));
        assertNotNull(openButton);
    }
 
    @Test
    void testHeaderLayoutInsets() {
        SettingsService ss = createSettingsService();
        ProjectService ps = createProjectService(ss);
        HeaderPanel header = new HeaderPanel(ps, createGitService(ps), ss, createWorkflowService(), mock(PlanService.class), name -> {});
        net.miginfocom.swing.MigLayout layout = (net.miginfocom.swing.MigLayout) header.getLayout();
        Object constraints = layout.getLayoutConstraints();
        assertTrue(constraints.toString().contains("insets 5 20 5 20"));
    }
 
    private Component findComponent(Container container, Class<?> clazz, java.util.function.Predicate<Component> predicate) {
        for (Component comp : container.getComponents()) {
            if (clazz.isInstance(comp) && predicate.test(comp)) {
                return comp;
            }
            if (comp instanceof Container) {
                Component found = findComponent((Container) comp, clazz, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
}
