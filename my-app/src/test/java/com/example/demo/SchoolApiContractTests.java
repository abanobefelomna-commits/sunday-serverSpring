package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SchoolApiContractTests {
    @Test
    void healthEndpointStaysPublic() {
        SchoolApiController controller = controller(mock(JdbcTemplate.class), mock(AuthService.class));

        assertEquals("Hello World!", controller.health());
    }

    @Test
    void protectedResourcesRejectMissingBearerTokenBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SchoolApiController controller = controller(jdbc, mock(AuthService.class));
        HttpServletRequest request = new MockHttpServletRequest();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.classes(null, null, 1, 10, request));

        assertEquals(401, exception.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test
    void publicNewsKeepsItsUnwrappedPaginationResponse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<Map<String, Object>>>any(),
                any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        SchoolApiController controller = controller(jdbc, mock(AuthService.class));

        Map<String, Object> response = controller.news(null, 1, 10);

        assertEquals(0L, response.get("total"));
        assertEquals(1, response.get("page"));
        assertEquals(10, response.get("limit"));
        assertEquals(0, response.get("totalPages"));
        assertTrue(response.containsKey("data"));
    }

    @Test
    void servantCannotCreatePrivilegedClass() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = mock(AuthService.class);
        when(auth.authenticatedClaims("token"))
                .thenReturn(Map.of("sub", "3e125539-f198-4c28-ad4a-0ae9daf0bf03", "role", "servant"));
        SchoolApiController controller = controller(jdbc, auth);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.createClass(Map.of("name", "Test", "grade", "first"), request));

        assertEquals(403, exception.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    private SchoolApiController controller(JdbcTemplate jdbc, AuthService auth) {
        return new SchoolApiController(jdbc, auth, new ObjectMapper(), "uploads", false);
    }
}
