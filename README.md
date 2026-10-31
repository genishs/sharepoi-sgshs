# sharepoi-sgshs — 내주변 위치찾기

Kotlin + Kakao Maps SDK v2 기반 안드로이드 앱. 내 위치를 지도에 표시하고, 공유 시트로 위치를 공유하며, 주변 화장실·편의시설을 검색하고, 카카오 로드뷰로 현장을 미리 확인할 수 있다.

- 패키지: `com.sgshs.sharepoi`
- 언어/SDK: Kotlin(AGP 9 내장 Kotlin), Kakao Maps SDK v2 (2.14.1 — `app/build.gradle.kts`의 `kakaoMapSdkVersion`)
- minSdk 26, targetSdk 36, compileSdk 36

## 주요 기능

1. **내 위치 표시** — `FusedLocationProviderClient`로 실제 기기 GPS 위치를 받아 지도 카메라와 마커를 이동한다. 위치를 얻지 못하면(권한 거부·위치 꺼짐 등) 처음 보이는 지역만 마곡나루역 부근으로 두고 현재 위치 마커는 찍지 않는다. 🎯 버튼은 권한·위치 설정 안내(위치 켜기 포함)를 띄운다.
2. **위치 공유** — 「위치 공유」 버튼을 누르면 그 시점에 현재 위치를 다시 확인하고, 안드로이드 공유 시트(`ACTION_SEND`, `text/plain`)로 위치 문구와 지도 링크를 보낸다. 카카오톡·문자·메일 등 기기에 있는 앱을 이용자가 고른다. 현재 위치를 얻지 못하면(권한 거부·위치 꺼짐 등) 현재 위치로 보내지 않고, 원하면 「지도에서 고른 위치」로 구분해 보낼 수 있다. 앱이 직접 발송하지 않으며 문자 관련 권한이 없다.
3. **주변 화장실 4소스 통합 검색** — 지도 중심 좌표 기준 반경 2km 내에서 카카오 로컬 API를 4가지 방식으로 조회해 중복 제거 후 합쳐서 보여준다: 키워드 "공중화장실", 키워드 "개방화장실", 카테고리 `PO3`(공공기관), 카테고리 `OL7`(주유소·충전소 — 개방 화장실을 갖춘 경우가 많음). 핀은 출처에 따라 🚽(화장실)·🏛️(공공기관)·⛽(주유소)로 구분하고, 공공기관·주유소 상세에는 화장실 이용 여부를 현장에서 확인하라는 안내가 붙는다.
4. **주변 편의시설 검색** — 편의점·카페·음식점·주차장·약국·은행·지하철역·병원 중 하나를 골라 반경 2km 내에서 최대 45곳을 보여준다. 핀에 카테고리 아이콘이 그려진다.
5. **핀 상세·로드뷰·전화** — 핀을 누르면 이름·도로명/지번 주소·전화번호를 보여 주고, 「로드뷰 보기」로 카카오 로드뷰를, 「전화 걸기」로 전화 앱의 다이얼 화면을 연다(바로 걸지 않는다). 새 검색 결과가 나오면 앞 검색의 핀은 지워진다. 검색이 끝나기 전에 다시 누르면 새 요청을 보내지 않는다.

## 빌드

### 사전 준비 — `local.properties`

프로젝트 루트에 `local.properties` 파일을 만들고 아래 값을 채운다 (이 파일은 `.gitignore`에 포함되어 있으므로 **절대 커밋하지 않는다**):

```properties
sdk.dir=/path/to/Android/Sdk
KAKAO_MAP_API_KEY=<카카오 네이티브 앱 키>
```

빌드에 들어가는 카카오 키는 **네이티브 앱 키 하나**다. 지도 SDK 인증과 주변 검색(카카오 Local API) 모두 이 키를 쓰고, Local API 요청에는 `KA` 헤더로 앱의 서명 키 해시와 패키지명을 함께 보낸다. 그래서 카카오 개발자 콘솔의 Android 플랫폼에 패키지명과 키 해시(디버그 키·Play 앱 서명 키)가 등록되어 있어야 지도와 검색이 동작한다. REST API 키는 키 해시로 묶이지 않아 앱에 넣지 않는다. 키가 없으면 빌드는 되지만 `DUMMY_KEY`로 채워져 지도·검색이 동작하지 않는다.

카카오 지도 SDK 버전은 `app/build.gradle.kts`의 `kakaoMapSdkVersion` 한 곳에서 정하고, `KA` 헤더의 `sdk/` 값도 여기서 가져온다.

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

패키지명 `com.sgshs.sharepoi`, 앱 이름 "내주변 위치찾기"로 Google Play 프로덕션 트랙에 출시되어 있다(첫 출시 1.0). 버전 번호·태그·제출 체크리스트는 [RELEASING.md](RELEASING.md)를 따른다.
