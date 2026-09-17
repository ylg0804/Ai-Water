# 灌区水情智能监测平台前端

Vue 3 前端通过 HTTP API 和 WebSocket 使用 Java 后端提供的数据。浏览器不直接连接 MySQL，也不保存数据库密码、摄像头 App Key 或 App Secret。

## 启动

先确认 Java 后端已经运行，再双击 `start-frontend.cmd`，或在本目录执行：

```powershell
npm.cmd ci
npm.cmd run dev
```

访问 <http://127.0.0.1:5173>，使用后端提供的管理员账号登录。开发环境需要 Node.js 22.12 或更新版本。

如需在本机启动仓库中已有的后端，第一次使用时先复制配置模板并填写本机 MySQL 账号：

```powershell
Copy-Item .backend.example.properties .backend.local.properties
```

需要视频和抓拍时，再在 `.backend.local.properties` 中填写后端使用的萤石云 App Key 与 App Secret。该本地文件已被 Git 忽略，不能提交。配置完成后双击 `start-backend.cmd`；它会调用 `scripts/start-backend.ps1` 校验 JDK 21、构建并启动仓库中已有的后端，不修改后端源码。

前后端命令窗口需要保持打开，分别按 `Ctrl+C` 可停止服务。若后端同学已经部署了可访问的服务，则不需要本地后端配置和 `start-backend.cmd`，按下一节配置 `.env.local` 即可。

## 开发代理

浏览器始终请求同源路径，Vite 在开发环境中将请求转发给后端：

| 浏览器请求 | 默认后端地址 |
| --- | --- |
| `/api/...` | `http://127.0.0.1:8080/api/...` |
| `/ws/realtime` | `ws://127.0.0.1:8080/ws/realtime` |

后端位于其他主机或端口时，把 `.env.example` 复制为 `.env.local`，只修改服务地址并重启前端：

```dotenv
BACKEND_URL=http://192.168.1.20:8080
```

`BACKEND_URL` 只供 Vite 开发代理读取，不会打包到浏览器代码。账号、密码和摄像头密钥不要写进前端环境变量。

## 代码分层

前端依赖方向如下：

```text
pages / components
├─ composables ───────┬─→ services/platform
│                     └─→ services/api
└─ services/platform ────→ services/api
                           └─→ services/api/client
```

- `pages` 和 `components` 只负责界面与用户操作。
- `composables` 管理历史查询、视频播放和对象 URL 的页面状态。
- `domain` 保存业务模型、告警元数据和设备参数规则，不发起网络请求。
- `services/platform/store.ts` 汇总设备、测量、告警与实时推送状态，供页面统一使用。
- `services/api` 按后端领域拆分接口，并在边界处校验、转换响应字段。
- `services/api/client.ts` 统一处理 Bearer token、超时、取消、响应解包和未授权状态。
- `services/session.ts` 负责登录会话；业务数据不写入浏览器本地数据源。

运行代码与测试代码分开存放：

```text
frontend/
├─ src/                  # 浏览器运行代码
│  ├─ assets/styles/     # 全局样式与主题变量
│  ├─ pages/
│  ├─ components/
│  ├─ composables/
│  ├─ domain/
│  ├─ router/            # 页面路由与登录守卫
│  ├─ services/
│  │  ├─ api/            # 后端接口与数据契约
│  │  └─ platform/       # 业务状态与实时连接
│  └─ utils/
└─ tests/                # 测试代码，按 src 的层次镜像组织
   ├─ composables/
   ├─ services/
   │  ├─ api/
   │  └─ platform/
   └─ domain/
```

`src` 不放 `*.test.ts`；生产类型检查只覆盖 `src` 和 Vite 配置，Vitest 从 `tests` 目录发现测试。

页面和组件不直接拼接后端地址，也不解析后端的 snake_case 字段。设备、测量、告警、视频地址和截图都来自后端接口。

## 已对接接口

| 功能 | 请求 |
| --- | --- |
| 登录 | `POST /api/login` |
| 设备管理 | `GET/POST /api/device`、`PUT/DELETE /api/device/{id}` |
| 最新测量 | `GET /api/measurement/latest?deviceId=` |
| 历史测量 | `GET /api/measurement/history?deviceId=&start=&end=` |
| 历史导出 | `GET /api/measurement/export?deviceId=&start=&end=` |
| 告警与处理 | `GET /api/alarm`、`POST /api/alarm/{id}/handle` |
| 告警截图 | `GET /api/alarm/{id}/snapshot` |
| 摄像头运行状态 | `GET /api/ezviz/measurements/latest?serial=` |
| 视频播放地址 | `GET /api/ezviz/play-url` |
| 视频抓图 | `GET /api/ezviz/capture` |
| 实时通知 | `WS /ws/realtime` |

接口可访问只说明前端已经连到 Java 服务。摄像头是否在线、能否生成播放地址、是否已有测量记录，取决于后端运行环境中的摄像头授权、设备接入、推理任务和数据库数据。后端返回空值或错误时，前端显示缺失状态和对应错误，不生成业务数据补位。

## 生产部署

构建后的静态资源继续请求同源的 `/api` 和 `/ws/realtime`。生产 Web 服务器需要把这两个路径转发到 Java 后端，其中 `/ws/realtime` 必须支持 WebSocket 连接升级。不要把 Java 后端地址硬编码进前端产物。

页面使用 hash 路由，因此静态服务器无需为页面路由额外回退。部署站点使用 HTTPS 时，后端返回的视频播放地址也必须使用 HTTPS，避免浏览器拦截混合内容。

## 检查与构建

```powershell
npm.cmd test
npm.cmd run build
npm.cmd run preview
```

`build` 会先执行 TypeScript 检查，再生成 `dist/`。`preview` 继续使用开发代理配置，仅用于本地检查。
