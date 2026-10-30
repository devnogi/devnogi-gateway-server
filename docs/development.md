# 개발과 검증

Java 21과 저장소의 Gradle wrapper를 사용한다. 처음 clone한 뒤 `./gradlew installGitHooks`를 실행해 이 저장소의 hook을 설치한다. Windows에서는 `gradlew.bat`을 사용한다.

- `pre-commit`: staged whitespace/conflict marker와 전체 Java 소스의 Spotless 포맷 및 import를 검사한다. 실패하면 `./gradlew spotlessApply`를 직접 실행하고 diff를 검토한 후 필요한 변경만 stage한다.
- `pre-push`: `./gradlew verify`로 전체 테스트, Spotless, PMD와 bootJar를 검사한다. 파일을 자동 수정하지 않는다.
- 수동 검사: `./gradlew lint --no-daemon`, 전체 검증: `./gradlew verify --no-daemon`.

hook은 `--no-verify`로 우회할 수 있지만 PR CI의 `verify`가 같은 정적 검사를 다시 실행한다. PMD는 미사용 local과 private 멤버를 검사하며 동적 호출 여부를 완전히 증명하지 못한다.

## CI와 배포

PR과 수동 검증은 고정된 공통 Spring workflow의 base 프로필을 사용한다. 표준 verify, secret-scan, Docker 빌드와 합성 환경의 운영 Compose 렌더링을 실행한다. 새 필수 검사 context는 실제 PR에서 확인한 후 브랜치 보호에 적용한다.

운영 workflow는 main push 또는 main의 수동 실행에서만 동작하며 공통 검증 통과 후 `gateway-v<Gradle 버전>` 이미지를 게시하고 prod2 서버에 digest로 배포해 상태를 확인한 뒤 GitHub Release를 생성한다. production Environment와 배포 동시성은 공통 workflow가 관리한다. 운영 Compose의 `IMAGE_REF`는 필수이며 실행 환경 파일에 직접 설정하지 않는다. 승인된 PR의 main 병합이 운영 배포 승인이다.
