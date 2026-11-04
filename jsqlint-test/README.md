# jsqlint-test

项目统一测试模块。所有模块的测试代码放在 `src/test/java`，测试资源放在
`src/test/resources`；测试专用的辅助类也放在测试源码目录。

测试沿用被测代码的 Java 包名，例如核心节点测试位于
`src/test/java/org/u80dc/jsqlint/core/node`。当前包含节点解析、渲染、表达式求值、
通用模板、节点结构相等性与不可变性、数值真假判断的测试。

JUnit 依赖由本模块声明，版本由父 POM 的 JUnit BOM 管理。
本模块以 `test` scope 依赖被测模块，目前为 `jsqlint-core`；新增其他模块的测试时，
在本模块添加对应的测试范围依赖。业务模块无需反向依赖本模块。

## 运行

从仓库根目录执行，`-am` 会一并构建被测模块：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```

全项目验证：

```powershell
.\mvnw.cmd '-Dmaven.test.skip=false' '-DskipTests=false' clean verify
```

Linux / macOS 将命令替换为 `./mvnw`，参数相同。
项目 `.mvn/maven.config` 默认设置 `maven.test.skip=true`，因此需显式覆盖。
测试报告位于 `jsqlint-test/target/surefire-reports`。
