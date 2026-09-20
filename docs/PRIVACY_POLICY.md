# 개인정보 처리방침 (초안) — Janggi AI

> **사용 방법**: 아래 본문을 공개 웹페이지(예: GitHub Pages, 개인 사이트)에 그대로 게시하고 그 URL을
> Play Console → 정책 → 앱 콘텐츠 → 개인정보처리방침에 입력한다. Google Play는 **데이터를 수집하지 않는 앱에도**
> 개인정보처리방침 제출을 요구하며, URL은 공개 접근 가능·지역 제한 없음·**PDF 불가**·편집 불가여야 하고,
> 스토어 등록정보의 개발자 이름 또는 앱 이름이 본문에 나와야 한다. `[ ]` 안의 자리표시자를 채운 뒤 게시할 것.
> 아래 내용은 현재 구현(INTERNET 권한 없음, 계정·광고·분석 SDK 없음, 외부 전송 없음)과 일치한다.
> **구현이 바뀌면(예: 광고, 클라우드 저장, 크래시 리포트 SDK 추가) 이 문서와 Play 데이터 보안 양식을 함께 고쳐야 한다.**

---

## Janggi AI 개인정보 처리방침

**앱 이름**: Janggi AI (임시 이름; 출시 시 확정 이름으로 교체)
**개발자**: [개발자 또는 조직 이름 — Play 등록정보와 동일하게]
**연락처**: [이메일 주소]
**최종 수정일**: [YYYY-MM-DD]

### 1. 요약

Janggi AI는 개인정보를 **수집하지 않고, 저장하지 않으며, 제3자에게 전송하지 않습니다.**
앱은 인터넷 접근 권한을 요청하지 않으며 네트워크 통신을 하지 않습니다.

### 2. 앱이 하는 일

Janggi AI는 장기(한국 장기) 분석·대국 앱입니다. 모든 계산(수 생성, 엔진 분석, 승률 추정)은 사용자의
기기 안에서 실행됩니다. 서버가 없습니다.

### 3. 수집하는 정보

없습니다. 앱은 다음 어느 것도 수집하지 않습니다.

* 이름, 이메일, 전화번호 등 개인 식별 정보
* 위치 정보
* 기기 식별자(광고 ID 포함)
* 사용 통계, 분석(analytics), 크래시 리포트
* 연락처, 사진, 파일 등 기기 내 다른 데이터

### 4. 기기 내부에만 저장되는 데이터

다음 데이터는 사용자의 기기 내부 저장공간(앱 전용 영역)에만 저장되며 앱 외부로 전송되지 않습니다.

* **설정값**: 분석 강도, 난이도, 규칙 옵션, 테마 등 앱 설정
* **기보**: 사용자가 저장한 대국/분석 기록(수순과 결과)

이 데이터는 앱을 삭제하면 함께 삭제됩니다. 앱은 Android 자동 백업 대상에서 이 데이터를 제외합니다
(`allowBackup=false`).

### 5. 권한

앱은 인터넷(INTERNET), 위치, 카메라, 마이크, 연락처, 저장공간 등 **어떠한 위험 권한도 요청하지 않습니다.**

### 6. 제3자 서비스

앱에는 광고 SDK, 분석 SDK, 로그인/계정 시스템, 결제 SDK가 포함되어 있지 않습니다.
앱은 Fairy-Stockfish 장기 엔진(GNU GPL v3 이상)을 포함하지만, 이 엔진은 기기 안에서만 실행되며 데이터를
전송하지 않습니다.

### 7. 아동

앱은 개인정보를 수집하지 않으므로 아동의 정보도 수집하지 않습니다.

### 8. 계정 삭제

앱에는 계정 기능이 없으므로 계정 삭제 절차가 필요하지 않습니다. 저장된 기보와 설정은 앱 안에서 삭제하거나
앱을 삭제함으로써 제거할 수 있습니다.

### 9. 변경

이 방침이 변경되면 이 페이지에 게시하고 최종 수정일을 갱신합니다. 데이터 처리 방식이 실제로 바뀌는 경우
앱 업데이트와 Play 스토어의 데이터 보안 정보도 함께 갱신합니다.

### 10. 문의

[이메일 주소]

---

## Privacy Policy — Janggi AI (English)

**App**: Janggi AI · **Developer**: [name as shown on Google Play] · **Contact**: [email] · **Last updated**: [YYYY-MM-DD]

Janggi AI does not collect, store or transmit any personal data. The app requests no INTERNET permission
and performs no network communication. All analysis runs on your device; there is no server.

**Data stored only on your device**: app settings and game records you choose to save. They stay in the
app's private storage, are excluded from Android backup, and are deleted when you uninstall the app.

**Permissions**: none of the dangerous permissions (internet, location, camera, microphone, contacts, storage).

**Third parties**: no ads, analytics, accounts or payment SDKs. The bundled Fairy-Stockfish engine
(GNU GPL v3 or later) runs locally and sends nothing.

**Children**: no data is collected from anyone, including children. **Accounts**: the app has no accounts,
so no account-deletion process applies. **Changes** to this policy are posted on this page with a new date.

---

## Play Console 데이터 보안(Data safety) 양식 — 이 방침과 일치하는 답

| 질문 | 답 |
|---|---|
| 앱이 필수 사용자 데이터 유형을 수집하거나 공유합니까? | **아니요** |
| 앱의 모든 사용자 데이터가 전송 중 암호화됩니까? | 해당 없음(전송 없음) |
| 사용자가 데이터 삭제를 요청할 수 있는 방법을 제공합니까? | 해당 없음(수집 없음) |

근거: `android/app/src/main/AndroidManifest.xml`에 INTERNET 권한 없음, 의존성에 광고/분석 SDK 없음
(`android/gradle/libs.versions.toml`), 기보는 `filesDir/records`, 백업 제외 규칙
(`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
