package com.wedding;

import com.wedding.identity.JsonBodyLimitFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class JsonBodyLimitFilterTest {
    @Test void structuredJsonWithoutContentLengthIsBoundedBeforeParsing() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/v1/searches") {
            @Override public long getContentLengthLong(){return -1;}
            @Override public int getContentLength(){return -1;}
        };
        request.setContentType("application/vnd.wedding+json");
        request.setContent(new byte[262145]);
        var response=new MockHttpServletResponse();
        var proceeded=new AtomicBoolean(false);
        new JsonBodyLimitFilter().doFilter(request,response,(req,res)->proceeded.set(true));
        assertEquals(413,response.getStatus());
        assertFalse(proceeded.get());
    }
}
