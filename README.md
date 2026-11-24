# 规章制度合法性与冲突审查系统

基于 [OpenSpec](https://github.com/zhuzhaoyun/OpenSpec) 改造的第一版制度冲突审查系统。系统围绕“制度库 → 新建审查 → 条文对照 → 人工复核 → 报告归档”形成本地闭环，不再要求 OSS、Clawith 或外部 AI Agent 才能完成审查。

> 当前版本用于辅助发现疑似问题，不替代法务、制度管理部门或其他专业人员的最终审查结论。

## 功能特性

- **制度文本导入**：支持 `.md`、`.txt`，自动按“第一条、第二条……”拆分条文；兼容 UTF-8 BOM、全角条款数字，没有条款编号时按非空段落处理。
- **制度分类与效力**：区分上级文件和内部制度，维护现行/废止状态、生效日期和失效日期。
- **新制度对上级审查**：将待审条文与基准日有效的上级文件逐条比较。
- **新制度对内部审查**：检查待审条文与所选内部制度的规定差异。
- **新旧替换模拟**：旧制度用于新旧对照，同时从“替换后的现行体系”中排除，再比较其余有效制度。
- **存量制度复核**：将现有制度作为待审对象，与最新上级文件比较。
- **本地规则引擎**：识别相关条款中的金额、比例、期限差异和审批主体差异，支持中文数字、阿拉伯数字、全角数字及“元/万元”等单位归一比较。
- **证据链展示**：每个问题同时展示待审条款、对照制度、对照条款、双方原文、分析理由和修改建议。
- **人工复核**：支持确认问题、排除问题、恢复待复核和填写复核意见。
- **报告导出**：按人工复核状态导出 Markdown 审查报告。
- **用户隔离**：沿用 OpenSpec 的注册、登录和 JWT 鉴权，审查记录及复核操作校验归属。

## 当前边界

本系统是可运行的规则型 MVP，并非完整的语义合法性审查产品：

1. 仅支持文本型 `.md`、`.txt`，尚未实现 Word、PDF 和扫描件 OCR；
2. 当前规则主要覆盖数量、期限、金额、比例和较明确的审批主体表述，复杂修饰语及跨条款上下文仍需人工判断；
3. 暂不支持复杂语义矛盾、授权边界、例外条款的自动推理；
4. 暂未接入 RAGFlow 或大模型进行候选召回与语义精判；
5. 报告目前导出 Markdown，尚未生成 Word/PDF；
6. 制度文件由用户手动导入，系统不自动同步国家或上级政策；
7. 条文关联采用业务主题词和中文二元文本匹配，可能漏报或误报，必须人工复核。

后续完整建设路线见 [具体落地方案.md](具体落地方案.md)。

## 技术栈

| 层级 | 技术 |
|---|---|
| 前端 | Vue 3、TypeScript、Vite、Element Plus |
| 后端 | Java 17、Spring Boot 3.1.5、MyBatis-Plus |
| 数据库 | PostgreSQL 15、pgvector |
| 审查引擎 | Java 本地条文解析与规则比较 |
| 部署 | Docker Compose（数据库及后端）、Vite 开发服务器 |

## 项目结构

```text
apps/
├── web/                         # 制度库、审查任务、复核工作台
└── backend/
    ├── ai-doc-system-api/       # 请求 DTO
    ├── ai-doc-system-model/     # 数据实体
    ├── ai-doc-system-repository/# MyBatis-Plus Mapper
    ├── ai-doc-system-service/   # 制度导入、冲突审查、规则引擎
    └── ai-doc-system-rest/      # REST API 与数据库初始化
docs/examples/                   # 可直接导入的演示制度
script/backend/initial_schema.sql# PostgreSQL 完整初始化脚本
具体落地方案.md                  # 后续完整技术与建设方案
```

## 快速启动

### 环境要求

- JDK 17 或兼容的更新版本；
- Maven 3.8+；
- Node.js 20+；
- Docker 与 Docker Compose，或已有的 PostgreSQL 15 + pgvector；
- 推荐使用 UTF-8 终端，避免中文文件名和日志乱码。

### 1. 启动 PostgreSQL

在项目根目录执行：

```bash
cd apps/backend
docker compose up -d postgres
```

默认配置：

```text
地址：localhost:5433
数据库：doc_generator
用户名：archspec
密码：archspec_doc_pass
```

首次启动会执行 `ai-doc-system-rest/src/main/resources/db/init-schema.sql` 和 `init-data.sql`。

如果使用已有数据库，请自行创建数据库和 pgvector 扩展，并执行：

```text
script/backend/initial_schema.sql
```

已有 OpenSpec 数据库也使用该脚本进行幂等升级；操作生产数据前应先备份。

### 2. 构建并启动后端

```bash
cd apps/backend
mvn clean package
java -jar ai-doc-system-rest/target/ai-doc-system-rest-1.0.0.jar
```

后端默认地址：<http://localhost:8080/api/v1>。

开发模式也可使用：

```bash
mvn spring-boot:run -pl ai-doc-system-rest
```

如果本机尚未安装各模块，先执行一次 `mvn clean install -DskipTests`。

常用数据库环境变量：

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/doc_generator
SPRING_DATASOURCE_USERNAME=archspec
SPRING_DATASOURCE_PASSWORD=archspec_doc_pass
JWT_SECRET=请替换为足够长的随机字符串
```

`application.yml` 中的数据库初始化器默认开启，可重复执行幂等初始化。生产部署建议改为手动管理数据库升级，并设置：

```bash
APP_DATABASE_INIT_ENABLED=false
```

### 3. 启动前端

新开终端：

```bash
cd apps/web
npm install
npm run dev
```

访问 <http://localhost:5173>，注册账号并登录。Vite 会将 `/api` 代理到 `http://localhost:8080`。

## 演示完整流程

`docs/examples` 提供三份虚构演示制度：

1. `上级员工请假管理制度.md`；
2. `内部员工请假管理细则.md`；
3. `新员工请假管理办法.md`。

建议按以下步骤操作：

1. 进入“制度库”，点击导入按钮；
2. 将第一份文件以“上级文件”导入，第二份以“内部制度”导入；
3. 生效日期填写为当前日期或更早日期，状态保持“现行”；
4. 检查导入后的条款拆分是否正确；
5. 进入“制度冲突审查”，创建“新制度对上级文件”任务；
6. 上传第三份文件，选择第一份上级制度并开始审查；
7. 查看请假天数和审批主体的疑似差异，对照双方原文；
8. 填写复核意见，确认或排除问题，导出报告；
9. 再创建“新旧替换及整体审查”，同时选择被替换的旧制度和替换后仍需保留的制度，并明确勾选旧制度，观察“旧版本对照”和“替换后体系”分组。

演示数据仅用于验证功能，不构成任何实际制度依据。

## 核心 API

所有以下接口均需 `Authorization: Bearer <JWT>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/standards/import` | 导入制度正文并自动拆分条文 |
| GET | `/api/v1/standards/tree` | 获取制度及条文树 |
| POST | `/api/v1/conflict-reviews` | 创建并同步执行本地冲突审查 |
| GET | `/api/v1/conflict-reviews` | 当前用户的审查历史 |
| GET | `/api/v1/conflict-reviews/{id}` | 原文、范围、统计和问题详情 |
| PUT | `/api/v1/conflict-reviews/{recordId}/issues/{issueId}` | 保存人工复核结论 |
| DELETE | `/api/v1/conflict-reviews/{id}` | 删除当前用户的审查记录 |

创建审查请求示例：

```json
{
  "documentName": "新员工请假管理办法.md",
  "content": "第一条 员工请假超过3天，由部门负责人审批。",
  "reviewType": "superior",
  "baselineDate": "2026-11-22",
  "standardIds": ["上级制度ID"],
  "replacedStandardIds": []
}
```

`reviewType` 可选：

- `superior`：新制度对上级文件；
- `internal`：新制度对内部制度；
- `replacement`：新旧替换及替换后体系；
- `existing`：存量制度对上级复核。

人工复核状态：

- `pending`：待复核；
- `resolved`：确认问题；
- `ignored`：排除问题。

## 审查处理逻辑

```text
读取待审文本
→ 按条款/段落拆分
→ 锁定对照制度与基准日期
→ 排除失效制度；替换审查单独保留旧版对照
→ 按业务主题和文本相似度筛选相关条款
→ 比较数量、期限、金额、比例与审批主体
→ 保存双方原文、规则版本和匹配程度
→ 人工复核
→ 导出报告
```

数量不同不必然违法，审批主体不同也可能来自分级审批。系统因此将结果命名为“疑似问题”，并要求人工确认。

## 测试与构建

后端：

```bash
cd apps/backend
mvn clean package
```

前端：

```bash
cd apps/web
npm run build
```

后端测试覆盖条文解析、中文及全角数字、金额单位归一化、数量标准差异、审批主体差异、年份排除、无关条款误报控制、替换任务校验及原有 OSS 签名兼容性。

## 安全与部署提醒

- 默认账号密码和 JWT 密钥仅用于本地开发，部署前必须修改；
- 制度正文可能敏感，建议使用内网数据库和内网部署；
- 不要把真实密钥、License 文件或生产数据提交到仓库；
- 当前为同步审查，适合小中型文本；大批量互审应改为异步队列；
- 上线前应使用真实脱敏样本建立标准问题集，评估漏报和误报。

## 开源许可

本项目基于 OpenSpec 修改，原项目采用 **GNU GPLv3**。继续分发或交付修改版本时，请遵守相应开源义务并保留版权和许可证声明。
