# TIEAT 파일럿 위험수용 기록

## QR capability 90일 유지

### 게이트 판정

| 항목 | 요구 수준 | 판정 | 적용 범위 |
| --- | --- | --- | --- |
| `SEC-CAP-QR-LONG-TTL` | `MUST` | `FAIL` | Azure 제한 파일럿과 이후 인터넷 공개 환경 |

- 전체 인터넷 공개 판정: `NO-GO`
- 제한된 파일럿 판정: 아래 범위와 기간에 한해 별도 사람 승인으로 위험수용. 이 기록은 `SEC-CAP-QR-LONG-TTL`을 `PASS`로 바꾸지 않는다.

- Finding IDs and affected environment: `SEC-CAP-QR-LONG-TTL`, `SEC-CAP-003`, `TIEAT-CAP-001`; Azure 파일럿 `rg-tieat-pilot-krc`, 호스트 `tieat.paranglabs.com`
- Pilot scope: 운영 계정 `parangofsky@gmail.com`과 초대 코드로 명시적으로 등록한 파일럿 매장만 사용한다. 일반 공개 가입과 파일럿 외 매장 확장은 이 승인 범위에 포함하지 않는다.
- 2026-09-06 운영자 추가 승인: 초대된 파일럿 매장 고객의 모바일 데이터 QR 접속을 허용한다. Web의 운영자 단일 IP 제한을 해제하되 API 내부 ingress, 초대 코드 가입, QR 보완 통제는 유지한다. 이는 전체 인터넷 공개 `GO` 승인이 아니며 위 파일럿 만료일을 연장하지 않는다.
- Pilot period: 2026-09-04 20:09 KST부터 2026-10-01 23:59 KST까지.
- Decision owner, operator, and approval time: 운영 계정 `parangofsky@gmail.com`; 2026-09-04 20:09 KST.
- Reason and bounded business impact: 매장 부착 QR을 자주 교체하지 않는 제한된 파일럿 운영을 위해 QR capability 기본 수명을 90일로 유지한다. 유출 시 해당 매장의 공개 요청 생성 경로가 악용될 수 있는 위험을 파일럿 기간에만 수용한다.
- Compensating controls already verified:
  - 원문 토큰은 조회용 SHA-256 해시와 Key Vault keyring 기반 AES-GCM 암호문으로만 저장한다.
  - 매장 인증 세션에서 신규 요청을 즉시 중지·재개하고, QR을 폐기·재발급할 수 있으며 변경을 감사 기록한다.
  - 공개 QR Web/API 응답은 `no-store`, `no-referrer`, `noindex`를 적용한다.
  - Next 중계·오류 로그는 `/qr/` 및 공개 QR API 뒤의 전체 URL suffix를 마스킹한다.
  - QR·IP·client·활성 PENDING 제한, 멱등키, `429`와 비상 중지 `503` 경로를 적용한다.
  - 2026-09-04 집중 검증: API 49 tests, Web 27 tests 통과.
- Expiry and mandatory re-review trigger: 2026-10-01 23:59 KST. 토큰 유출 의심, 비정상 요청 증가, edge·proxy·analytics·logging 경로 변경, 파일럿 범위 확대 또는 만료일 도래 시 즉시 재검토한다.
- Rollback / kill-switch owner: 운영 계정 `parangofsky@gmail.com`(승인자와 동일). 의심 시 공개 QR 신규 요청을 먼저 중지하고, 유출이 확인되면 해당 QR을 폐기·재발급한다.
- Pilot exit condition: 만료일까지 QR TTL을 단축하거나 고정 QR을 대체할 회전 구조를 다시 검토하고 새 판정을 기록한다. 어느 쪽도 완료되지 않으면 파일럿 공개 접근을 종료한다.

이 예외는 원래 `FAIL` finding을 삭제하거나 보안 감사 결과를 자동으로 `GO`로 바꾸지 않는다. 제한된 파일럿 실사용과 전체 인터넷 공개 `GO`는 별도 판정이며, 다른 공개 배포 필수 증거는 별도로 충족해야 한다.
