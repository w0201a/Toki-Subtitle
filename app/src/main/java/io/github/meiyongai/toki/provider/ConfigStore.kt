package io.github.meiyongai.toki.subtitle.provider

import android.content.SharedPreferences

/**
 * 管理端配置的唯一持久存储及框架只读副本；宿主不访问管理端组件。
 * @param changed 完整快照或连接状态改变后的通知，由调用方安排线程。
 * @param failed 明确报告读取或发布失败，不将失败解释成空配置。
 * Callers: ConfigClient、ConfigStoreTest。
 */
internal class ConfigStore(
    private val changed: () -> Unit,
    private val failed: (Exception) -> Unit
) {
    @Volatile private var current: ConfigSnapshot? = null
    @Volatile var isReady: Boolean = false
        private set
    @Volatile var syncError: Exception? = null
        private set
    private var local: SharedPreferences? = null
    private var remote: SharedPreferences? = null
    private var host = false

    /** Confirm a persisted request is readable by its host, not merely saved in the manager app. */
    @Synchronized fun deliveryConfirmed(changes: Map<String, Any>): Boolean {
        if (!isReady || syncError != null || changes.isEmpty()) return false
        val target = (if (host) local else remote) ?: return false
        val saved = target.all
        return (host || saved[PROTOCOL] == 1) && saved[REVISION] == current?.revision &&
            changes.all { (key, value) -> saved[key] == value }
    }

    companion object {
        const val GROUP = "toki_configuration"
        const val LOCAL_NAME = "toki_config"
        const val REVISION = "__toki_revision"
        const val PROTOCOL = "__toki_protocol"
        const val IMPORT_REVISION = "__toki_import_revision"
    }

    /** Host-owned overrides never write into framework read-only preferences. */
    @Synchronized fun attachHostOverrides(preferences: SharedPreferences) {
        check(host && local == null)
        local = preferences
        reloadHost()
    }

    private fun managerValues(): Map<String, *> {
        val source = remote?.all.orEmpty()
        // An injector may not offer a manager service at all. A partially published manager snapshot is not accepted.
        if (source.isEmpty()) return emptyMap<String, Any>()
        require(source[PROTOCOL] == 1) { "Manager configuration protocol unavailable" }
        val revision = source[REVISION]
        val imported = source[IMPORT_REVISION] ?: 0L
        require(revision is Long && revision >= 0 && imported is Long && imported in 0..revision)
        ConfigArchive.validate(source.filterKeys { it in ConfigSchema.keys }.mapValues { requireNotNull(it.value) })
        ConfigConflictClock.revisions(source[ConfigConflictClock.KEY_REVISIONS], revision)
        return source
    }

    private fun hostValues(): Map<String, Any?> {
        val base = managerValues()
        val preferences = local ?: return base
        val overrides = preferences.all
        val removed = (overrides["__host_removed"] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val keys = overrides.keys.filter { it in ConfigSchema.keys }.toSet() + removed.filter { it in ConfigSchema.keys } +
            if (overrides["__host_replace"] == true) ConfigSchema.keys else emptySet()
        val revisions = ConfigConflictClock.revisions(base[ConfigConflictClock.KEY_REVISIONS], base[REVISION] as? Long ?: 0L)
        val baselines = ConfigConflictClock.baselines(overrides[ConfigConflictClock.HOST_BASELINES]).toMutableMap()
        val suppressed = ((overrides[ConfigConflictClock.HOST_SUPPRESSED] as? Set<*>)
            ?.filterIsInstance<String>()?.filter { it in ConfigSchema.keys }?.toSet().orEmpty()).toMutableSet()
        var metadataChanged = false
        keys.forEach { key ->
            val now = ConfigConflictClock.fingerprint(base, key, revisions)
            val previous = baselines[key]
            if (previous == null) { baselines[key] = now; metadataChanged = true }
            else if (base.isNotEmpty() && previous != now && suppressed.add(key)) metadataChanged = true
        }
        val merged = base.toMutableMap()
        keys.filter { it !in suppressed }.forEach { key ->
            if (key in removed || key !in overrides) merged.remove(key)
            else merged[key] = overrides[key]
        }
        ConfigArchive.validate(merged.filterKeys { it in ConfigSchema.keys }.mapValues { requireNotNull(it.value) })
        val baseRevision = base[REVISION] as? Long ?: 0L
        val localRevision = overrides[REVISION] as? Long ?: 0L
        merged[REVISION] = maxOf(baseRevision, localRevision, current?.revision ?: 0L)
        merged[IMPORT_REVISION] = minOf(maxOf(overrides[IMPORT_REVISION] as? Long ?: 0L,
            base[IMPORT_REVISION] as? Long ?: 0L), merged[REVISION] as Long)
        // Keep old user values for recovery. Only conflict metadata changes; a reverted manager value cannot resurrect them.
        if (metadataChanged) check(preferences.edit()
            .putString(ConfigConflictClock.HOST_BASELINES, ConfigConflictClock.encode(baselines))
            .putStringSet(ConfigConflictClock.HOST_SUPPRESSED, suppressed).commit()) { "Conflict metadata was not saved" }
        return merged
    }

    /**
     * 连接管理端私有配置，保留已有键值和版本。
     * @param preferences Toki 私有 SharedPreferences。
     * @return Unit。
     * Callers: ConfigClient.init、ConfigStoreTest。
     */
    @Synchronized fun attachLocal(preferences: SharedPreferences) {
        check(!host && local == null) { "配置来源已确定" }
        local = preferences
        if (accept(preferences.all, requireProtocol = false)) changed()
    }

    /**
     * 连接框架发布端并立即提交完整配置；失败保存本地配置并明确标记未同步。
     * @param acquire 获取框架管理端可写配置的操作。
     * @return 只有当前完整事务被框架确认时返回 true。
     * Callers: ConfigClient.connectFramework、ConfigStoreTest。
     */
    @Synchronized fun connect(acquire: () -> SharedPreferences): Boolean {
        check(local != null && !host) { "仅管理端可以发布配置" }
        remote = null
        try {
            remote = acquire()
            publish()
        } catch (error: Exception) {
            publicationFailed(error)
            return false
        }
        changed()
        return true
    }

    /**
     * 处理框架服务死亡，保留管理端设置并等待服务重新绑定，不轮询服务。
     * @return Unit。
     * Callers: ConfigClient.disconnectFramework、ConfigStoreTest。
     */
    @Synchronized fun disconnect() {
        remote = null
        publicationFailed(IllegalStateException("框架配置服务已断开，设置尚未同步"))
    }

    /**
     * 安装宿主只读来源；通信或数据错误转换为明确的未就绪状态。
     * @param acquire 框架提供的只读配置获取操作。
     * @param observe 注册监听的操作；先监听再读取，避免丢失并发更新。
     * @return 初始配置是否已完整验证。
     * Callers: ConfigClient.initHost、ConfigStoreTest。
     */
    @Synchronized fun attachHost(
        acquire: () -> SharedPreferences,
        observe: (SharedPreferences) -> Unit
    ): Boolean {
        check(local == null && !host) { "配置来源已确定" }
        host = true
        try {
            remote = acquire()
            observe(requireNotNull(remote))
        } catch (error: Exception) {
            hostFailed(error)
            return false
        }
        // 通知下游的异常不属于配置获取或协议错误。
        reloadHost()
        return isReady
    }

    /**
     * 原子接收宿主完整配置；损坏数据不覆盖已验证快照，并暂停依赖配置的拦截。
     * @return Unit。
     * Callers: attachHost、ConfigClient 的框架变更监听、ConfigStoreTest。
     */
    @Synchronized fun reloadHost() {
        check(host) { "仅宿主接收框架配置" }
        val notify = try {
            accept(if (local != null) hostValues() else requireNotNull(remote).all, requireProtocol = local == null)
        } catch (error: Exception) {
            hostFailed(error)
            return
        }
        if (notify) changed()
    }

    /**
     * 获取已就绪快照，拒绝把尚未连接解释成所有功能关闭。
     * @return 完整配置；未就绪时抛出包含连接原因的异常。
     * Callers: ConfigClient.snapshot、update、publish、ConfigStoreTest。
     */
    @Synchronized fun snapshot(): ConfigSnapshot {
        check(isReady) { "配置尚未就绪：${syncError?.javaClass?.simpleName}" }
        return checkNotNull(current)
    }

    /** 返回同一次状态读取的已验证快照；null 明确表示宿主配置不可用。 */
    @Synchronized fun snapshotOrNull(): ConfigSnapshot? = if (isReady) current else null

    /**
     * 管理端串行验证和保存一批修改，再向框架发布同一完整版本。
     * @param changes 用户提交的键值；null 删除对应键。
     * @param replace 是否全量替换，用于用户确认的配置导入。
     * @return 完成保存的配置版本；本地保存失败不发布成功状态。
     * Callers: ConfigClient 的写入接口、ConfigStoreTest。
     */
    @Synchronized fun update(changes: Map<String, Any?>, replace: Boolean = false, receipt: HostSettingsPatch? = null): Long {
        val preferences = checkNotNull(local) { "宿主配置只读" }
        return synchronized(preferences) {
            val values = if (replace) mutableMapOf<String, Any>() else snapshot().configuration().toMutableMap()
            changes.forEach { (key, value) ->
                require(key in ConfigSchema.keys) { "不支持的配置项：$key" }
                if (value == null) values.remove(key) else values[key] = value
            }
            val checked = ConfigArchive.validate(values)
            val revision = Math.addExact(snapshot().revision, 1L)
            val importRevision = if (replace) revision else snapshot().importRevision
            val editor = preferences.edit()
            if (host) {
                val base = managerValues()
                val clocks = ConfigConflictClock.revisions(base[ConfigConflictClock.KEY_REVISIONS], base[REVISION] as? Long ?: 0L)
                val baselines = ConfigConflictClock.baselines(preferences.all[ConfigConflictClock.HOST_BASELINES]).toMutableMap()
                val suppressed = preferences.getStringSet(ConfigConflictClock.HOST_SUPPRESSED, emptySet())!!.toMutableSet()
                (if (replace) ConfigSchema.keys else changes.keys).forEach { key ->
                    baselines[key] = ConfigConflictClock.fingerprint(base, key, clocks)
                    suppressed.remove(key)
                }
                editor.putString(ConfigConflictClock.HOST_BASELINES, ConfigConflictClock.encode(baselines))
                    .putStringSet(ConfigConflictClock.HOST_SUPPRESSED, suppressed)
                val removed = (preferences.getStringSet("__host_removed", emptySet()) ?: emptySet()).toMutableSet()
                if (replace) {
                    editor.putBoolean("__host_replace", true)
                    ConfigSchema.keys.forEach { key -> if (key !in checked) { editor.remove(key); removed.add(key) } }
                }
                (if (replace) checked else changes).forEach { (key, value) ->
                    when (value) {
                        null -> { editor.remove(key); removed.add(key) }
                        is Boolean -> { editor.putBoolean(key, value); removed.remove(key) }
                        is String -> { editor.putString(key, value); removed.remove(key) }
                    }
                }
                editor.putStringSet("__host_removed", removed).putLong(REVISION, revision).putLong(IMPORT_REVISION, importRevision)
                val previousPending = if (preferences.getBoolean(HostSettingsWire.RECONCILE, false)) emptySet()
                    else preferences.getStringSet(HostSettingsWire.PENDING, emptySet()).orEmpty()
                val mirrorKeys = previousPending + (if (replace) ConfigSchema.keys else changes.keys)
                val mirrorReplace = replace || preferences.getBoolean(HostSettingsWire.REPLACE, false)
                val mirrorValues = if (mirrorReplace) checked else mirrorKeys.associateWith { checked[it] }
                // Validate the exact eventual envelope before committing any part of the host edit.
                HostSettingsWire.bounded(org.json.JSONObject().apply {
                    put("protocol", 1); put("host", ManualHostAdaptation.packages[0]); put("id", "0".repeat(128)); put("replace", mirrorReplace)
                    put("values", org.json.JSONObject(HostSettingsWire.valuesJson(mirrorValues)))
                }.toString())
                editor.putStringSet(HostSettingsWire.PENDING, mirrorKeys).putString(HostSettingsWire.REQUEST, java.util.UUID.randomUUID().toString())
                    .putBoolean(HostSettingsWire.REPLACE, mirrorReplace).putBoolean(HostSettingsWire.RECONCILE, false).putString(HostSettingsWire.PAYLOAD, HostSettingsWire.valuesJson(mirrorValues))
            } else {
                val clocks = ConfigConflictClock.revisions(preferences.all[ConfigConflictClock.KEY_REVISIONS], snapshot().revision).toMutableMap()
                (if (replace) ConfigSchema.keys else changes.keys).forEach { clocks[it] = revision }
                editor.clear()
                putConfiguration(editor, checked, revision, importRevision)
                editor.putString(ConfigConflictClock.KEY_REVISIONS, ConfigConflictClock.encode(clocks))
                val receipts = preferences.getString(HostSettingsWire.RECEIPTS, null)
                if (receipt != null) editor.putString(HostSettingsWire.RECEIPTS, HostSettingsWire.remember(receipts, receipt, revision))
                else if (receipts != null) editor.putString(HostSettingsWire.RECEIPTS, receipts)
            }
            check(editor.commit()) { "配置保存失败，版本 $revision 未确认" }
            if (accept(if (host) hostValues() else preferences.all, requireProtocol = false)) changed()
            if (!host && remote != null) {
                try {
                    publish()
                } catch (error: Exception) {
                    publicationFailed(error)
                    return revision
                }
                changed()
            }
            revision
        }
    }

    /** Upgrade reconciliation mirrors only active local keys absent from the manager, never suppressed conflicts. */
    @Synchronized fun queueUnsyncedHostValues(): Int {
        if (!host || !isReady) return 0
        val preferences = local ?: return 0
        synchronized(preferences) {
            if (!preferences.getStringSet(HostSettingsWire.PENDING, emptySet()).isNullOrEmpty()) return 0
            val localValues = preferences.all
            val base = managerValues()
            val suppressed = preferences.getStringSet(ConfigConflictClock.HOST_SUPPRESSED, emptySet()).orEmpty()
            val values = localValues.filterKeys { it in ConfigSchema.keys && it !in base && it !in suppressed }
                .mapValues { requireNotNull(it.value) }
            if (values.isEmpty()) return 0
            ConfigArchive.validate(values)
            HostSettingsWire.bounded(HostSettingsWire.valuesJson(values))
            check(preferences.edit().putStringSet(HostSettingsWire.PENDING, values.keys)
                .putString(HostSettingsWire.REQUEST, java.util.UUID.randomUUID().toString())
                .putBoolean(HostSettingsWire.REPLACE, false).putBoolean(HostSettingsWire.RECONCILE, true)
                .putString(HostSettingsWire.PAYLOAD, HostSettingsWire.valuesJson(values)).commit())
            return values.size
        }
    }

    /** Manager receipt and configuration share one commit; an ACK-lost retry never reapplies old values. */
    @Synchronized fun applyHostPatch(patch: HostSettingsPatch): Long {
        check(!host)
        HostSettingsWire.validate(patch)
        val preferences = checkNotNull(local)
        HostSettingsWire.receipt(preferences.getString(HostSettingsWire.RECEIPTS, null), patch)?.let { return it }
        val changes = (if (patch.onlyAbsent) patch.values.filterKeys { it !in snapshot().configuration() } else patch.values).toMutableMap()
        // A host can request its own rescan, never overwrite another installed host’s pending nonce.
        if (ManualHostAdaptation.KEY in changes || patch.replace) {
            val before = snapshot().string(ManualHostAdaptation.KEY)
            val incoming = changes[ManualHostAdaptation.KEY] as? String
            val merged = if (ManualHostAdaptation.requested(incoming, patch.host))
                ManualHostAdaptation.values(before, patch.host, ManualHostAdaptation.token(incoming, patch.host, ""))
                    .getValue(ManualHostAdaptation.KEY) else before
            if (merged == null) changes.remove(ManualHostAdaptation.KEY) else changes[ManualHostAdaptation.KEY] = merged
        }
        return update(changes, patch.replace, patch)
    }

    /**
     * 验证版本元数据及业务字段后一次替换内存快照。
     * @param values 从同一次 getAll 获取的数据。
     * @param requireProtocol 宿主必须确认管理端已发布协议标识。
     * @return 是否应发布状态通知；业务监听不在校验异常边界内执行。
     * Callers: attachLocal、reloadHost、update。
     */
    private fun accept(values: Map<String, *>, requireProtocol: Boolean): Boolean {
        if (requireProtocol) require(values[PROTOCOL] == 1) { "配置尚未发布，请先打开 Toki 完成同步" }
        val revision = values[REVISION] ?: if (requireProtocol) error("配置缺少版本") else 0L
        val imported = values[IMPORT_REVISION] ?: 0L
        require(revision is Long && revision >= 0 && imported is Long && imported in 0..revision) { "配置版本无效" }
        ConfigConflictClock.revisions(values[ConfigConflictClock.KEY_REVISIONS], revision)
        // 升级后只读取当前支持的持久化字段；不修改旧数据，文件导入仍严格校验。
        val checked = ConfigArchive.validate(values.filterKeys { it in ConfigSchema.keys }
            .mapValues { requireNotNull(it.value) { "配置项 ${it.key} 为空" } })
        val previous = current
        val notify = !isReady || previous == null || previous.revision != revision || previous.configuration() != checked ||
            previous.importRevision != imported
        current = ConfigSnapshot(checked, revision, imported)
        isReady = true
        if (host) syncError = null
        return notify
    }

    /**
     * 发布一个完整事务；commit 返回 false 时不报告同步成功。
     * @return Unit。
     * Callers: connect、update。
     */
    private fun publish() {
        val snapshot = snapshot()
        val editor = checkNotNull(remote).edit().clear().putInt(PROTOCOL, 1)
        putConfiguration(editor, snapshot.configuration(), snapshot.revision, snapshot.importRevision)
        val clocks = ConfigConflictClock.revisions(local?.all?.get(ConfigConflictClock.KEY_REVISIONS), snapshot.revision)
        editor.putString(ConfigConflictClock.KEY_REVISIONS, ConfigConflictClock.encode(clocks))
        check(editor.commit()) { "框架未确认配置版本 ${snapshot.revision}" }
        syncError = null
    }

    /**
     * 向同一事务编码业务配置和版本元数据。
     * @param editor 接收所有字段的事务。
     * @param values 已校验的业务配置。
     * @param revision 提交版本。
     * @param imported 最近导入版本。
     * @return Unit。
     * Callers: update、publish。
     */
    private fun putConfiguration(editor: SharedPreferences.Editor, values: Map<String, Any>, revision: Long, imported: Long) {
        values.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
        } }
        editor.putLong(REVISION, revision).putLong(IMPORT_REVISION, imported)
    }

    /**
     * 明确记录发布失败，已保存的管理端设置仍可读取，等待下一次连接或用户修改。
     * @param error 实际通信或提交错误。
     * @return Unit。
     * Callers: connect、disconnect、update。
     */
    private fun publicationFailed(error: Exception) {
        syncError = error
        failed(error)
        changed()
    }

    /**
     * 将宿主读取失败标记为未就绪，通知诊断并停止配置依赖功能。
     * @param error 实际通信或协议错误。
     * @return Unit。
     * Callers: attachHost、reloadHost。
     */
    private fun hostFailed(error: Exception) {
        isReady = false
        syncError = error
        failed(error)
        changed()
    }
}
