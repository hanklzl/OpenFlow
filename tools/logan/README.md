# Logan Decode Tooling (OpenFlow)

OpenFlow 用 Logan 在设备上写加密日志，路径：
```
/sdcard/Android/data/com.hank.flow.open/files/logan/logs/
```

## 拉取日志（设备上）

```bash
adb pull /sdcard/Android/data/com.hank.flow.open/files/logan/logs ./openflow-logs
```

无需 root。

## 解密日志

OpenFlow 当前只有 debug 构建，使用硬编码的 AES key / IV（写死在
`app/src/main/java/com/hank/flow/open/log/OpenFlowLog.kt`）：

```bash
export LOGAN_AES_KEY=0123456789abcdef
export LOGAN_AES_IV=abcdef0123456789

# 拉到的目录或 zip 都可以
tools/logan/decode-logan.sh ./openflow-logs
# 或者
tools/logan/decode-logan.sh /path/to/some-feedback.zip
```

解密后的明文落在 `tools/logan/out/decoded/*.txt`。

## 日志事件分类

每行格式：
```
ts=<unix-ms> thread=<name> tag=<TAG> event=<event_name> key1=value1 key2=value2 ...
```

Tag 列表（见 `OpenFlowLog.Tag`）：
- `APP`：Application 生命周期
- `A11Y`：AccessibilityService 事件 + 悬浮球显隐决策
- `OVERLAY`：WindowManager addView/removeView
- `FGS`：录音前台服务
- `AUDIO`：AudioRecord 启停
- `ASR`：Whisper 转写
- `LLM`：润色
- `INSERT`：写入焦点 EditText

## 环境要求

- `javac` + `java`（JDK 8+）
- `unzip`（仅在解码 zip 时需要）

不要把解密后的明文日志提交到 git。
