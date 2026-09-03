# TIEAT

TIEAT은 프랜차이즈 매장의 협력사 식대 장부 입력과 확인 업무를 줄이기 위한 장부 자동화 서비스입니다.

협력사 직원은 POS에 비치된 QR을 개인 휴대폰으로 열어 사용 금액을 입력하고, 매장 직원은 태블릿에서 거래를 확인한 뒤 자신의 이니셜로 확정합니다. 선불 계약은 남은 금액을 먼저 적용하고 초과분을 미수금으로 기록합니다.

TIEAT은 결제·충전·송금 서비스가 아니며 고객 자금을 보관하거나 이동시키지 않습니다.

## MVP flow

```text
QR mobile or store tablet entry
  → PENDING meal usage
  → store staff confirmation
  → CONFIRMED ledger entry
  → prepaid allocation and receivable
```

현재 저장소에는 다음 backend 기반이 구현되어 있습니다.

- 매장·식대 계약 범위를 가진 `MealUsage` 도메인 모델
- `PENDING → CONFIRMED` 상태 전이와 확인 이니셜
- 선불 우선 적용 및 초과 미수금 계산
- Spring Data JPA adapter와 Flyway migration
- Testcontainers PostgreSQL 통합 테스트

REST API, 인증된 확인 흐름, `CONFIRMED` 영속화, SSE와 Next.js PWA는 아직 구현 중입니다.

## Technology

- Java 25 (BellSoft Liberica)
- Spring Boot 4.1
- Gradle Kotlin DSL
- Spring MVC, Spring Security, Spring Data JPA
- PostgreSQL 18, Flyway, Testcontainers
- Docker Compose

Backend는 하나의 배포 단위를 유지하는 modular monolith입니다. 핵심 업무 규칙은 Spring과 JPA에 의존하지 않는 domain package에 두고 application use case와 adapter가 외부 기술을 연결합니다.

## Project structure

```text
apps/api
├── src/main/java/com/tieat
│   ├── ledger
│   │   ├── domain
│   │   ├── application
│   │   └── adapter
│   ├── partnership
│   └── store
└── src/main/resources/db/migration
```

## Run locally

SDKMAN을 사용하는 경우 프로젝트 SDK를 적용합니다.

```bash
sdk env
docker compose up -d postgres
./gradlew :apps:api:bootRun --args='--spring.profiles.active=local'
```

기본 API 주소는 `http://localhost:8080`이며 health endpoint는 다음과 같습니다.

```text
GET /actuator/health
```

### Public QR abuse controls

공개 QR 생성 요청은 기본적으로 client IP당 1분에 15건으로 제한되고, 요청 본문은 16KB를 넘을 수 없습니다. 여러 API 인스턴스가 같은 PostgreSQL 제한 상태를 공유합니다.

- `TIEAT_SECURITY_PUBLIC_QR_CREATE_ENABLED=false`: 사고 대응 시 생성 요청을 즉시 503으로 차단합니다.
- `TIEAT_SECURITY_PUBLIC_QR_CREATE_REQUESTS_PER_MINUTE`: 분당 허용 건수를 조정합니다.
- `TIEAT_SECURITY_PUBLIC_QR_CREATE_TRUSTED_PROXY_CIDRS`: API에 직접 연결되는 검증된 reverse proxy CIDR만 쉼표로 지정합니다. 비워 두면 `X-Forwarded-For`를 신뢰하지 않고 직접 연결 IP를 사용합니다.

운영에서는 `security_event=public_qr_create_rate_limited` 로그 발생률을 경보로 연결합니다. 이 이벤트에는 QR token이나 client IP가 기록되지 않습니다. 프록시 CIDR을 잘못 넓히면 IP 위조가 가능하므로 실제 배포 경로에서 확인한 범위만 설정해야 합니다.

### Azure Container Apps deployment template

- Web/API의 실제 `minReplicas`는 한국시간 07:55에 각각 1, 00:00에 각각 0으로 전환합니다. 최대 1개와 HTTP 규칙은 유지하여 새벽 장부 조회에도 다시 기동합니다. `container-apps.bicep`의 `initialMinReplicas`는 최초 배포 기본값 1이며, 재배포에는 현재 시간대의 값(야간 0/주간 1)을 전달해야 합니다. 앱만 배포하면 시간 예약은 동작하지 않습니다.
- 앱 생성 후 `scale-schedule.bicep`를 별도로 배포하면 동일 Web 이미지의 운영 스크립트를 실행하는 예약 Job 두 개가 만들어집니다(UTC `55 22 * * *`, `0 15 * * *`). 별도 상시 서버는 없으며 Job 실행 시간은 과금 대상입니다. 스케줄러 identity의 앱 read/write 권한은 대상 두 앱에만 부여합니다. `minReplicas`만 수정하는 RBAC 권한은 없으므로 이 identity는 앱 설정도 변경할 수 있는 민감한 운영 권한입니다. Web 이미지를 갱신하면 Job 이미지도 같은 검증된 digest로 갱신합니다.
- 최소 개수 변경은 새 revision을 만들므로 5분 예열은 준비 완료 보장이 아닙니다. 최초 적용 전 두 Job의 수동 실행, 최신 ready revision·설정 보존, 08시 QR 요청과 야간 장부 조회를 실환경에서 확인해야 합니다. 전환 실패 시 기존 revision이 남을 수 있어 Job 실패 감시도 필요합니다. DB와 독립 retention Job에는 이 일정이 적용되지 않습니다.
- 실제 0 replica이면 앱 컴퓨팅 요금은 없고, 주간 실제 min=1에서는 HTTP 요청 없음·낮은 CPU/네트워크 사용 등 조건을 충족할 때 유휴 요금 대상이 됩니다. 기존 cron floor와 달리 유휴 요금 적용이 가능한 구성이지 전 시간 할인 보장은 아닙니다. 이전 USD 33.48(컴퓨팅), USD 39~40(ACR 등 포함)은 하루 16시간 활성 요금 기준의 참고 추정이며 새 정책의 확정 견적이 아닙니다. 실제 비용은 5분 예열·활성/유휴 비중·새벽 요청·Job·로그·네트워크와 무료 할당량 적용 여부로 재확인합니다. [Azure 과금 기준](https://learn.microsoft.com/en-us/azure/container-apps/billing)
- Web 이미지는 Next.js standalone 산출물과 정적 파일만 포함하며, Next.js 시작 전 로그 필터를 로드해 QR 경로의 토큰·query·fragment를 마스킹합니다. 이 필터는 Web 프로세스 console 로그 대상이며 Azure ingress·외부 APM 로그는 별도로 확인해야 합니다.

### Retention worker

고객 이름 익명화 worker는 retention profile의 non-web 프로세스로 실행합니다.

```bash
./gradlew :apps:api:bootRun --args='--spring.profiles.active=retention'
```

기본 실행 시각은 매일 03:00(Asia/Seoul)이며 `TIEAT_CUSTOMER_NAME_ANONYMIZATION_CRON`으로 override할 수 있습니다. 단일 worker와 로그 실패·success count alert는 외부 플랫폼에서 연결해야 하며, 현재 저장소에는 그 증거가 없습니다.

## Verify

전체 backend 검증:

```bash
./gradlew :apps:api:check --no-daemon
```

PostgreSQL 통합 테스트에는 Docker가 필요합니다.
