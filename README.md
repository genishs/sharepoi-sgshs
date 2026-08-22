# sharepoi-sgshs — 내주변 위치찾기

Kotlin + Kakao Maps SDK v2 기반 안드로이드 앱. 내 위치를 지도에 표시하고, SMS로 위치를 공유하며, 주변 화장실을 여러 소스에서 통합 검색하고, 카카오 로드뷰로 현장을 미리 확인할 수 있다.

- 패키지: `com.sgshs.sharepoi`
- 언어/SDK: Kotlin, Kakao Maps SDK v2 (2.14.1)
- minSdk: 26 (targetSdk 34, compileSdk 34)

## 주요 기능

1. **내 위치 표시** — `FusedLocationProviderClient`로 실제 기기 GPS 위치를 받아 지도 카메라와 마커를 이동한다. 위치를 가져오기 전(권한 거부, 최초 fix 대기 등)에는 마고나루역 좌표를 기본값으로 표시한다.
2. **SMS로 위치 전송** — 입력한 전화번호로 현재 위치 좌표가 담긴 문자메시지를 `SmsManager`를 통해 직접 전송한다.
3. **주변 화장실 4소스 통합 검색** — 지도 중심 좌표 기준 반경 2km 내에서 카카오 로컬 API를 4가지 방식으로 조회해 중복 제거 후 합쳐서 보여준다: 키워드 "공중화장실", 키워드 "개방화장실", 카테고리 `PO3`(공공기관), 카테고리 `OL7`(주유소·충전소— 개방 화장실을 갖춘 경우가 많음). 마커를 탭하면 화장실 이름·주소·전화번호와 함께 로드뷰 바로가기 버튼이 라벨로 표시된다.
4. **로드뷰** — 검색된 지점의 라벨 클릭 시 해당 위치의 카카오 로드뷰를 바로 열어 현장 모습을 확인할 수 있다.

## 빌드

### 사전 준비 — `local.properties`

프로젝트 루트에 `local.properties` 파일을 만들고 아래 값을 채운다 (이 파일은 `.gitignore`에 포함되어 있으므로 **절대 커밋하지 않는다**):

```properties
sdk.dir=/path/to/Android/Sdk
KAKAO_MAP_API_KEY=<카카오 네이티브 앱 키>
KAKAO_REST_API_KEY=<카카오 REST API 키>
```

`KAKAO_REST_API_KEY`를 생략하면 `KAKAO_MAP_API_KEY` 값을 대신 사용한다(둘 다 없으면 빌드는 되지만 `DUMMY_KEY`로 채워져 지도·검색이 동작하지 않는다).

### 디버그 빌드 설치

```bash
./gradlew installDebug
```

### 릴리스 AAB 빌드 (Play Console 업로드용)

서명된 릴리스 App Bundle은 `./build-release.sh` 스크립트로 만든다. 서명 정보는 소스에 커밋하지 않고 환경변수로 주입한다:

| 환경변수 | 설명 | 기본값 |
| --- | --- | --- |
| `SHAREPOI_KEYSTORE` | 업로드 키스토어(.jks) 경로 | `~/sharepoi-upload.jks` |
| `SHAREPOI_KEYSTORE_PW` | 키스토어 비밀번호 | 스크립트 실행 시 프롬프트로 입력(화면에 표시되지 않음) |
| `SHAREPOI_KEY_ALIAS` | 키 별칭 | `upload` |
| `SHAREPOI_KEY_PW` | 키 비밀번호 | 미지정 시 `SHAREPOI_KEYSTORE_PW`와 동일하게 취급 |
| `ANDROID_HOME` | Android SDK 경로 | `~/Android/Sdk` |

```bash
./build-release.sh
```

빌드 결과물은 `app/build/outputs/bundle/release/app-release.aab`에 생성된다. `SHAREPOI_KEYSTORE`가 설정되지 않으면 서명 없이 빌드된다(로컬 확인용).

## 에뮬레이터에서 GPS 위치 테스트

에뮬레이터는 실제 GPS가 없으므로 아래 명령으로 가짜 위치를 주입해 "내 위치 표시" 기능을 확인한다:

```bash
adb emu geo fix <경도> <위도>
```

예: `adb emu geo fix 127.0276 37.4979` (강남역 부근)

## 개인정보처리방침

Google Play 등록용 개인정보처리방침은 GitHub Pages(`docs/` 디렉터리)로 게시되어 있다: https://genishs.github.io/sharepoi-sgshs/privacy.html

## Play Console 등록 상태

패키지명 `com.sgshs.sharepoi`, 앱 이름 "내주변 위치찾기"로 등록 진행 중이며 아직 정식 배포(공개 출시) 전 단계다.
