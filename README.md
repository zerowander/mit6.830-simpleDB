# SimpleDB — MIT 6.830/6.814 Database Systems (2021)

基于 MIT 6.830 课程的简易关系型数据库实现，完成全部 6 个实验。

## 项目结构

```
src/java/simpledb/
├── common/          # 全局单例、Catalog、类型定义
├── storage/         # HeapFile、BufferPool、LogFile
├── execution/       # 火山模型查询算子
├── index/           # B+ 树索引
├── optimizer/       # 基于成本的查询优化
└── transaction/     # 锁管理、事务、死锁检测
```

## 实验完成情况

| Lab | 内容 | 核心实现 |
|-----|------|----------|
| 1 | 存储层 | HeapFile, HeapPage, BufferPool, Catalog, Tuple |
| 2 | 查询执行 | SeqScan, Filter, Join, Aggregate, Insert, Delete |
| 3 | B+ 树索引 | BTreeFile (findLeafPage, split, merge, steal) |
| 4 | 查询优化 | IntHistogram, TableStats, JoinOptimizer |
| 5 | 事务与并发 | LockManager, 死锁检测, 页面级锁 |
| 6 | 崩溃恢复 | LogFile.recover() 三趟恢复算法 |

## 各 Lab 思路

### Lab 1 — 存储层
- **HeapPage**：定长页面，头部 bitmap 管理空闲槽位，元组顺序存放
- **HeapFile**：页面集合，通过 `readPage`/`writePage` 读写磁盘
- **BufferPool**：页面缓存（LRU 驱逐），提供 `getPage` 统一访问接口
- **Catalog**：维护表名 → DbFile 的映射

### Lab 2 — 查询执行
- 基于 **Volcano 迭代器模型**（`open`/`hasNext`/`next`/`rewind`/`close`）
- **SeqScan**：遍历 HeapFile 所有元组
- **Filter**：对子算子输出应用 Predicate 过滤
- **Join**：嵌套循环连接，支持多种 JoinPredicate
- **Aggregate**：GROUP BY + 聚合函数（COUNT/SUM/AVG/MAX/MIN）
- **Insert/Delete**：修改底层页面并返回受影响的行数

### Lab 3 — B+ 树索引
- **BTreeFile** 实现 `DbFile` 接口，可作为表的存储后端
- **findLeafPage**：从根递归二分查找目标叶子页
- **splitLeafPage/splitInternalPage**：页面满时分裂，中间键上推父节点
- **stealFromLeafPage/stealFromLeft/Right**：页面低于半数时从兄弟页重分配
- **mergeLeafPages/mergeInternalPages**：删除导致页面低于半数时合并兄弟页，含级联父指针修复

### Lab 4 — 查询优化
- **IntHistogram/StringHistogram**：等宽直方图，估算过滤选择性
- **TableStats**：扫描全表建立字段直方图
- **JoinOptimizer**：Selinger 风格动态规划，选择最优连接顺序

### Lab 5 — 事务与并发
- **LockManager**：页面级共享锁（读）和独占锁（写），支持锁升级
- **死锁检测**：基于等待图的环检测，超时自动终止事务
- **Transaction**：管理 begin/commit/abort 生命周期，跟踪脏页

### Lab 6 — 崩溃恢复
- **LogFile.rollback()**：从事务首条日志向前扫描，收集每页首个 before-image 并写回磁盘
- **LogFile.recover()**：三趟恢复算法
  1. **Analysis**：从 checkpoint 向前扫描，确定 winner/loser 集合
  2. **Redo**：重放已提交事务的 after-image
  3. **Undo**：按倒序撤销未提交事务的 before-image
- 支持 checkpoint、STEAL/NO-FORCE 缓冲策略

## 测试结果

- 全部单元测试通过
- 系统测试：LogTest 10/10，其余通过（BTreeTest.testBigFile 为并发压力测试已知问题）
