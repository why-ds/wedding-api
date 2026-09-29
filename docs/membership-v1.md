# 회원 기능 초안

2026-09-08. 기존 예식장 검색·비교에 기본 회원 기능을 연결했다.

## 구현한 흐름

이메일 회원가입 요청 → 메일의 인증 링크로 계정 생성 → 로그인 → 내 정보 조회·닉네임 수정 → 계정별 찜 저장·삭제 → 로그아웃 → 재로그인.

가입 이메일은 앞뒤 공백 제거·소문자로 정규화하고 중복을 거부한다. 닉네임은 앞뒤 공백 제거 후 2~30자다. 비밀번호는 10자 이상, UTF-8 72바이트 이내이며 BCrypt cost 12로 저장한다. 비밀번호·해시를 응답 DTO에 포함하지 않는다.

로그인은 HttpOnly / SameSite=Lax 쿠키 세션을 사용한다. 로그인 성공 시 이전 세션을 폐기하고 새 세션으로 교체한다. 비밀번호를 재설정하면 그 이전에 로그인한 모든 세션은 다음 요청에서 종료된다. 비활성 30분 후 만료되며 로그아웃은 세션을 무효화한다. HTTPS 환경에서는 `SESSION_COOKIE_SECURE=true`를 설정한다.

## API

| 경로 | 메서드 | 동작 |
|---|---|---|
| /api/v1/auth/csrf | GET | 세션의 CSRF 토큰·헤더 이름 |
| /api/v1/auth/session | GET | 로그인 회원 또는 null, demo 여부 |
| /api/v1/auth/register | POST | 인증 메일 발송(202). 계정은 아직 만들지 않음 |
| /api/v1/auth/verify | POST | `{token}` 인증 링크 확인 후 계정 생성. 로그인하지 않음 |
| /api/v1/auth/password-reset | POST | `{email}` 재설정 메일 발송(202) |
| /api/v1/auth/password-reset/confirm | POST | `{token,password}` 새 비밀번호 저장(204) |
| /api/v1/auth/login | POST | 이메일·비밀번호 로그인 |
| /api/v1/auth/logout | POST | 로그인 세션 무효화 |
| /api/v1/me | GET | 본인 정보 |
| /api/v1/me | PATCH | displayName 변경 |
| /api/v1/me/favorites | GET | 본인의 업체 ID 목록 |
| /api/v1/me/favorites/{listingId} | PUT | 멱등 찜 저장 |
| /api/v1/me/favorites/{listingId} | DELETE | 멱등 찜 삭제 |

회원 쓰기 요청에는 직전 GET /auth/csrf에서 받은 토큰을 응답의 headerName으로 전송한다. 로그인/가입 자체도 CSRF 보호 대상이다. 로그인 이후 토큰은 다시 발급받는다. 브라우저는 API에 same-origin credentials를 사용하며 JWT나 세션 ID를 localStorage에 저장하지 않는다.

공개 검색 POST /searches만 읽기 전용 계산으로 CSRF 예외다. 기본 경로는 거부하고 필요한 공개·회원 경로만 허용한다. 요청의 userId가 아니라 서버 세션의 회원 UUID로 모든 내 정보·찜을 조회한다. 인증 요청은 두 기준으로 제한한다. 서버가 확인한 원격 주소(IPv6는 /64 대역)는 모든 시도를 1분에 20회까지, 이메일 계정은 실패만 5분에 10회까지 허용하며 로그인 성공 시 계정 실패 횟수를 초기화한다. 추적 표가 가득 차면 가장 오래 쓰지 않은 항목을 버리므로 표를 채워 전체 사용자를 차단할 수 없다. 단일 프로세스 메모리 기준이므로 API를 여러 대로 늘리기 전에 공유 저장소로 옮긴다.

## 저장 구조

- `iam.user_account`: 계정·닉네임·상태. 기존 구조 사용.
- `iam.auth_identity`: LOCAL 공급자와 고정 사용자 ID를 연결.
- `iam.local_credential`: 계정 FK, 정규화된 로그인 이메일 UNIQUE, 비밀번호 해시.
- `planning.user_favorite`: (user_id, listing_id) PK. 공동 프로젝트를 만들기 전부터 개인 찜 가능.

V901 마이그레이션은 기존 개발 seed V900을 이미 적용한 DB에서도 순서대로 적용되도록 번호를 정했다. V1·V2·V900은 변경하지 않았다. PostgreSQL 가입은 계정·자격정보·인증 식별자 세 행을 한 트랜잭션으로 생성하며 이메일 UNIQUE 충돌 시 전체 롤백한다.

`demo`는 메모리에 회원·찜을 보관하므로 **서버를 재시작하면 초기화**된다. 회원 화면에 이를 표시하며 테스트용 이메일·비밀번호를 안내한다. `postgres`는 DB에 회원·찜을 보관한다. 두 모드 모두 세션은 현재 서버 메모리를 사용하므로 서버 재시작 후 재로그인이 필요하다. PostgreSQL 통합 실행은 미검증이다.

