package com.wedding.operations;

import jakarta.validation.Validator;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@PreAuthorize("@adminAccess.allowed(authentication)")
public class CatalogIntakeService {
    public static final List<String> HEADERS=List.of("externalKey","organizationName","branchName","category","region","address","publicPhone","sourceUrl");
    private final CatalogDraftRepository repository;
    private final Validator validator;
    public CatalogIntakeService(CatalogDraftRepository repository,Validator validator){this.repository=repository;this.validator=validator;}
    public record Row(int row,CatalogData data,List<String> errors){}
    public record Preview(CatalogDraftRepository.Ticket ticket,List<Row> rows,boolean valid){}
    public CatalogDraftRepository.Page list(int page,String status) {
        if(page<0||page>5000||!Set.of("","DRAFT","ARCHIVED").contains(status))throw bad("목록 조건이 올바르지 않습니다.");return repository.list(page,status);
    }
    public CatalogDraftRepository.Draft create(UUID actor,CatalogData data){return repository.create(actor,checked(data));}
    public CatalogDraftRepository.Draft update(UUID actor,UUID id,long version,CatalogData data){return repository.update(actor,id,version,checked(data));}
    public CatalogDraftRepository.Draft archive(UUID actor,UUID id,long version){return repository.archive(actor,id,version);}
    public List<CatalogDraftRepository.Audit> audits(){return repository.audits();}
    public List<UUID> commit(UUID actor,UUID ticket){return repository.commit(actor,ticket);}
    private CatalogData checked(CatalogData raw) {
        if(raw==null)throw bad("업체 데이터를 입력해 주세요.");var data=raw.normalized();var issues=issues(data);
        if(!issues.isEmpty())throw bad(String.join(" / ",issues));return data;
    }
    private List<String> issues(CatalogData data) {
        var errors=new ArrayList<String>();
        validator.validate(data).stream().map(v->v.getPropertyPath()+": 형식 또는 길이를 확인해 주세요.").sorted().forEach(errors::add);
        for(String value:List.of(data.organizationName(),data.branchName(),data.region(),data.address())) {
            if(!value.isEmpty()&&"=+-@".indexOf(value.charAt(0))>=0)errors.add("이름·주소에는 스프레드시트 수식을 입력할 수 없습니다.");
            if(value.codePoints().anyMatch(Character::isISOControl))errors.add("제어 문자를 포함할 수 없습니다.");
        }
        if(!data.sourceUrl().isEmpty())try {
            var uri=URI.create(data.sourceUrl());
            if(!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)errors.add("출처는 사용자 인증정보가 없는 http(s) URL로 입력해 주세요.");
        }catch(IllegalArgumentException ex){errors.add("출처 URL 형식이 올바르지 않습니다.");}
        return errors;
    }
    public Preview preview(UUID actor,MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>262144)throw bad("비어 있지 않은 256KB 이하 CSV를 선택해 주세요.");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if(!name.endsWith(".csv"))throw bad("UTF-8 CSV 파일만 업로드할 수 있습니다.");
        try {
            byte[] bytes=file.getInputStream().readNBytes(262145);if(bytes.length>262144)throw bad("CSV는 256KB 이하여야 합니다.");
            String content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(content.startsWith("\uFEFF"))content=content.substring(1);
            var format=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setIgnoreEmptyLines(true).get();
            var rows=new ArrayList<Row>();var keys=new HashSet<String>();var identities=new HashSet<String>();
            try(var parser=CSVParser.parse(content,format)) {
                if(parser.getHeaderNames().size()!=HEADERS.size()||!new HashSet<>(parser.getHeaderNames()).equals(new HashSet<>(HEADERS)))throw bad("CSV 열 이름은 제공된 양식과 같아야 합니다.");
                for(var record:parser) {
                    if(rows.size()>=100)throw bad("한 번에 최대 100개 행까지 업로드할 수 있습니다.");
                    int row=(int)record.getRecordNumber()+1;
                    if(!record.isConsistent()){rows.add(new Row(row,null,List.of("열 개수가 맞지 않습니다.")));continue;}
                    CatalogData.Category category=null;
                    try{category=CatalogData.Category.valueOf(record.get("category").strip().toUpperCase(Locale.ROOT));}catch(IllegalArgumentException ignored){}
                    var data=new CatalogData(record.get("externalKey"),record.get("organizationName"),record.get("branchName"),category,record.get("region"),record.get("address"),record.get("publicPhone"),record.get("sourceUrl")).normalized();
                    var errors=issues(data);
                    if(!keys.add(data.externalKey())||!identities.add(data.identityKey()))errors.add("파일 안에 중복 업체가 있습니다.");
                    if(errors.isEmpty()&&repository.conflicts(data,null))errors.add("이미 등록된 관리 코드 또는 업체·지점·주소입니다.");
                    rows.add(new Row(row,data,List.copyOf(errors)));
                }
            }
            if(rows.isEmpty())throw bad("등록할 행이 없습니다.");
            boolean valid=rows.stream().allMatch(r->r.errors().isEmpty());
            var ticket=valid?repository.preview(actor,CatalogData.sha(bytes),rows.stream().map(Row::data).toList()):null;
            return new Preview(ticket,rows,valid);
        }catch(ResponseStatusException ex){throw ex;}
        catch(IOException|IllegalArgumentException|UncheckedIOException ex){throw bad("UTF-8 인코딩과 CSV 따옴표·열 구분을 확인해 주세요.");}
    }
    static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"중복된 관리 코드 또는 업체·지점·주소가 있습니다. 저장된 데이터는 변경하지 않았습니다.");}
    static ResponseStatusException stale(){return new ResponseStatusException(HttpStatus.CONFLICT,"다른 작업에서 변경되었거나 보관된 초안입니다. 목록을 새로고침해 주세요.");}
    static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"대상 데이터를 찾을 수 없습니다.");}
    static ResponseStatusException expired(){return new ResponseStatusException(HttpStatus.GONE,"미리보기가 만료되었습니다. 파일을 다시 검증해 주세요.");}
    static ResponseStatusException limited(){return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"대기 중인 업로드가 많습니다. 잠시 후 다시 시도해 주세요.");}
}
