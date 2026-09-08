package com.wedding.identity;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Single-process development limit. Use shared limits before multi-instance deployment. */
@Component
public class AuthRateLimiter {
    private record Window(long expires, int count) {}
    private final Map<String,Window> attempts=new HashMap<>();
    public synchronized void check(String remoteAddress) {
        long now=System.currentTimeMillis();
        attempts.entrySet().removeIf(e->e.getValue().expires()<now);
        var old=attempts.getOrDefault(remoteAddress,new Window(now+60_000,0));
        if(old.count()>=20 || (!attempts.containsKey(remoteAddress) && attempts.size()>=10_000))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"요청이 많습니다. 1분 뒤 다시 시도해 주세요.");
        attempts.put(remoteAddress,new Window(old.expires(),old.count()+1));
    }
}
