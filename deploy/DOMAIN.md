# allaboutwedding.co.kr 연결

서버와 애플리케이션 설치 이후의 별도 단계다. DNS가 준비될 때까지 `wedding-preview`의 SSH 터널 접속은 유지한다. `SERVER_IP`에는 본인의 EC2 탄력적 IP를 사용한다.

## DNS

루트 도메인(`@`)의 A 레코드를 `SERVER_IP`로 연결한다. `www`는 루트 도메인으로 CNAME을 설정하거나 같은 서버로 A 레코드를 연결한다. IPv6를 구성하지 않았다면 다른 서버를 가리키는 AAAA 레코드를 추가하지 않는다. HTTPS 설정은 두 호스트를 모두 포함하는 인증서가 필요하며, `www` 접속은 루트 도메인으로 이동한다.

## 현재 준비 상태

`wedding-domain-http.nginx.conf`는 해당 호스트의 ACME 인증 경로만 제공하고 나머지는 준비 중(503)으로 응답한다. 공개 HTTP에서 로그인 화면을 제공하지 않는다. 기존 k-lunch 호스트 설정과 소스/DB는 변경하지 않는다. k-lunch 실행 여부와 웨딩 실행은 서로 독립적이다.

## DNS 연결 확인 후

1. 서버와 외부 DNS에서 도메인이 EC2 주소로 해석되는지 확인한다. 80/443 인바운드도 확인한다.
2. 서버에 설치된 Certbot으로 전용 인증서를 발급한다. 기존 다른 도메인의 인증서를 재사용하지 않는다.

   ```bash
   sudo certbot certonly --webroot -w /var/www/wedding-acme \
     --cert-name allaboutwedding.co.kr -d allaboutwedding.co.kr -d www.allaboutwedding.co.kr
   ```

3. 발급 성공 후 `wedding-domain-https.nginx.conf`를 `/etc/nginx/sites-available/wedding-domain`에 설치한다. `nginx -t` 성공을 확인하고 reload한다.
4. `/etc/wedding-api.env`의 `SESSION_COOKIE_SECURE=true`를 설정하고 **wedding-api.service만** 재시작한다. 비밀번호나 환경파일 전체를 출력하지 않는다.
5. HTTPS의 검색, 회원 로그인/로그아웃, 관리자 권한과 세션 쿠키 Secure 속성을 확인한다. HTTP→HTTPS 이동과 ACME 경로가 유지되는지도 확인한다.
6. 인증서 갱신 후 새 인증서를 읽도록 전용 hook을 설치하고, 해당 인증서의 갱신 모의 실행을 검증한다.

   ```bash
   sudo install -o root -g root -m 0755 deploy/renew-wedding-certificate.sh \
     /etc/letsencrypt/renewal-hooks/deploy/wedding-nginx-reload
   systemctl is-enabled certbot.timer
   systemctl is-active certbot.timer
   sudo certbot renew --cert-name allaboutwedding.co.kr --dry-run
   ```

   Hook은 웨딩 인증서가 갱신된 경우에만 `nginx -t` 성공 후 reload한다. 기본 dry-run은 deploy hook을 실행하지 않으므로 hook 구문 검사와 실제 Nginx reload도 별도로 확인한다.

   인증서에 포함된 모든 호스트가 HTTP ACME 경로를 제공해야 한다. 기존 인증서에 `www`가 포함돼 있으면 루트 주소만 정상이어도 갱신에 실패할 수 있다. 두 Nginx 템플릿 모두 루트 주소와 `www`의 HTTP ACME 경로를 제공한다.

Secure 쿠키 적용 후에는 SSH 터널의 HTTP 화면에서 로그인 세션을 사용할 수 없다. 로그인은 정식 HTTPS 도메인으로 진행한다. 인증서 경고를 무시하거나 다른 도메인의 인증서로 대신하지 않는다.

## 상태 확인

```bash
sudo nginx -t
systemctl is-active wedding-api.service
curl -fsS http://127.0.0.1:18081/actuator/health
```

k-lunch는 실행만 중지한 상태라면 필요할 때 `sudo systemctl start lunch.service`로 다시 시작할 수 있다. 소스 변경/재배포와 서비스 자동 시작 설정은 별도 작업이다.

기존 앱을 중지해도 Nginx의 정적 파일 제공은 계속된다. 웨딩의 443 호스트 설정이 없으면 기존 기본 HTTPS 사이트가 선택될 수 있다. 반드시 전용 인증서와 `server_name allaboutwedding.co.kr` 설정을 활성화한 뒤 정식 도메인으로 확인한다. IP 직접 접속이나 인증서 경고 무시로 검증하지 않는다.
