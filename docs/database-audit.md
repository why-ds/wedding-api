# DB 분리·저장 경로 점검 — 2026-09-28

## 분리의 정확한 의미

같은 EC2와 PostgreSQL 16 인스턴스에 **서로 다른 데이터베이스** `lunch`, `wedding`이 있다. 같은 DB 안에서 테이블 이름만 나눈 것이 아니다. 다만 서버/디스크/DB 엔진 장애를 분리한 물리적 독립 환경은 아니다.

| 항목 | k-lunch | All About Wedding |
|---|---|---|
| DB | `lunch` | `wedding` |
| DB 소유자 | 기존 `postgres` | `wedding_migrator` |
| 실행 DB 계정 | 기존 JAR의 prod 설정은 `postgres` | `wedding_app` |
| DDL | 기존 설정 유지, 앱의 Hibernate 설정은 validate | 배포용 `wedding_migrator`만 사용 |
| 앱 실행 | 사용자 요청으로 중지 유지 | `wedding-api.service` 실행 |
| 소스/JAR 변경 | 없음 | 검증한 웨딩 릴리스 배포 |

**남은 운영 위험:** k-lunch의 기존 설정은 PostgreSQL 최고관리자 계정을 사용한다. 이번 작업은 k-lunch 소스·JAR·DB 내용을 바꾸지 않았다. 재운영 전 k-lunch도 제한된 실행 계정으로 전환하고 기존 관리자 자격증명을 정리해야 한다. PostgreSQL 관리자와 서버 root까지 서로 격리한 환경이라고 주장하지 않는다.

## 발견한 접속 권한과 수정

`wedding`은 이미 PUBLIC CONNECT가 제거되어 있었지만 `lunch`의 기본 PUBLIC CONNECT 때문에 웨딩 계정도 인증 시 접속할 수 있었다. 단순한 `REVOKE ... FROM wedding_app`는 PUBLIC에서 상속된 권한을 거부하지 못한다.

`deploy/isolate-wedding-database.py`로 다음을 적용했다.

- 기존 `pg_hba.conf` 원본을 백업하고 관리 블록만 추가한다. 구문 검증 실패 시 복원한다.
- `wedding_app`, `wedding_migrator`는 loopback의 `wedding`에만 비밀번호 인증으로 접속할 수 있다. 다른 DB나 외부 주소의 연결은 거절한다.
- `wedding`의 네트워크 접속은 위 두 역할만 허용한다. 기존 k-lunch의 `postgres` 계정으로 하는 일반 TCP 연결도 거절한다.
- 로컬 OS postgres 사용자의 peer 관리 접속은 유지한다. 백업·DB 유지보수에 필요하다.
- PostgreSQL 재시작 없이 설정을 reload했다. k-lunch 인증 규칙·소스·DB 내용은 유지했다.

실제 비밀번호 인증으로 두 웨딩 계정 각각 `wedding` 성공, `lunch`/`postgres` 거절을 확인했다. 비밀번호는 출력하지 않았다. HBA 거절은 `has_database_privilege` 결과와 별개이므로 ACL 조회 하나만으로 접속 차단 여부를 판단하지 않는다.

동일 PostgreSQL 인스턴스의 최고관리자는 본질적으로 전체 관리 권한이 있다. 이 제한은 애플리케이션 접속 경계를 강화하는 조치이며, 악성 최고관리자/서버 root에 대한 물리적 격리가 아니다.

## 웨딩 저장 경로

