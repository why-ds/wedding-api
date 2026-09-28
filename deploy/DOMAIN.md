# allaboutwedding.co.kr 연결

서버와 애플리케이션 설치 이후의 별도 단계다. DNS가 준비될 때까지 `wedding-preview`의 SSH 터널 접속은 유지한다. `SERVER_IP`에는 본인의 EC2 탄력적 IP를 사용한다.

## DNS

루트 도메인(`@`)의 A 레코드를 `SERVER_IP`로 연결한다. IPv6를 구성하지 않았다면 다른 서버를 가리키는 AAAA 레코드를 추가하지 않는다. `www`를 사용하려면 별도 DNS 연결과 인증서/SNI 설정도 함께 추가해야 한다. 아래 초기 설정은 루트 도메인만 대상으로 한다.

## 현재 준비 상태

`wedding-domain-http.nginx.conf`는 해당 호스트의 ACME 인증 경로만 제공하고 나머지는 준비 중(503)으로 응답한다. 공개 HTTP에서 로그인 화면을 제공하지 않는다. 기존 k-lunch 호스트 설정과 소스/DB는 변경하지 않는다. k-lunch 실행 여부와 웨딩 실행은 서로 독립적이다.

## DNS 연결 확인 후

1. 서버와 외부 DNS에서 도메인이 EC2 주소로 해석되는지 확인한다. 80/443 인바운드도 확인한다.
2. 서버에 설치된 Certbot으로 전용 인증서를 발급한다. 기존 다른 도메인의 인증서를 재사용하지 않는다.

   ```bash
   sudo certbot certonly --webroot -w /var/www/wedding-acme \
     --cert-name allaboutwedding.co.kr -d allaboutwedding.co.kr
   ```

3. 발급 성공 후 `wedding-domain-https.nginx.conf`를 `/etc/nginx/sites-available/wedding-domain`에 설치한다. `nginx -t` 성공을 확인하고 reload한다.
4. `/etc/wedding-api.env`의 `SESSION_COOKIE_SECURE=true`를 설정하고 **wedding-api.service만** 재시작한다. 비밀번호나 환경파일 전체를 출력하지 않는다.
5. HTTPS의 검색, 회원 로그인/로그아웃, 관리자 권한과 세션 쿠키 Secure 속성을 확인한다. HTTP→HTTPS 이동과 ACME 경로가 유지되는지도 확인한다.
6. Certbot 자동 갱신 타이머와 성공한 갱신 후 Nginx reload hook을 확인하고, 해당 인증서의 갱신 모의 실행을 검증한다.

Secure 쿠키 적용 후에는 SSH 터널의 HTTP 화면에서 로그인 세션을 사용할 수 없다. 로그인은 정식 HTTPS 도메인으로 진행한다. 인증서 경고를 무시하거나 다른 도메인의 인증서로 대신하지 않는다.

## 상태 확인

```bash
sudo nginx -t
systemctl is-active wedding-api.service
curl -fsS http://127.0.0.1:18081/actuator/health
```

k-lunch는 실행만 중지한 상태라면 필요할 때 `sudo systemctl start lunch.service`로 다시 시작할 수 있다. 소스 변경/재배포와 서비스 자동 시작 설정은 별도 작업이다.
