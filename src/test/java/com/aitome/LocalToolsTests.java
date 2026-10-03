package com.aitome;

import com.aitome.tools.LocalToolsController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LocalToolsTests {
    @TempDir Path directory;
    @Test void rejectsMissingWrongAndUnconfiguredSecrets() {
        var request = new MockHttpServletRequest();
        var controller = new LocalToolsController("owner-secret", directory.toString());
        assertEquals(403, controller.list(request, "").getStatusCode().value());
        assertEquals(403, controller.list(request, "wrong").getStatusCode().value());
        assertEquals(403, new LocalToolsController("", directory.toString()).list(request, "").getStatusCode().value());
    }
    @Test void scansOnlyMetadataWithNoCaching() throws Exception {
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: sample\ndescription: Example skill\n---\nPRIVATE BODY");
        var response = new LocalToolsController("secret", directory.toString()).list(new MockHttpServletRequest(), "secret");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(1, ((Map<?, ?>)response.getBody()).get("total"));
        assertTrue(response.getBody().toString().contains("Example skill"));
        assertFalse(response.getBody().toString().contains("PRIVATE BODY"));
    }
}
