# EC2에 All About Wedding 미리보기 올리기

대상: 기존 k-lunch가 실행 중인 Ubuntu 24.04 x86_64, PostgreSQL 16, Nginx 서버. Docker는 사용하지 않습니다. 아래 `SERVER_IP`는 본인의 서버 공인 IP로 바꿔 입력하세요. 실제 주소는 저장소에 기록하지 않고 GitHub Secret으로 관리합니다.

이번 구성은 **영구 DB를 사용하는 개발 미리보기**입니다. 예식장 가격·업체는 가상 자료이고, 관리자 등록 자료는 비공개 초안입니다. HTTP이므로 보안 그룹을 본인 IP로 제한하고 테스트용 계정·데이터만 사용합니다. 실제 회원을 받기 전 도메인·HTTPS·계정 운영 기능을 준비해야 합니다.

## 1. GitHub 검증 결과 먼저 확인

`why-ds/wedding-api`와 `why-ds/wedding-web`의 Actions에서 `Verify and deploy … preview`의 **verify**가 성공해야 합니다. 아직 배포 변수를 켜지 않았으면 **deploy가 skipped인 것은 정상**입니다.

API는 일회용 PostgreSQL 16에서 모든 마이그레이션과 DB 저장·CSV 롤백·동시 요청을 검증합니다. 실제 서버의 lunch DB에 테스트를 실행하지 않습니다. 빌드도 GitHub에서 수행합니다.

## 2. 서버에서 최초 한 번 준비

서버의 Ubuntu 터미널에서 실행합니다. 디렉터리가 이미 있으면 clone 대신 해당 폴더에서 `git pull --ff-only`를 실행하세요.

```bash
cd ~
git clone https://github.com/why-ds/wedding-api.git wedding-api-setup
cd wedding-api-setup
sudo bash deploy/bootstrap-server.sh
```

스크립트가 하는 일:

- 8088·18081 포트 충돌, 기존 wedding DB·계정·설정, PostgreSQL 확장 설치 가능 여부를 먼저 확인합니다.
- 필요하면 Java 21 JRE를 추가하고 기존 `java` 기본 경로를 복원합니다. 패키지 설치 중 기존 서비스 자동 재시작은 요청하지 않습니다.
- `wedding_app` DB 계정과 별도 `wedding` DB를 생성합니다. lunch DB를 수정하지 않습니다. 계정은 슈퍼유저·DB 생성·역할 생성 권한이 없습니다. 미리보기에서는 마이그레이션을 위해 wedding DB 소유자 권한을 사용합니다.
- 비밀번호를 임의 생성해 root만 읽을 수 있는 `/etc/wedding-api.env`, `/etc/wedding-initial-login.txt`에 보관합니다.
- API 전용 OS 계정·systemd 서비스·배포 폴더를 만듭니다. 아직 JAR가 없으므로 API를 시작하지 않습니다.
- Nginx에 8088 미리보기 사이트를 추가하고 `nginx -t` 성공 후 reload합니다. 기존 k-lunch 사이트 설정은 수정하지 않습니다.

기존 이름이 있으면 덮어쓰지 않고 중단합니다. 중간 실패 후에는 오류 원인을 확인하고 이어서 복구해야 하므로 DB나 설정 파일을 임의 삭제하고 재실행하지 마세요.

설정 직후 8088에서 404/500/502가 보일 수 있습니다. 웹과 API 결과물을 아직 올리지 않았기 때문입니다.

## 3. GitHub 접속 정보 등록 — 두 저장소 각각

각 저장소의 **Settings → Environments → New environment**에서 `preview`를 만듭니다. 그 환경의 Secrets에 아래 4개를 등록합니다. 비밀 키는 채팅이나 소스 파일에 올리지 않습니다.

| Secret 이름 | 값 |
| --- | --- |
| `SERVER_HOST` | `SERVER_IP` |
| `SERVER_USER` | `ubuntu` |
| `SERVER_KEY` | 서버에 접속 가능한 SSH 개인 키 전체. 기존 k-lunch 배포에서 사용하는 원본 키를 사용할 수 있습니다. GitHub에 저장된 Secret 값 자체는 다시 열람할 수 없습니다. |
| `SERVER_KNOWN_HOSTS` | 아래 명령 결과 한 줄 |

서버 터미널에서 SSH 서버 공개 키를 확인합니다. 이 명령은 개인 키를 출력하지 않습니다.

```bash
printf 'SERVER_IP '
cut -d ' ' -f 1,2 /etc/ssh/ssh_host_ed25519_key.pub
```

출력은 `SERVER_IP ssh-ed25519 AAAA…` 형태입니다. 두 저장소 모두 같은 값을 등록합니다. 배포는 이 키로 서버 신원을 검증하며, 호스트 키 검증을 끄지 않습니다. GitHub 실행기에서 서버의 SSH 포트로 접속할 수 있어야 합니다. 기존 k-lunch의 SSH 접근 정책을 확인하세요.

