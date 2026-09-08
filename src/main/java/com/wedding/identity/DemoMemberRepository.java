package com.wedding.identity;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("demo")
public class DemoMemberRepository implements MemberRepository {
    private final Map<UUID, Member> members = new HashMap<>();
    private final Map<String, UUID> emails = new HashMap<>();
    private final Map<UUID, Set<String>> favorites = new HashMap<>();
    private final Set<UUID> admins = new HashSet<>();
    public synchronized boolean isAdmin(UUID id) { return members.containsKey(id) && admins.contains(id); }
    public synchronized void grantAdmin(UUID id) { if(!members.containsKey(id)) throw MemberService.unauthorized(); admins.add(id); }
    public synchronized Optional<Member> byEmail(String email) { return Optional.ofNullable(emails.get(email)).flatMap(this::byId); }
    public synchronized Optional<Member> byId(UUID id) { return Optional.ofNullable(members.get(id)); }
    public synchronized Member create(String email, String name, String hash) {
        if (emails.containsKey(email)) throw MemberService.conflict();
        var member = new Member(UUID.randomUUID(), email, name, hash);
        members.put(member.id(), member); emails.put(email, member.id()); return member;
    }
    public synchronized Member rename(UUID id, String name) {
        var old = byId(id).orElseThrow(MemberService::unauthorized);
        var member = new Member(id, old.email(), name, old.passwordHash()); members.put(id, member); return member;
    }
    public synchronized Set<String> favorites(UUID id) { return Set.copyOf(favorites.getOrDefault(id, Set.of())); }
    public synchronized void favorite(UUID id, UUID listingId, boolean saved) {
        var ids = favorites.computeIfAbsent(id, key -> new HashSet<>());
        if (saved) ids.add(listingId.toString()); else ids.remove(listingId.toString());
    }
}