비회원 찜은 기존 localStorage에 따로 유지한다. 로그인할 때 특정 회원에게 자동 이관하지 않는다. 로그인 중에는 해당 회원의 찜만 표시하고, 로그아웃하면 비회원 찜으로 돌아간다. 탭에 포커스가 돌아올 때 로그인 상태를 재확인하며 만료된 인증으로 회원 찜을 저장할 수 없다.

## 검증

MembershipIntegrationTest는 실제 Spring 필터·컨트롤러와 HttpSession CSRF 토큰을 사용해 다음을 검증한다.

1. 가입·내 정보·닉네임 변경·로그아웃 및 해시 저장.
2. 잘못된 비밀번호 거부, 대소문자 정규화, 로그인 전후 세션 ID 변경.
3. 중복 이메일, 짧은 닉네임·비밀번호, UTF-8 한도 초과 거부.
4. 가입·프로필·찜·로그아웃의 CSRF 누락 거부.
5. 두 회원 간 찜 분리, 중복 저장 멱등성, 미등록 업체 거부, 비회원 접근 거부.
6. 비회원 공개 검색·카테고리 조회 유지.

기존 가격 엔진 5개·컨텍스트 1개를 합쳐 전체 12개 테스트다. 프런트는 타입 검사·Vite 빌드를 수행한다. DOM 상호작용 자동화 및 PostgreSQL 서버 통합 검증은 아직 별도다.

## 후속 범위

## 이메일 인증·비밀번호 재설정 (V910)

- **가입은 링크를 열어야 완료된다.** 가입 요청은 닉네임과 BCrypt 해시를 `iam.email_token`의 대기 가입(REGISTER)으로만 저장한다. 계정은 메일함 소유자가 링크를 연 순간 인증된 상태로 만들어지며, 인증 전 계정은 존재하지 않는다. 같은 주소의 다른 대기 가입은 함께 폐기한다.
- **계정 존재 여부를 노출하지 않는다.** 가입·재설정 요청은 등록 여부와 관계없이 같은 202 응답을 준다. 이미 가입된 주소로 가입을 요청하면 그 주소로 "이미 가입된 계정" 안내 메일만 보낸다. 비밀번호 해시는 모든 경로에서 계산하고 메일은 비동기로 보내서 응답 시간 차이를 줄인다.
- **토큰**은 256비트 난수이며 DB에는 SHA-256만 저장한다. 가입 24시간, 재설정 30분, 한 번만 사용한다. 링크는 `/account/verify#token=…` 형태로 URL fragment에 담아 서버 로그와 Referer에 남지 않게 한다. 페이지는 버튼을 눌러야 토큰을 사용하므로 메일 보안 스캐너의 링크 미리 열기로 소모되지 않는다.
- **링크 주소**는 `WEDDING_PUBLIC_BASE_URL`로만 만든다. 요청 Host 헤더를 쓰지 않는다(재설정 링크 변조 방지).
- **인증 링크를 열어도 로그인하지 않는다.** 링크 소유는 메일함 증명일 뿐 비밀번호 증명이 아니다.
- **재설정**하면 `password_changed_at`이 바뀌고 `CredentialFreshnessFilter`가 그 이전 세션을 끊는다. 해당 계정의 다른 재설정 링크와 로그인 실패 잠금도 해제한다.
- **발송 한도**: 같은 주소로 15분에 3통. 초과분은 응답을 바꾸지 않고 조용히 보내지 않는다.
- V910 이전에 있던 계정(관리자 포함)은 인증된 것으로 간주한다.

### 메일 발송 설정

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `WEDDING_MAIL_PROVIDER` | `log` | `log`: 발송하지 않음(demo는 링크를 로그에 출력). `ses`: Amazon SES |
| `WEDDING_MAIL_FROM` | 없음 | SES에서 인증한 발신 주소. 예: `All About Wedding <no-reply@allaboutwedding.co.kr>` |
| `WEDDING_MAIL_SES_REGION` | `ap-northeast-2` | SES 리전 |
| `WEDDING_PUBLIC_BASE_URL` | demo `http://127.0.0.1:5173`, preview `https://allaboutwedding.co.kr` | 메일 링크의 사이트 주소 |

SES 자격 증명은 환경 변수로 두지 않는다. EC2 인스턴스 역할에 `ses:SendEmail` 권한만 부여하면 AWS SDK 기본 체인이 사용한다. preview 서버는 SES를 설정하기 전까지 `log`이므로 **새 회원가입을 완료할 수 없다**(기존 계정 로그인은 가능).

회원 탈퇴, 이메일 변경, 로그인 상태의 비밀번호 변경, 약관·동의 버전 처리, 소셜 로그인, 공동 프로젝트는 다음 단계다. 로그인 이메일은 개발 DB의 비공개 iam 테이블에 저장하며 연락처 암호화·키 관리 설계는 운영 전 보완 대상이다. 다중 서버 도입 시 세션과 인증 요청 제한도 공유 저장소로 이전해야 한다.

구현 시 확인한 공식 자료: [Spring Security 세션 관리](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html), [CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [비밀번호 저장](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