그다음 각 저장소 **Settings → Secrets and variables → Actions → Variables → New repository variable**에서 다음을 추가합니다. 이 항목은 environment 변수가 아닌 **Repository variable**입니다.

```text
WEDDING_DEPLOY_ENABLED = true
```

## 4. 최초 배포 실행

각 저장소 Actions에서 해당 workflow를 선택하고 **Run workflow → main**을 실행합니다. API가 성공한 뒤 웹을 실행하면 확인하기 편합니다. 이후 main에 push하면 검증 후 자동 배포됩니다. DB 비밀번호나 관리자 비밀번호는 GitHub에 등록할 필요가 없습니다.

- API: 업로드 체크섬 확인 → 새 릴리스 폴더 → 링크 전환 → **wedding-api.service만 재시작** → DB 연결·검색 API 확인.
- API 시작에 실패하면 이전 JAR 링크로 복구하고 오류를 반환합니다. **이미 적용된 SQL은 자동 롤백하지 않습니다.** 향후 마이그레이션은 이전 앱과 호환되도록 추가하고 중요한 변경 전에 DB 백업을 확보해야 합니다.
- 웹: 체크섬 확인 → 새 정적 파일 폴더 → 링크 전환. Nginx 재시작과 Node 개발 서버가 필요 없습니다.
- 같은 커밋의 같은 결과물은 재배포할 수 있습니다. 같은 커밋 ID인데 결과물이 다르면 덮어쓰지 않습니다.

## 5. AWS 포트 설정과 접속

EC2의 보안 그룹 인바운드에 **사용자 지정 TCP / 8088 / 소스 내 IP**를 추가합니다. API 18081과 DB 5432는 외부에 열지 않습니다. 서버 UFW가 활성이라면 UFW에서도 본인 IP의 8088 접근을 별도로 허용해야 합니다. 기존 SSH·k-lunch 규칙은 유지합니다.

- 회원 화면: http://SERVER_IP:8088/
- 관리자: http://SERVER_IP:8088/admin
- 초기 관리자 이메일: `admin@allaboutwedding.local`

비밀번호는 서버에서 확인합니다. 이 파일 내용이나 비밀번호가 보이는 화면은 공유하지 마세요.

```bash
sudo cat /etc/wedding-initial-login.txt
```

최초 관리자 로그인이 성공한 뒤 초기 발급 설정을 끕니다. 이미 생성한 관리자 계정은 DB에 남습니다.

```bash
sudo sed -i '/^ADMIN_PASSWORD=/d; s/^WEDDING_ADMIN_BOOTSTRAP_ENABLED=true$/WEDDING_ADMIN_BOOTSTRAP_ENABLED=false/' /etc/wedding-api.env
sudo systemctl restart wedding-api.service
```

## 운영 중 확인

```bash
sudo systemctl status wedding-api.service --no-pager
sudo journalctl -u wedding-api.service -n 80 --no-pager
curl -fsS http://127.0.0.1:18081/actuator/health
free -h
df -h /
```

API는 JVM heap 384MiB, 서비스 메모리 상한 768MiB, DB 커넥션 최대 4개로 시작합니다. 이는 초기 제한이지 동시 운영 안정성 보장이 아닙니다. 부하·실제 메모리를 보고 조정해야 하며, OOM 재시작이 반복되면 제한을 무작정 올리지 말고 서버 용량을 점검합니다.

릴리스와 업로드 자료가 누적됩니다. `/opt/wedding-api/releases`, `/var/www/wedding-web/releases`, `~/wedding-deploy` 용량을 확인하고 현재·복구용 버전을 보존하는 정리 정책을 마련하세요. DB 백업·보존 정책도 실제 데이터를 받기 전에 필요합니다.

기존 k-lunch workflow에는 `pkill -f 'java -jar'`가 있습니다. 웨딩 실행 명령은 Java 경로와 JVM 옵션을 명시해 현재 패턴에 매치되지 않게 했지만, k-lunch도 자체 systemd 서비스만 재시작하도록 개선해야 합니다. 두 앱을 포괄하는 종료 명령을 추가하지 마세요.

HTTP 미리보기의 쿠키 이름은 `WEDDINGSESSION`으로 분리해 k-lunch의 일반적인 세션 쿠키와 충돌을 피합니다. 같은 IP의 다른 포트는 쿠키의 보안 격리 경계가 아닙니다. 도메인 연결 후 HTTPS와 `SESSION_COOKIE_SECURE=true`로 전환합니다.

## 현재 검증 범위

로컬에서 기존 애플리케이션 테스트·React 빌드·셸 구문을 확인합니다. PostgreSQL 16 통합 검증은 GitHub의 verify 결과를 기준으로 확인해야 합니다. 초기 설정 스크립트의 apt·systemd·Nginx 변경과 실제 EC2 배포는 서버에서 실행되기 전에는 완료된 것으로 간주하지 않습니다.
