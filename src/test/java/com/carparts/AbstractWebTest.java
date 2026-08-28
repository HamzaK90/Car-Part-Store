package com.carparts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Drives the API the way a client does: over HTTP, through the real filter chain.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> A rolling-back test would keep a session
 * open for the whole request, which is precisely the condition this application does not run
 * in — {@code open-in-view} is disabled. Every lazy-loading bug it has had would be invisible
 * here: the 500 from {@code fulfil} rendering an order outside its transaction passed every
 * service-level check and only appeared when something drove the endpoint.
 *
 * <p>The cost is that these tests share a database and see each other's writes. They are
 * written to suit that: reads lean on the V6 seed, and anything created carries {@link #tag()}
 * so two runs — or two tests — cannot collide on a unique constraint.
 */
@AutoConfigureMockMvc
public abstract class AbstractWebTest extends IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    private String tag;

    @BeforeEach
    void freshTag() {
        // Unique per test method, so a name or phone number created here cannot clash with one
        // created by another test, or by an earlier run against the same database.
        tag = Long.toString(System.nanoTime() % 100_000_000L);
    }

    /** A value unique to this test, for names and phone numbers that must not collide. */
    protected String tag() {
        return tag;
    }

    // ---------------------------------------------------------------- requests

    protected MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    protected MockHttpServletRequestBuilder get(String path, String token) {
        return bearer(MockMvcRequestBuilders.get(path), token);
    }

    protected MockHttpServletRequestBuilder post(String path, String token, Object body)
            throws Exception {
        return withBody(MockMvcRequestBuilders.post(path), token, body);
    }

    protected MockHttpServletRequestBuilder patch(String path, String token, Object body)
            throws Exception {
        return withBody(MockMvcRequestBuilders.patch(path), token, body);
    }

    protected MockHttpServletRequestBuilder put(String path, String token, Object body)
            throws Exception {
        return withBody(MockMvcRequestBuilders.put(path), token, body);
    }

    protected MockHttpServletRequestBuilder delete(String path, String token) {
        return bearer(MockMvcRequestBuilders.delete(path), token);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String token,
                                                   Object body) throws Exception {
        bearer(b, token).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            b.content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return b;
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder b, String token) {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    // ---------------------------------------------------------------- helpers

    /** The status of a request, for the many assertions that care about nothing else. */
    protected int status(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request).getResponse().getStatus();
    }

    protected JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        String content = perform(request).getResponse().getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    /**
     * Logs in and returns the token.
     *
     * <p>Every seeded account shares {@link IntegrationTest#PASSWORD}, because
     * {@code application-test.yml} gives both of V6's placeholders the same digest.
     */
    protected String login(String username) throws Exception {
        JsonNode response = body(post("/api/auth/login", null,
                java.util.Map.of("username", username, "password", PASSWORD)));
        return response.path("token").asText(null);
    }
}
