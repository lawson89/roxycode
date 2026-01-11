package org.roxycode.ui.views;

import org.junit.jupiter.api.Test;
import org.roxycode.core.tools.service.plans.PlanService;
import org.roxycode.ui.ThemeService;

import static org.mockito.Mockito.*;

class CurrentPlanViewTest {

    @Test
    void testInit() {
        PlanService planService = mock(PlanService.class);
        ThemeService themeService = mock(ThemeService.class);
        CurrentPlanView view = new CurrentPlanView(planService, themeService);
        view.init();
    }
}
