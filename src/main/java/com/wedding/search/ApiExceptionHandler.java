package com.wedding.search;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail invalid(Exception ex) {
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"필수 항목과 입력 형식을 확인해 주세요.");
        if(ex instanceof MethodArgumentNotValidException invalid) problem.setProperty("fields",invalid.getBindingResult().getFieldErrors().stream().map(error->error.getField()).distinct().sorted().toList());
        return problem;
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail business(ResponseStatusException ex) { return ProblemDetail.forStatusAndDetail(ex.getStatusCode(),ex.getReason()==null?"요청을 처리하지 못했습니다.":ex.getReason()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadSize(MaxUploadSizeExceededException ex){return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,"업로드 파일은 256KB 이내로 보내 주세요.");}
    @ExceptionHandler(MultipartException.class)
    public ProblemDetail multipart(MultipartException ex){return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"파일 업로드 형식을 확인해 주세요.");}
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail denied(AccessDeniedException ex){return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,"이 작업을 수행할 권한이 없습니다.");}
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException ex){return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"저장소에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail media(HttpMediaTypeNotSupportedException ex){return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"지원하지 않는 요청 형식입니다.");}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail method(HttpRequestMethodNotSupportedException ex){return ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED,"지원하지 않는 요청 방식입니다.");}
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex){
        String reference=java.util.UUID.randomUUID().toString();
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Unhandled API failure reference={} type={}",reference,ex.getClass().getName());
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,"요청을 처리하지 못했습니다. 반복되면 관리자에게 오류 번호를 알려 주세요.");
        problem.setProperty("reference",reference);return problem;
    }
}
