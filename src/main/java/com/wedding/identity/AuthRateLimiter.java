package com.wedding.identity;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login/registration throttle with two independent keys.
 * <ul>
 *   <li>Client network: every attempt counts, 20 per minute. IPv6 clients are keyed by their /64,
 *       because one subscriber usually controls the whole prefix.</li>
 *   <li>Account (normalized email): only failures count, 10 per 5 minutes, cleared by a successful login.
 *       This stops one account being guessed from many addresses (credential stuffing).</li>
 * </ul>
 * Both tables are bounded LRU maps: when full, the least recently used window is dropped instead of
 * refusing every new client, so flooding the table cannot lock all users out.
 * Single-process only. Before running more than one API instance, move these counters to a shared
 * store (for example Bucket4j with Redis or a PostgreSQL table).
 */
@Component
public class AuthRateLimiter {
    static final int NETWORK_LIMIT=20;
    static final long NETWORK_WINDOW_MS=60_000;
    static final int ACCOUNT_FAILURE_LIMIT=10;
    static final long ACCOUNT_WINDOW_MS=5*60_000;
    static final int MAX_TRACKED=10_000;

    private record Window(long expires,int count) {}
    private final Map<String,Window> networks=lru();
    private final Map<String,Window> accounts=lru();
    private final LongSupplier clock;

    public AuthRateLimiter() { this(System::currentTimeMillis); }
    AuthRateLimiter(LongSupplier clock) { this.clock=clock; }

    private static Map<String,Window> lru() {
        // accessOrder=true: get/put move an entry to the tail, so the eldest entry is the least recently used.
        return new LinkedHashMap<>(256,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,Window> eldest) { return size()>MAX_TRACKED; }
        };
    }

    /** Network-only check, kept for callers without an account identifier. */
    public synchronized void check(String remoteAddress) {
        increment(networks,networkKey(remoteAddress),NETWORK_LIMIT,NETWORK_WINDOW_MS);
    }

    /** Call before verifying credentials. Counts the attempt for the network; rejects a locked account. */
    public synchronized void check(String remoteAddress,String email) {
        String account=accountKey(email);
        // Check the account first so a locked account does not also consume the caller's network budget.
        if(count(accounts,account)>=ACCOUNT_FAILURE_LIMIT) throw tooMany();
        check(remoteAddress);
    }

    /** Records a rejected login or registration for the account. */
    public synchronized void failed(String email) {
        String account=accountKey(email);
        long now=clock.getAsLong();
        var current=live(accounts,account,now);
        accounts.put(account,current==null?new Window(now+ACCOUNT_WINDOW_MS,1):new Window(current.expires(),current.count()+1));
    }

    /** A successful login proves the password, so earlier failures stop counting against the account. */
    public synchronized void succeeded(String email) { accounts.remove(accountKey(email)); }

    private void increment(Map<String,Window> table,String key,int limit,long windowMs) {
        long now=clock.getAsLong();
        var current=live(table,key,now);
        if(current!=null&&current.count()>=limit) throw tooMany();
        table.put(key,current==null?new Window(now+windowMs,1):new Window(current.expires(),current.count()+1));
    }
    private int count(Map<String,Window> table,String key) {
        var current=live(table,key,clock.getAsLong());
        return current==null?0:current.count();
    }
    /** Expired windows are removed lazily on access; the LRU bound handles entries never seen again. */
    private Window live(Map<String,Window> table,String key,long now) {
        var current=table.get(key);
        if(current!=null&&current.expires()<=now) { table.remove(key); return null; }
        return current;
    }

    static String accountKey(String email) { return email==null?"":email.strip().toLowerCase(Locale.ROOT); }
    static String networkKey(String remoteAddress) {
        if(remoteAddress==null) return "";
        // Only parse IPv6 literals (the container supplies literals, so no DNS lookup can happen).
        if(remoteAddress.indexOf(':')<0) return remoteAddress;
        try {
            var address=InetAddress.getByName(remoteAddress);
            if(!(address instanceof Inet6Address)) return address.getHostAddress();
            byte[] bytes=address.getAddress();
            var prefix=new StringBuilder("v6:");
            for(int i=0;i<8;i++) prefix.append(String.format("%02x",bytes[i]));
            return prefix.append("/64").toString();
        } catch(Exception ex) { return remoteAddress; }
    }
    private static ResponseStatusException tooMany() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"요청이 많습니다. 잠시 후 다시 시도해 주세요.");
    }
}
