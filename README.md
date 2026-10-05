# 投资组合绩效归因与收益核算服务

接收按日到达的持仓估值快照与外部现金流，计算组合的时间加权 / 资金加权收益，
并将组合相对基准的超额收益按 Brinson-Hood-Beebower（BHB）模型分解为
配置效应、选择效应与交互效应。

## 数据与约定

- **估值快照** `V_d`：日期 `d` 日终、且当日外部现金流已入账之后的组合总市值。
  同一 `(portfolioId, date)` 的快照只有一份，重复提交即整体替换（含行业拆分），
  因此事后修正不会产生新旧并存的记录。
- **外部现金流** `F_d`：`d` 当日入账的现金流净额，注资 / 申购为正，提取 / 赎回为负。
  以 `(portfolioId, externalFlowId)` 幂等去重，重复提交相同 `externalFlowId`
  不会被计算两次；同一 ID 再次提交视为修正。现金流可乱序到达，计算前按日期归并，
  同一日期多笔现金流按净额合并。
- 起始日 `d0` 的现金流视为已包含在起始日终市值 `V0` 中，TWR 与 MWR 都不再
  对其重复计权。
- 现金流必须归属到一个行业（`sectorId`），以便按行业核算子组合收益；
  提供行业拆分时，行业市值之和必须等于总市值（容差 `1e-9`）。

## 两种收益口径

### 时间加权收益（TWR）

在每个有现金流（或估值）的日期切分子区间。子区间 `i` 的收益率为

```
r_i = (V_i - V_(i-1) - F_i) / V_(i-1)
```

区间时间加权收益为各子区间精确复合：

```
R_TWR = ∏ (1 + r_i) - 1
```

因此任意区间的 TWR 都可由其相邻子区间结果复合得到，与直接对该区间一次性
计算的结果严格一致（浮点误差除外）。当某子区间期初市值为 0 时，
要求期末（扣除现金流后）也为 0，此时该子区间收益记为 0；否则拒绝计算。
自动化测试对多点区间（包括 3 个估值点以上、任意相邻切分、以及现金流修正
前后）断言 `R(d0,dn) = ∏(1 + R(d_i, d_(i+1))) - 1`，
见 `ReturnCalculatorTest.wholeIntervalMatchesCompoundingAcrossThreePointsAndArbitrarySplit`
与 `BoundaryConsistencyIntegrationTest.twrCompoundFromSubRangesEqualsDirectQueryAfterCorrection`。

### 资金加权收益（MWR，Modified Dietz）

```
R_MWR = (V_n - V_0 - Σ F) / (V_0 + Σ w_i F_i)
w_i = (d_n - d_i) / (d_n - d_0)
```

求和只包含严格位于 `(d0, dn]` 的现金流；起始日现金流已在 `V0` 中。
分母为 0 时 MWR 返回 0。

两种口径相互独立：TWR 剔除现金流时点与规模的影响，MWR 反映现金流时点的影响，
同一区间两者各自按定义独立成立，不要求相等。

### 现金流时点边界（无估值快照日期上的现金流）

TWR 必须在每笔现金流当日切分子区间，并取得“当日扣除现金流后的期末市值”；
若某笔区间内部现金流（日期严格位于 `(d0, dn]`）落在**没有估值快照的日期**，
TWR 在现有数据下无法对该子区间给出定义，而 MWR 仍会把该现金流计入分子与
加权分母——这将造成同一区间内一种口径计入现金流、另一种口径将其静默忽略的
矛盾。为保证两种口径不会互相矛盾，服务此时**拒绝**整个查询：

- 错误码 `CASH_FLOW_WITHOUT_VALUATION`，HTTP 400；
- 拒绝只取决于输入数据（哪些日期有快照、哪些日期有非零净额现金流），
  与请求顺序、录入顺序无关；同一数据重复请求得到完全相同的错误码与信息；
- 同一日期多笔现金流先按净额归并：净额为 0（容差 `1e-12`）时对两种口径
  均无经济影响，允许该日期没有快照；
- 起始日 `d0` 的现金流视为已含在 `V0` 中（两种口径都不另计）；区间
  `(d0, dn]` 之外的现金流不参与该区间，故这两类现金流永远不会触发该拒绝；
- 消除拒绝的方式只有两种：补录该日期的估值快照，或修正/删除该现金流。

该规则对持久化查询（`GET /api/returns`）与显式一次性计算
（`POST /api/calculate-returns`）同等适用；归因查询中的组合侧现金流
也受同一规则约束。

## 归因分解（BHB）

组合与基准必须使用同一条估值日期轴、同一组行业。行业 `s` 在子区间 `i` 的
收益率与期初权重：

```
rP_s,i = (M_s,i - M_s,i-1 - F_s,i) / M_s,i-1
wP_s,i = M_s,i-1 / V_i-1
```

基准侧 `rB_s,i`、`wB_s,i` 同理（基准无外部现金流）。每个子区间做标准 BHB 分解：

