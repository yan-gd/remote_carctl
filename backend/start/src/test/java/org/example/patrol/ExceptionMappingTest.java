package org.example.patrol;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.example.patrol.app.AuditService;
import org.example.patrol.client.InspectionService;
import org.example.patrol.domain.BusinessException;
import org.example.patrol.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExceptionMappingTest {
    @Test
    void infrastructureAndUnexpectedFailuresNeverLeakDetails() throws Exception {
        var service = mock(InspectionService.class);
        var audit = mock(AuditService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(new RouteController(service))
                        .setControllerAdvice(new GlobalExceptionHandler(audit))
                        .build();
        doThrow(new DataIntegrityViolationException("secret SQL")).when(service).route(1);
        mvc.perform(get("/api/v1/routes/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
        doThrow(new DataAccessResourceFailureException("secret path")).when(service).route(1);
        mvc.perform(get("/api/v1/routes/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DATABASE_ERROR"));
        doThrow(new IllegalStateException("secret internal")).when(service).route(1);
        mvc.perform(get("/api/v1/routes/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        doThrow(new BusinessException(BusinessException.Kind.FORBIDDEN, "FORBIDDEN", "禁止访问"))
                .when(service)
                .route(1);
        mvc.perform(get("/api/v1/routes/1")).andExpect(status().isForbidden());
    }
}
