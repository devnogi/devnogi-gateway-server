# 개발과 검증

Java 21과 저장소의 Gradle wrapper를 사용한다. 처음 clone한 뒤 `./gradlew installGitHooks`를 실행해 이 저장소의 hook을 설치한다. Windows에서는 `gradlew.bat`을 사용한다.

- `pre-commit`: staged whitespace/conflict marker와 전체 Java 소스의 Spotless 포맷 및 import를 검사한다. 실패하면 `./gradlew spotlessApply`를 직접 실행하고 diff를 검토한 후 필요한 변경만 stage한다.
- `pre-push`: `./gradlew verify`로 전체 테스트, Spotless, PMD와 bootJar를 검사한다. 파일을 자동 수정하지 않는다.
- 수동 검사: `./gradlew lint --no-daemon`, 전체 검증: `./gradlew verify --no-daemon`.

hook은 `--no-verify`로 우회할 수 있지만 PR CI의 `verify`가 같은 정적 검사를 다시 실행한다. PMD는 미사용 local과 private 멤버를 검사하며 동적 호출 여부를 완전히 증명하지 못한다.