```
配置    A_s,i = (wP_s,i - wB_s,i) (rB_s,i - rB_i)
选择    S_s,i =  wB_s,i           (rP_s,i - rB_s,i)
交互    I_s,i = (wP_s,i - wB_s,i) (rP_s,i - rB_s,i)
```

单子区间内 `Σ_s (A + S + I) = rP_i - rB_i` 恒等。多期时，超额收益定义为
区间 TWR 之差 `R_P - R_B`；先按公共链接系数

```
k = (R_P - R_B) / Σ_i (rP_i - rB_i)
```

缩放三项（单期时 `k = 1`，退化为教科书 BHB），再将链接产生的残差
（正常情况下仅为浮点误差）按各项幅度比例计入交互项。因此对任意区间恒有

```
配置效应合计 + 选择效应合计 + 交互效应合计 = R_P - R_B
```

该恒等在输出舍入**之前**严格成立。

### 缺少行业拆分时的归因行为

行业拆分按“侧 + 日期”区分（行业市值 0 是合法录入，存在性不以市值是否为 0
判断）。规则如下：

| 组合侧 | 基准侧 | 行为 |
| --- | --- | --- |
| 全程无行业拆分 | 全程无行业拆分 | 正常返回：整体作为单一聚合组，配置效应 = 交互效应 = 0，选择效应 = 超额收益，三项恒等依旧严格成立；响应中 `sectors` 为空列表 |
| 有行业拆分 | 无行业拆分 | 拒绝：`SECTOR_BREAKDOWN_MISSING` |
| 无行业拆分 | 有行业拆分 | 拒绝：`SECTOR_BREAKDOWN_MISSING` |
| 行业集合只在部分日期出现（任意一侧、任意日期缺行） | — | 拒绝：`SECTOR_BREAKDOWN_MISSING`，错误信息指明缺失的行业与日期 |

即：要么两侧都在全部估值日期上提供同一组行业拆分，要么两侧都完全不提供；
单侧 / 部分缺失在 BHB 下没有明确定义，因此以稳定、可区分的错误码拒绝，
而不是静默按 0 填充后给出失真的分解。拒绝原因只取决于数据本身。

## 舍入规则

- 所有内部计算使用 `double` 全精度，不在中间步骤舍入。
- 对外输出的收益率、权重与三项效应统一四舍五入到小数点后 **6 位**
  （`HALF_UP`），即精度 `1e-6`。
- 文档约定的舍入容差为 **`2e-6`**：在输出（已舍入）数值上核对
  “三项合计 = 超额收益”时，偏差不超过 `2e-6` 即为通过。
- 行业市值合计与总市值的录入一致性容差为 `1e-9`。

## 区间查询与修正

- `GET /api/returns` 与 `GET /api/attribution` 支持任意起止日期，
  结果只依赖该区间内的快照与 `(start, end]` 的现金流；区间外数据不参与计算。
- 不缓存任何计算结果，每次查询都从持久层重新派生，因此历史数据修正后，
  受影响区间立即得到新结果，未受影响区间保持不变。
- 区间两个端点都必须存在估值快照，否则以 `MISSING_ENDPOINT_VALUATION` 拒绝。
- 区间内部现金流日期必须存在估值快照，否则以 `CASH_FLOW_WITHOUT_VALUATION`
  拒绝（见“现金流时点边界”）。

## 并发与重启恢复

一致性由**两层机制**共同保证，任何一次查询都只能观察到某个完整提交点：

1. **进程内读写锁（`WriteGuard`）**：写操作获取排他锁，且持锁范围覆盖
   *整个数据库事务*——在事务开启前加锁、事务提交/回滚之后才释放；读查询
   获取共享锁并在整个只读事务期间持有。因此查询绝不会与写事务并发执行，
   不可能读到提交中途“新总市值 + 旧行业拆分”之类的跨表混合状态。
2. **查询事务快照隔离**：读查询在单条只读事务中完成其全部 SELECT，
   隔离级别由配置项 **`portfolio.query.isolation`** 控制：
   - `SNAPSHOT`（**默认**，H2 的 JDBC 隔离级别 6）：事务内多条读取始终
     看到事务开始时的同一数据库快照，即使期间有其他事务提交；
   - `READ_COMMITTED` / `SERIALIZABLE`：可显式切换（换用其他数据库时
     可设为该库支持的对应级别）。
   该配置项见 `application.properties`。

“快照总市值 + 行业拆分”在单个数据库事务内替换，要么整体生效要么整体不
生效。可验证性（而不只是文档声明）：

- `ConcurrencyIntegrationTest`：大量写线程与并发返回查询交织，断言每次
  查询都返回有限且合理的结果；
- `CrossTableConcurrencyIntegrationTest`：写线程持续跨表修正端点快照，
  读线程并发做归因查询并断言**行业期初权重之和恒为 1**、三项合计恒等于
  超额收益、行业级效应之和与组合级一致。该测试在去掉读锁/隔离（注入读
  事务内语句间延迟）时稳定失败，在当前实现下稳定通过。

