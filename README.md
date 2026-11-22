# PixelShooter

一款零依赖的 Java 像素射击小游戏：单文件源码、运行时合成芯片音效、无任何外部素材，Windows / macOS / Linux 一键安装包开箱即玩。

## 游戏玩法

你驾驶一艘小飞船，抵御从屏幕上方不断涌来的敌机，活得越久、分越高。

- **5 种敌人**：小兵（蛇形下落）、疾锋（高速飘移）、重装（3 点血量）、炮手（会向下开火），以及每 30 秒登场、释放三向弹幕的 **BOSS**（屏幕底部显示血条）
- **5 种掉落道具**：击落敌机有概率掉落
  | 图标 | 效果 |
  | --- | --- |
  | W（黄） | 武器升级：单发 → 双发 → 三向散射 |
  | R（蓝） | 急速射击 8 秒 |
  | S（绿） | 护盾 10 秒，免疫碰撞与子弹 |
  | B（橙） | 全屏炸弹，清空屏幕敌机与敌弹 |
  | +（粉） | 生命 +1（上限 5） |
- **连击系统**：连续击杀提升分数倍率，最高 x4
- **关卡推进**：得分越高出怪越快、敌人越强，BOSS 血量随关卡提升
- **最高分存档**：自动保存到 `~/.pixelshooter_hi.txt`（Windows 为用户目录下同名文件）

## 操作说明

| 按键 | 作用 |
| --- | --- |
| ← / → 或 A / D | 左右移动 |
| 空格 | 发射子弹 / 开始游戏 |
| P 或 ESC | 暂停 / 恢复 |
| R | 重新开始（对局中、暂停中、结束后随时可用） |
| M | 音效开关 |

## 下载与安装

前往 [Releases 页面](https://github.com/SashaMC-Minecraft/PixelShooter/releases/latest) 下载对应平台的文件。所有安装包均已内置 Java 运行时，**无需另外安装 Java**。

| 平台 | 文件 | 安装方法 |
| --- | --- | --- |
| Windows 10/11 (x64) | `PixelShooter-Windows-PatchEXE.zip` | 解压后运行 `PixelShooter\PixelShooter.exe` |
| macOS (Apple Silicon M 系列) | `PixelShooter-macOS-AppleSilicon-v2.0.zip` | 解压后把 `PixelShooter.app` 拖入「应用程序」 |
| macOS (Intel) | `PixelShooter-macOS-Intel-v2.0.zip` | 同上 |
| Linux (Debian / Ubuntu / Deepin) | `pixelshooter_2.0_amd64.deb` | `sudo apt install ./pixelshooter_2.0_amd64.deb` |
| Linux (Fedora / RHEL / openSUSE) | `pixelshooter-2.0-1.x86_64.rpm` | `sudo dnf install ./pixelshooter-2.0-1.x86_64.rpm` |
| 任意装有 Java 11+ 的系统 | `PixelShooter-universal.jar` | `java -jar PixelShooter-universal.jar` |

### 首次打开提示？

- **macOS**：应用未做 Apple 公证，首次打开会提示"无法验证开发者"。右键（或双指轻按）**应用图标 → 打开**，再确认一次即可；也可在「系统设置 → 隐私与安全性」中点「仍要打开」。
- **Windows**：应用未购买代码签名证书，SmartScreen 若拦截，点「更多信息 → 仍要运行」。

### 系统要求

- 有图形桌面环境（Windows / macOS / Linux X11 或 XWayland），纯命令行环境无法显示窗口
- 无声卡或音频设备被占用时，游戏会自动静音继续运行

## 从源码运行

只需要 JDK 11 或更高版本（开发验证于 JDK 25）：

```bash
git clone https://github.com/SashaMC-Minecraft/PixelShooter.git
cd PixelShooter
javac -encoding UTF-8 -d out src/PixelShooter.java
java -cp out PixelShooter
```

整个游戏就是 `src/PixelShooter.java` 一个文件，欢迎阅读和修改。

## 自行打包安装包

使用 JDK 16+ 自带的 `jpackage`，例如：

```bash
jar --create --file pkg/PixelShooter.jar --main-class PixelShooter -C out .
jpackage --type app-image --name PixelShooter --app-version 2.0 \
  --input pkg --main-jar PixelShooter.jar --main-class PixelShooter --dest dist
```

把 `--type` 换成 `msi`（需 WiX 工具链）、`deb`、`rpm`、`dmg`（仅 macOS）可生成对应平台的安装包。

本仓库 [.github/workflows](.github/workflows) 中已配置好自动化打包：在 Actions 页面手动运行 **Build Linux Packages** 或 **Build macOS Packages**，产物会自动发布到最新 Release。

## 常见问题

- **窗口看起来发虚/模糊**：系统显示缩放导致，可在启动参数加 `-Dsun.java2d.uiScale=1.0`（安装包已内置此参数）
- **最高分想清零**：删除用户目录下的 `.pixelshooter_hi.txt` 后重新启动
- **按住 R 会反复重开吗**：不会，重新开始是按下瞬间触发一次

## 许可证

[MIT](LICENSE) © 2026 SashaMC-Minecraft
