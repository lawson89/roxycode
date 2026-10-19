package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.InlineView;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.ImplementationPlan;

class PlanPanelTest {

    @Test
    void testBuildPlanHtmlUsesNowrapAndNonBreakingCheckbox() {
        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Requirement 1"),
                        List.of(
                                new ImplementationPlan.TechStep(
                                        "Long step description that should wrap in its column without squishing the index or checkbox.",
                                        false),
                                new ImplementationPlan.TechStep("Completed step", true)),
                        false,
                        null,
                        null,
                        null,
                        List.of());

        String html = PlanPanel.buildPlanHtml(plan);

        assertTrue(html.contains("[&nbsp;]"), "Unchecked checkbox should use non-breaking space");
        assertFalse(
                html.contains(">[ ]<"), "Unchecked checkbox should not use regular breaking space");
        assertTrue(html.contains("<b>[x]</b>"), "Completed checkbox should contain [x]");
        assertTrue(html.contains("nowrap='nowrap'"), "Index and checkbox cells should have nowrap");
        assertTrue(
                html.contains("<td width='100%'"),
                "Step description cell should take 100% remaining width");
    }

    @Test
    void testPlanPanelRendersNonZeroMinimumSpanForNowrapCells() throws Exception {
        PlanService planService = mock(PlanService.class);
        WorkflowService workflowService = mock(WorkflowService.class);

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Req"),
                        List.of(
                                new ImplementationPlan.TechStep(
                                        "Update src/main/resources/prompts/plan_phase.md to remove stale planService method names and refer to the provided JEXL API documentation.",
                                        false)),
                        false,
                        null,
                        null,
                        null,
                        List.of());

        when(planService.getCurrentPlan()).thenReturn(plan);

        PlanPanel panel = new PlanPanel(planService, workflowService);
        SwingUtilities.invokeAndWait(() -> {});

        JEditorPane editorPane = panel.getDisplayArea();
        HTMLEditorKit kit = (HTMLEditorKit) editorPane.getEditorKit();
        ViewFactory factory = kit.getViewFactory();
        HTMLDocument doc = (HTMLDocument) editorPane.getDocument();

        Element td0 = findTdWithText(doc.getDefaultRootElement(), "0.");
        assertNotNull(td0, "Should find index <td> element");
        assertTrue(
                hasPositiveInlineMinSpan(td0, factory),
                "Nowrap index cell inline content should preserve positive minimum X span so it does not wrap");

        Element tdCheckbox = findTdWithText(doc.getDefaultRootElement(), "[");
        assertNotNull(tdCheckbox, "Should find checkbox <td> element");
        assertTrue(
                hasPositiveInlineMinSpan(tdCheckbox, factory),
                "Nowrap checkbox cell inline content should preserve positive minimum X span so it does not wrap");

        Element tdDesc = findTdWithText(doc.getDefaultRootElement(), "Update src/main/resources");
        assertNotNull(tdDesc, "Should find description <td> element");
        assertFalse(
                hasPositiveInlineMinSpan(tdDesc, factory),
                "Description cell inline content should have 0 minimum X span to allow wrapping");
    }

    @Test
    void testBuildPlanMarkdownFormatsPlanAndHandoffLog() {
        assertEquals("", PlanPanel.buildPlanMarkdown(null));

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Visibility Feature",
                        "Improve visibility",
                        List.of("Show turns", "Export markdown"),
                        List.of(
                                new ImplementationPlan.TechStep("Update ChatPanel", true),
                                new ImplementationPlan.TechStep("Update PlanPanel", false)),
                        true,
                        "2026-04-06T12:00:00",
                        "user",
                        null,
                        List.of(
                                new ImplementationPlan.LogEntry(
                                        "2026-04-06T12:05:30.123",
                                        "Architect",
                                        "Created plan | verified\ndetails")));

        String md = PlanPanel.buildPlanMarkdown(plan);

        assertTrue(md.contains("# Implementation Plan: Visibility Feature"));
        assertTrue(md.contains("**Goal:** Improve visibility"));
        assertTrue(md.contains("## Requirements\n- Show turns\n- Export markdown"));
        assertTrue(
                md.contains(
                        "## Technical Steps\n- [x] 0. Update ChatPanel\n- [ ] 1. Update PlanPanel"));
        assertTrue(md.contains("## Handoff Log"));
        assertTrue(md.contains("| Timestamp | User | Message |"));
        assertTrue(
                md.contains(
                        "| 2026-04-06 12:05:30 | Architect | Created plan \\| verified details |"));
    }

    @Test
    void testCopyMarkdownButtonStateAndAction() throws Exception {
        PlanService planService = mock(PlanService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        org.mockito.ArgumentCaptor<PlanService.PlanUpdateListener> listenerCaptor =
                org.mockito.ArgumentCaptor.forClass(PlanService.PlanUpdateListener.class);

        when(planService.getCurrentPlan()).thenReturn(null);
        PlanPanel panel = new PlanPanel(planService, workflowService);
        verify(planService).addListener(listenerCaptor.capture());
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(
                panel.getCopyMarkdownButton().isEnabled(),
                "Copy button should be disabled when plan is null");

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Req 1"),
                        List.of(new ImplementationPlan.TechStep("Step 0", true)),
                        false,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(plan);
        listenerCaptor.getValue().onPlanUpdated(plan);
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(
                panel.getCopyMarkdownButton().isEnabled(),
                "Copy button should be enabled when plan is present");
        assertEquals("Copy Plan as Markdown", panel.getCopyMarkdownButton().getText());

        panel.getCopyMarkdownButton().doClick();

        assertEquals("Copied!", panel.getCopyMarkdownButton().getText());
        assertNotNull(panel.getLastCopiedMarkdown());
        assertTrue(panel.getLastCopiedMarkdown().contains("# Implementation Plan: Test Plan"));
        assertTrue(panel.getLastCopiedMarkdown().contains("- [x] 0. Step 0"));
    }

    private boolean hasPositiveInlineMinSpan(Element elem, ViewFactory factory) {
        View view = factory.create(elem);
        if (view instanceof InlineView) {
            return view.getMinimumSpan(View.X_AXIS) > 0;
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            if (hasPositiveInlineMinSpan(elem.getElement(i), factory)) {
                return true;
            }
        }
        return false;
    }

    private Element findTdWithText(Element elem, String snippet) throws Exception {
        if ("td".equalsIgnoreCase(elem.getName())) {
            int start = elem.getStartOffset();
            int end = elem.getEndOffset();
            String text = elem.getDocument().getText(start, end - start);
            if (text.contains(snippet)) {
                return elem;
            }
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            Element found = findTdWithText(elem.getElement(i), snippet);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
