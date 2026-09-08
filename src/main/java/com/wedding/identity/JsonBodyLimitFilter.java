package com.wedding.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds JSON before deserialization, including requests without Content-Length. */
public class JsonBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX=262144;
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String type=request.getContentType();
        if(!request.getRequestURI().startsWith("/api/")||type==null||!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {chain.doFilter(request,response);return;}
        if(request.getContentLengthLong()>MAX) {reject(response);return;}
        byte[] body=request.getInputStream().readNBytes(MAX+1);
        if(body.length>MAX){reject(response);return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read(){return input.read();}
                    public boolean isFinished(){return input.available()==0;}
                    public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous API only");}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"요청 데이터는 256KB 이내로 보내 주세요.\"}");
    }
}
