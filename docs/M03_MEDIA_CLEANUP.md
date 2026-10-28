# M03 图片残留文件清理

## 问题与旧方法

同步上传先将重新编码的图片原子移动到最终文件名，再向 `media_assets` 插入 `READY` 行。正常异常由 `finally` 删除文件；进程在移动后退出时，`finally` 不会运行。临时文件删除失败也会残留。

## 参考与选型

[Halo 的资源协调器](https://github.com/halo-dev/halo/blob/eb351e936cca89bf7fccac67934425c639ec10cc/application/src/main/java/run/halo/app/core/reconciler/PluginReconciler.java)会扫描限定目录、识别属于自己的残留 JAR，再清理。这里参考“先限定文件集合，再核对持久状态”的做法，没有复用 Halo 的插件协调代码。

本项目新增每日一次的目录核对：只检查上传器生成的 UUID 图片名和 `source-`／`upload-` 临时文件；至少保留 24 小时。图片删除前按 `storage_key` 查询数据库，有记录就保留；数据库检查失败时停止清理。忽略符号链接、目录及其他文件名。相比只依赖 `finally`，这能清理进程退出留下的旧文件；相比直接清空未识别文件，数据库核对与保留期减少误删。代价是每日逐个检查过期图片，目录规模大时扫描及数据库查询会增加。

配置项为 `SHOP_MEDIA_CLEANUP_DELAY_MS`（默认 86400000 毫秒）和 `SHOP_MEDIA_ORPHAN_RETENTION_HOURS`（默认 24 小时，低于 24 时不执行清理）。没有数据库迁移，也没有修改上传接口。

这次清理以当前单实例、单机持久图片目录为边界。没有把上传改成持久 `STAGED` 任务；上传时的同步解码和两路并发上限仍保留。多实例写入同一目录需要跨实例的任务租约或存储侧协调，不能直接依赖本轮的时间保留规则。
