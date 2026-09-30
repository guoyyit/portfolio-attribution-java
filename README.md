# portfolio-attribution-java

投资组合绩效归因与收益核算服务：按日录入估值快照与外部现金流，计算时间加权 /
资金加权收益，并对相对基准的超额收益做 BHB（配置 / 选择 / 交互）归因。

## 运行

```bash
mvn spring-boot:run
# 或
mvn package && java -Dattribution.data-dir=data -jar target/portfolio-attribution-java-1.0-SNAPSHOT.jar
```

数据以仅追加事件日志（带 CRC32）持久化在 `attribution.data-dir`（默认 `./data`），
重启自动重放恢复。

## 测试

```bash
mvn test
```

覆盖：乱序录入、重复提交幂等、事后修正、多期 TWR 复合与任意区间、TWR/MWR
独立成立、BHB 三项合计等于超额收益、并发查询一致性、重启恢复与尾部损坏截断、
REST 错误码稳定性。

## 文档

完整口径定义（收盘估值约定、TWR、IRR/MWR、BHB 分解与精确跨期链接权重、
舍入规则、现金流区间归属、错误码表）见 [docs/attribution.md](docs/attribution.md)。

## 接口速览

- `POST /api/portfolios/{id}/valuations`
- `POST /api/portfolios/{id}/cash-flows`
- `GET  /api/portfolios/{id}/returns?startDate&endDate&returnKind=TIME_WEIGHTED|MONEY_WEIGHTED`
- `GET  /api/portfolios/{id}/attribution?startDate&endDate`