数据持久化到文件型 H2（默认 `./data/portfolio`）。进程在任意时刻终止后重启，
已提交的录入依然存在，未提交事务由数据库回滚，不会留下部分生效的记录
（`RecoveryIntegrationTest` 覆盖重启恢复与重启后的修正）。历史数据无需
迁移：表结构未发生破坏性变化，旧库可直接被新版本查询。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/valuations` | 录入 / 修正某日估值快照（幂等替换） |
| POST | `/api/cash-flows` | 录入 / 修正一笔外部现金流（按 `externalFlowId` 幂等） |
| GET | `/api/returns?portfolioId&start&end` | 查询区间 TWR 与 MWR |
| GET | `/api/attribution?portfolioId&benchmarkId&start&end` | 查询 BHB 归因 |
| POST | `/api/calculate-returns` | 用显式传入的估值与现金流一次性计算，不访问持久数据 |

录入示例：

```json
{
  "portfolioId": "P1",
  "date": "2024-01-08",
  "totalValue": 110.0,
  "sectors": [
    {"sectorId": "A", "marketValue": 66.0},
    {"sectorId": "B", "marketValue": 44.0}
  ]
}
```

现金流示例（`amount` 正数注资、负数提取）：

```json
{
  "portfolioId": "P1",
  "externalFlowId": "broker-20240108-001",
  "date": "2024-01-08",
  "amount": 10.0,
  "sectorId": "A"
}
```

## 错误码

拒绝结果只取决于输入本身，与请求顺序无关。错误响应形如
`{"code": "...", "message": "..."}`，HTTP 状态为 400（内部错误为 500）。

| code | 触发条件 |
| --- | --- |
| `INVALID_REQUEST` | 缺少必填字段、日期/数值非法、重复行业等格式问题 |
| `NEGATIVE_TOTAL_VALUE` | 组合总市值为负 |
| `NEGATIVE_SECTOR_VALUE` | 任一行业市值为负 |
| `ZERO_CASH_FLOW` | 现金流金额为 0 |
| `MISSING_ENDPOINT_VALUATION` | 查询区间起点或终点缺少估值快照 |
| `CASH_FLOW_OUT_OF_RANGE` | 一次性计算中现金流日期不在 `(start, end]` |
| `INVALID_DATE_RANGE` | 起始日期晚于结束日期 |
| `SECTOR_TOTAL_MISMATCH` | 行业市值之和与总市值不一致 |
| `SECTOR_CASH_FLOW_MISSING` | 现金流未指定行业 |
| `SECTOR_SET_MISMATCH` | 组合与基准的行业集合不一致 |
| `CASH_FLOW_WITHOUT_VALUATION` | 区间内部现金流落在没有估值快照的日期（两种口径无法一致定义） |
| `SECTOR_BREAKDOWN_MISSING` | 仅一侧提供行业拆分，或某行业在部分估值日期缺拆分记录 |

## 可复现的验证方法

```bash
mvn test                 # 全部 36 个单元/集成测试（含并发与端到端 HTTP）
mvn spring-boot:run      # 或：mvn package 后 java -jar target/*.jar
```

端到端 HTTP 复核（`EndToEndHttpIntegrationTest` 已自动化同样流程）：

```bash
# 1) 启动（可改端口与数据源，避免触碰已有历史库）
java -jar target/portfolio-attribution-java-1.0-SNAPSHOT.jar --server.port=8088 \
  --spring.datasource.url='jdbc:h2:file:/tmp/pam-e2e/manual;AUTO_SERVER=FALSE'

# 2) 录入三日快照（含行业拆分）与 D1 现金流
curl -s -X POST localhost:8088/api/valuations -H 'Content-Type: application/json' \
  -d '{"portfolioId":"P","date":"2024-03-01","totalValue":100,"sectors":[{"sectorId":"A","marketValue":60},{"sectorId":"B","marketValue":40}]}'
# ... 其余录入见 EndToEndHttpIntegrationTest / 本次复核记录

# 3) 现金流落在无快照日期 → 400 + CASH_FLOW_WITHOUT_VALUATION
curl "localhost:8088/api/returns?portfolioId=P&start=2024-03-01&end=2024-03-15"

# 4) 归因恒等：allocation + selection + interaction == excessReturn（|偏差| <= 2e-6）
curl "localhost:8088/api/attribution?portfolioId=P&benchmarkId=B&start=2024-03-01&end=2024-03-15"
```

## 构建与测试

```bash
mvn test        # 全部单元测试与 Spring 集成测试
mvn spring-boot:run
```

测试覆盖：乱序录入、重复提交幂等、事后修正的影响范围、多期 TWR 复合与
直接计算一致、TWR/MWR 定义、**现金流落在无快照日期时两种口径一致拒绝
（含净额为 0、起止/区间外边界、乱序稳定性）**、归因三项合计等于超额收益、
**两侧均无行业拆分的聚合归因、单侧/部分缺失行业信息的稳定拒绝**、
显式输入的稳定拒绝、**跨表并发读写只观察完整提交点**、并发读写一致性、
基于文件数据库的重启恢复与**历史数据免迁移**，以及实际启动 Web 容器后
经对外 HTTP 接口完成的端到端录入—修正—收益—归因复核。
