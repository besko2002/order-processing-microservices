package com.example.orders.support.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice test of the shared error rendering: one controller that throws, the real advice, and the
 * real message converters.
 */
class ApiExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void notFoundIsRenderedAs404() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product 42 does not exist"))
                .andExpect(jsonPath("$.path").value("/test/not-found"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void conflictIsRenderedAs409() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("SKU already exists"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void badRequestIsRenderedAs400() throws Exception {
        mvc.perform(get("/test/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Items must not be empty"));
    }

    @Test
    void validationFailureListsEveryFieldError() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.path").value("/test/validate"))
                .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.quantity").isNotEmpty());
    }

    @Test
    void malformedJsonIsA400WithoutFieldErrors() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing request body"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void missingRequiredParameterIsA400NamingIt() throws Exception {
        mvc.perform(get("/test/parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter is missing: count"))
                .andExpect(jsonPath("$.fieldErrors.count").isNotEmpty());
    }

    @Test
    void unparsableIdIsA400() throws Exception {
        mvc.perform(get("/test/by-id/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parameter has the wrong format: id"))
                .andExpect(jsonPath("$.fieldErrors.id").isNotEmpty());
    }

    @Test
    void accessDeniedIsRenderedAs403WithTheSharedMessage() throws Exception {
        mvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(RestAccessDeniedHandler.MESSAGE));
    }

    @Test
    void constraintViolationsAreRenderedAs409() throws Exception {
        mvc.perform(get("/test/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Request conflicts with the current state of the data"));
    }

    @Test
    void optimisticLockingFailureIsRenderedAs409() throws Exception {
        mvc.perform(get("/test/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The resource was modified concurrently, please retry"));
    }

    @Test
    void unexpectedFailureIsA500ThatLeaksNothing() throws Exception {
        String body = mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value(ApiExceptionHandler.UNEXPECTED_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("password")
                .doesNotContain("IllegalStateException")
                .doesNotContain("jdbc");
    }

    @RestController
    @RequestMapping("/test")
    static class ThrowingController {

        @org.springframework.web.bind.annotation.GetMapping("/not-found")
        void notFound() {
            throw NotFoundException.of("Product", 42);
        }

        @org.springframework.web.bind.annotation.GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("SKU already exists");
        }

        @org.springframework.web.bind.annotation.GetMapping("/bad-request")
        void badRequest() {
            throw new BadRequestException("Items must not be empty");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {
            // never reached in these tests
        }

        @org.springframework.web.bind.annotation.GetMapping("/parameter")
        void parameter(@RequestParam int count) {
            // never reached in these tests
        }

        @org.springframework.web.bind.annotation.GetMapping("/by-id/{id}")
        void byId(@PathVariable UUID id) {
            // never reached in these tests
        }

        @org.springframework.web.bind.annotation.GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("nope");
        }

        @org.springframework.web.bind.annotation.GetMapping("/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
        }

        @org.springframework.web.bind.annotation.GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException("Product", UUID.randomUUID());
        }

        @org.springframework.web.bind.annotation.GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("jdbc connection failed for user with password=hunter2");
        }
    }

    record Payload(@NotBlank String name, @Min(1) int quantity) {
    }
}
