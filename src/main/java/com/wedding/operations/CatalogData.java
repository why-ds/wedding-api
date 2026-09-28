package com.wedding.operations;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Version-one intake contract. Product specifications and pricing have separate future contracts. */
public record CatalogData(
    @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9_-]{2,79}") String externalKey,
    @NotBlank @Size(max=120) String organizationName,
    @NotBlank @Size(max=100) String branchName,
    @NotNull Category category,
    @NotBlank @Size(max=60) String region,
    @NotBlank @Size(max=250) String address,
    @Size(max=30) @Pattern(regexp="[0-9+() -]*") String publicPhone,
    @Size(max=1000) String sourceUrl,
    @jakarta.validation.Valid CatalogDetails details
) {
    public CatalogData { details=details==null?CatalogDetails.empty():details; }
    public CatalogData(String externalKey,String organizationName,String branchName,Category category,String region,String address,String publicPhone,String sourceUrl){this(externalKey,organizationName,branchName,category,region,address,publicPhone,sourceUrl,null);}
    public enum Category { VENUE, STUDIO, DRESS, MAKEUP, JEWELRY, HANBOK, SUIT, WEDDING_PHOTO, IPHONE_SNAP, WEDDING_VIDEO }
    private static String clean(String value) { return value==null?"":value.strip().replaceAll("\\s+"," "); }
    public CatalogData normalized() { return new CatalogData(clean(externalKey).toLowerCase(Locale.ROOT),clean(organizationName),clean(branchName),category,clean(region),clean(address),clean(publicPhone),clean(sourceUrl),details); }
    public String identityKey() { return sha((organizationName+"\u001f"+branchName+"\u001f"+category+"\u001f"+address).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)); }
    public static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
