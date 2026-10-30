# 下载中心

[![HMCL Plugin](https://img.shields.io/badge/HMCL-plugin-blue)](https://github.com/topics/erren282s-launcher-plugins)
[![Version](https://img.shields.io/badge/version-1.5.1-green)]()
[![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)](LICENSE)

把启动器的下载集中到一个页面：进度、速度、记录一目了然，下载全程在后台进行。

- **插件 ID**：`org.dsh.download-hub`
- **适用启动器**：[erren282-launcher](https://github.com/erren282/erren282-launcher)（HMCL 3.16.4 定制版，需内建插件系统）

## 功能

### 后台下载

启动器原本在安装游戏时会弹出一个覆盖窗口的进度框，挡在前面直到装完。装上这个插件后：

- 任务照常执行，但**不再弹窗**
- 进度改由下载中心页面展示
- 你可以继续浏览、设置、做别的事

### 下载列表

每个下载显示进度条、百分比、已下载/总大小与实时速度。

**单个文件**（一个 mod、一个资源包）显示自己的文件名和大小：

```
screenshot-1.4.2.jar            已完成
━━━━━━━━━━━━━━━━━━━━━━━━━━━
22.2 KB / 22.2 KB
```

**批量任务**（游戏安装、整合包）会有几百个文件，全部折叠成一行：

```
安装 1.20.1                      进行中
━━━━━━━━━━━━━━━━━━━━━━━━━━━
47 个文件完成 · 23.4 MB · 2.3 MB/s
```

### 记录与清空

下载记录默认保存在磁盘上，重启启动器后仍在。点「清空记录」可以移除已完成项，**正在下载的任务不受影响**。

### 取消下载

进行中的任务右侧有「取消」按钮，点击后：

1. 整棵任务树停止——批次里排队的文件不会再启动
2. 当前传输立即中断
3. 未完成的文件被删除

### 标题栏入口

最小化按钮左侧有一个下载图标，点它打开下载中心。新下载开始时图标上会滑入一个红点，提示有新任务。

## 安装

把 `org.dsh.download-hub_1.5.1.jar` 放进 `.hmcl/plugins/`，重启启动器。

之后：

- 标题栏会出现下载按钮
- 设置 → 插件 → 下载中心 也能打开

## 更新依赖的启动器能力

本插件依赖两个启动器 API：

| API | 作用 |
|---|---|
| `PluginMenuService.registerDownloadListener` | 接收所有下载的生命周期事件 |
| `DownloadEvent` | 携带进度、批次名与取消动作 |

如果装在未包含这些 API 的启动器上，插件会加载失败并提示缺少类。

## 数据位置

```
.hmcl/plugins/data/org.dsh.download-hub/downloads.json
```

删除这个文件等于清空记录。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

需要 JDK 17+，编译期 classpath 指向启动器 jar。产物在 `dist/`。

## 说明：本项目为 Vibe Coding 产物

**本项目的全部代码均由 AI（大语言模型）生成**，通过对话式编程（vibe coding）完成——
人类只提出需求、给出反馈与验收，不手写实现代码。

- 代码可能包含未经验证的边界情况、不一致的风格，或与常规工程实践不同的设计取舍
- 请在生产环境使用前自行审查、测试与评估
- 欢迎提交 issue 或 PR 改进
