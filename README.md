# StarLight Web Server

StarLight 프로젝트(스크립트)가 HTTP 요청을 처리할 수 있게 해 주는 웹 서버 플러그인입니다.
Ktor(CIO 엔진) 위에서 동작하며, 홈 화면용 상태 위젯을 함께 제공합니다.

**StarLight nightly(PluginCore 1.0.0) 이상이 필요합니다.** 정식 앱(0.3.4b)에서는 불러오지 않습니다.

**필수 의존성:** [Ktor 플러그인](https://github.com/mooner1022/starlight-ktor)(`ktor@3.6.0`). Ktor는 이 플러그인에 포함되지 않고 Ktor 플러그인에서 불러오며,
Ktor 로그는 `Ktor/…` 태그로 남습니다.

## 스크립트에서 사용하기

프로젝트 설정의 이벤트 목록에서 **웹 서버 설정**(`webserver.server.setup`)을 허용하고 아래 함수를 작성하세요.
프로젝트가 컴파일될 때마다 호출되며, 호출 전에 이전 컴파일에서 등록한 라우트는 모두 제거됩니다.
NeonJS 프로젝트는 ES 모듈로 실행되므로 함수를 반드시 `export` 해야 합니다(Rhino에서는 `export` 없이 작성).

```js
export function onWebServerSetup(server) {
    // 반환값이 응답 본문이 됩니다. 객체/배열은 JSON, 문자열은 text/plain.
    server.get("/hello/:name", (req) => ({ hello: req.params.name }));

    // res로 직접 응답할 수도 있습니다.
    server.post("/echo", (req, res) => {
        res.status(201).header("X-Length", req.bodySize).send(req.body);
    });

    // async 함수(또는 Promise 반환)로 비동기 처리 (NeonJS)
    server.get("/weather", async (req, res) => {
        const r = await fetch("https://example.com/api");
        res.json(await r.json());
    });

    // 프로젝트 디렉터리의 public 폴더를 /files 아래로 제공
    server.serveStatic("/files", "public");
}
```

`server`를 변수에 저장해 두면 나중에 라우트를 추가/제거할 수도 있습니다. 재컴파일 이후에는 이전 `server` 객체로 등록한 라우트가 무시됩니다.

### server (라우터)

| 메서드 | 설명 |
|---|---|
| `get/post/put/patch/delete/options(path, handler)` | 해당 메서드의 라우트 등록. GET 라우트는 HEAD 요청도 처리합니다. |
| `all(path, handler)` | 모든 메서드 |
| `route(method, path, handler)` | 임의 메서드 |
| `serveStatic(prefix, directory)` | 디렉터리의 파일 제공 (상대 경로는 프로젝트 디렉터리 기준, 디렉터리 요청 시 `index.html`) |
| `remove(method, path)`, `clear()` | 라우트 제거 |
| `routes`, `running`, `port`, `urls` | 등록된 라우트 목록, 서버 상태, 포트, 접속 주소 목록 |

경로 패턴: `/users/:id`는 한 세그먼트를 `req.params.id`로, 끝의 `*`는 나머지 경로 전체를 `req.params["*"]`로 받습니다.
여러 라우트가 일치하면 더 구체적인 경로(고정 세그먼트 > `:param` > `*`)가 우선합니다.

### req

`method`, `path`, `url`(쿼리 포함), `params`, `query`(첫 번째 값), `queryAll(name)`, `headers`(소문자 키), `header(name)`,
`body`(문자열), `bodyBytes()`, `bodySize`, `form`(`application/x-www-form-urlencoded`), `contentType`, `ip`, `receivedAt`

### res

| 메서드 | 설명 |
|---|---|
| `status(code)`, `header(name, value)`, `type(contentType)` | 체이닝 가능 |
| `send(body)` | 문자열은 그대로, 객체/배열은 JSON |
| `json(value)`, `text(string)`, `html(string)` | |
| `file(path)` | 파일 전송 (상대 경로는 프로젝트 디렉터리 기준) |
| `redirect(url[, status])` | 기본 302 |
| `end()` | 본문 없이 응답 (상태 코드 기본 200, 필요하면 `status(204)`) |
| `sent` | 이미 응답했는지 여부 |

처음 호출된 전송 메서드만 적용됩니다. 핸들러가 아무것도 반환하지 않고 응답하지도 않으면 나중에 `res`로 응답할 때까지 기다리며,
제한 시간(설정 > 요청)이 지나면 504로 응답합니다. NeonJS에서 비동기 작업은 `async` 함수로 작성하거나 Promise를 반환하세요.
핸들러에서 예외가 발생하면 500으로 응답하고 프로젝트 로그에 오류를 남깁니다.

### 오류 응답

서버가 직접 응답하는 경우 `{"status": 404, "error": "Not Found"}` 형식의 JSON을 보냅니다.
401(토큰), 404, 405(`Allow` 헤더 포함), 413(본문 크기 초과), 503(프로젝트 비활성/미컴파일), 504(시간 초과).

## 설정

- **일반**: 포트(1024–65535, 기본 8080), 접속 허용 범위(모든 네트워크 / 이 기기에서만), 자동 시작
- **제어**: 서버 상태와 접속 주소(실시간, 눌러서 주소 복사), 시작/중지, 재시작, 최근 요청 목록
- **보안**: 접근 토큰 필수 여부와 토큰(필수일 때만 표시, SHA-256 해시로만 저장, `Authorization: Bearer <토큰>` 또는 `?token=`), CORS 허용
- **요청**: 최대 본문 크기, 핸들러 제한 시간
- **로그**: 모든 요청 로그 표시

## 위젯

**웹 서버** 위젯(2×1 ~ 8×4)은 서버 상태, 접속 주소, 요청 수, 라우트 수, 평균 처리 시간을 보여 주고,
3행 이상에서는 최근 30분간 분당 요청 그래프와 최근 요청 목록을 표시합니다(위젯 설정에서 끌 수 있음). 누르면 주소가 복사됩니다.

## 엔진 호환성

- **NeonJS (주 대상)**: `onWebServerSetup` 이벤트로 사용합니다. 핸들러는 프로젝트의 NeonJS 컨텍스트에서 실행되며 async 핸들러를 지원합니다.
- **Rhino (레거시)**: 같은 이벤트를 쓸 수 있고, 전역 `WebServer` API로 최상위 코드에서 바로 등록할 수도 있습니다.

전역 `WebServer` API는 플러그인 Api로 등록되어 있지만, 현재 StarLight의 NeonJS는 호스트 내장 패키지의 Api만 노출하므로
NeonJS에서는 아직 보이지 않습니다. NeonJS의 Api 노출이 블랙리스트 방식으로 바뀌면 코드 수정 없이 NeonJS에서도 쓸 수 있습니다.
API 객체와 이벤트는 같은 라우터를 공유하므로(컴파일 시작 시 API가 만든 라우터를 컴파일 후 이벤트가 이어받음) 둘을 섞어 써도 됩니다.

```js
// Api 접근이 가능한 엔진에서
WebServer.get("/ping", () => "pong");
```

## 빌드

```sh
./gradlew :app:assembleReleaseSlp   # app/build/outputs/starlight/release/webserver-release.slp
./gradlew :app:testReleaseUnitTest  # 라우팅, NeonJS 연동, HTTP 통합 테스트
./gradlew :app:deployReleaseSlp -Pstarlight.device=<serial>
```

Ktor는 `compileOnly`이며 런타임에는 Ktor 플러그인에서 불러옵니다. Ktor 버전을 올릴 때는
`app/build.gradle`의 Ktor 버전, `starlight.json`의 `dependency`, Ktor 플러그인 버전을 함께 맞추세요.

`app/libs`의 `PluginCore-release.aar`, `ConfigDSL-release.aar`은 StarLight 저장소에서 빌드한 것입니다
(`./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease`, nightly 브랜치의 PluginCore 1.0.0).

`assets/` 아래 파일(아이콘 등)을 바꾸면 `starlight.json`의 `assetRevision`을 올려야 합니다.
StarLight는 이 값이 바뀔 때만 에셋을 다시 풀어 두기 때문에, 그대로 두면 기존 설치본에 이전 에셋이 남습니다.