| 기능 | 실제 저장소 | 이번 판단/수정 |
|---|---|---|
| 회원·비밀번호 해시·관리자 역할 | `iam.*` | PostgreSQL, 현재 계정 상태/역할 재조회 |
| 회원 찜 | `planning.user_favorite` | 실제 공개 업체도 저장 가능하게 수정. 500건 제한과 계정별 행 잠금 |
| 내 찜 목록 | 공개 업체+회원 찜 DB 조인 | 로그인 계정에서만 ID를 얻고 게시 중단/휴업 업체는 제외 |
| 업체 초안·CSV 검증/확정 | `ops.catalog_draft`, `catalog_import` | 기존 트랜잭션·버전 검사·CSV commit 멱등 처리 유지 |
| 공개 업체·출처 | `partner.*`, `catalog.listing*`, `ops.catalog_publication`, `source_document` | 초안과 승인 스냅샷 분리 유지 |
| 관리자 변경 이력 | `ops.audit_event` | 실행 DB 계정의 수정/삭제 권한 금지 유지 |
| 저장소 상태 화면 | 관리자 전용 DB 조회 | DB/계정/권한/테이블 건수 표시. 연결 실패를 정상/0건으로 위장하지 않음 |
| 예식장 견적 체험 | PostgreSQL의 명시적 가상 fixture/view | `/preview/venues`로 분리. 실제 가격/예약으로 취급하지 않음 |
| 기본 화면 | 공개 업체 DB | `/`와 `/directory` 모두 실제 검수 업체 목록을 표시 |
| 로그인 세션·CSRF | 서버 메모리 | 업무 원본 데이터가 아닌 인증 상태. 앱 재시작 시 재로그인 필요 |
| 로그인 빈도 제한 | 서버 메모리 | 현재 단일 인스턴스용. 다중 서버 전환 전 공유 제한 저장소 필요 |
| 비회원 체험 찜 | 해당 브라우저 localStorage | 가상 견적 체험용. 실제 공개 업체 찜은 로그인 후 DB에 저장 |

서버 연결 실패 시 브라우저 회원 상태를 비회원 저장소로 조용히 대체하던 처리를 제거했다. 실제 업체 찜은 연결 상태가 불명확하면 저장을 막고 오류를 표시한다.

## 잘못된 DB 연결 방지

`preview` 배포 프로필은 시작 시 현재 DB가 `wedding`, 현재 계정이 `wedding_app`이며 최고관리자·역할 생성·DB 생성·복제·RLS 우회 권한이 없는지 확인한다. 불일치 시 실행을 중단한다.

오프라인 마이그레이션도 Flyway 실행 **전에** `wedding`/`wedding_migrator`와 역할 권한을 확인한다. k-lunch DB를 잘못 지정한 마이그레이션이 실행되지 않도록 방어한다. 로컬 demo/일회용 PostgreSQL CI 환경과 배포 검사를 구분한다.

`preview`에서 회원 데이터를 DB에 저장하더라도 견적 체험 자료는 여전히 가상 데이터다. 세션 API의 `ephemeral=false`는 회원·찜 영구 저장을 뜻하며 모든 콘텐츠의 실재성이나 로그인 세션 영속성을 뜻하지 않는다.

## 아직 구현하지 않은 기능

V905에서 업체 상세·사진 주소·주차 조건·정확한 날짜/시각의 참고 견적과 관리 검수를 추가했다. 참고 견적은 아래 범용 가격 계산이나 사용자 조합 견적 저장 기능과 다르다. [상세 구조](listing-details.md)를 참고한다.

상품·세부 옵션·가격표의 관리 편집/검수, 조건별 범용 계산, 마이페이지 조합 견적 저장, 실제 후기·문의·예약은 별도 구현 단계다. 테이블이 있는 기능과 사용자에게 동작하는 기능을 구분한다. 상세 구조는 [database-roadmap.md](database-roadmap.md)를 따른다.

또한 서버 밖 백업 복제·전체 복원 훈련, 이메일 인증/비밀번호 재설정은 아직 완료하지 않았다. 이번 변경은 이러한 기능을 구현했다고 표시하지 않는다.

## 검증 기준

- PostgreSQL 16 CI: 실제 마이그레이션, 공개 업체 찜, 사용자 간 목록 격리, 게시 중단 후 비노출, 500건 경계의 동시 저장, 잘못된 배포 DB 거절.
- 보안 API 테스트: 저장소 상태는 현재 관리자만 조회, 내 찜 목록은 로그인 필요, 기존 CSRF·역할 위조 차단 유지.
- 브라우저: 기본 경로가 DB 업체 목록, 공개 업체 찜/해제, 내 찜, 관리자 저장소 상태, 장애 시 오류 표시와 찜 비활성화.
- 운영: DB 계정별 교차 접속 차단, 실제 관리자 조회, DDL/감사 수정 권한, 서비스 상태 및 기존 k-lunch 중지 상태 확인.
